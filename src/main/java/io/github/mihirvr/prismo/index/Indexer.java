package io.github.mihirvr.prismo.index;

import io.github.mihirvr.prismo.db.Database;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Indexes library folders into the {@code media} table, and keeps it in step with the files.
 *
 * <p>The pipeline, per folder:
 * <ol>
 *   <li>Load what's already indexed under the folder (one query).</li>
 *   <li>Walk the folder tree on the calling thread. Media files whose size and modified time are
 *       unchanged are skipped without opening them.</li>
 *   <li>New or changed files are collected into chunks of {@code batchSize}. Each chunk's metadata is
 *       read in parallel on virtual threads (at most {@code MAX_PARALLEL_READS} files open at once).</li>
 *   <li>Each chunk is written in one transaction by the database's single writer thread. While chunk N
 *       is being written, chunk N+1's metadata is already being read; at most one write in flight,
 *       which gives back-pressure if the disk is slower than the readers.</li>
 *   <li>Rows whose file is gone are matched against the new files ({@link MoveMatcher}): a moved or
 *       renamed file keeps its row; the rest are removed. A row is only treated as gone after a complete
 *       walk, never under a folder that couldn't be read, and never when the library folder itself can't
 *       be reached (an unplugged drive): then nothing under it is touched.</li>
 * </ol>
 *
 * <p>{@link #index} walks whole library folders (at launch, after an overflow, as a periodic safety net);
 * {@link #update} applies the file watcher's changed paths. Both block; run them on one background thread,
 * one at a time. Interrupting that thread stops the run before anything is removed.
 */
public final class Indexer {

    private static final Logger log = LoggerFactory.getLogger(Indexer.class);

    static final int DEFAULT_BATCH_SIZE = 500;
    /** Caps open file handles and disk seeks; plenty for SSDs, still kind to spinning disks. */
    private static final int MAX_PARALLEL_READS = 16;
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L; // 100 ms

    private final Database database;
    private final MetadataReader metadataReader;
    private final int batchSize;

    public Indexer(Database database, MetadataReader metadataReader) {
        this(database, metadataReader, DEFAULT_BATCH_SIZE);
    }

    Indexer(Database database, MetadataReader metadataReader, int batchSize) {
        this.database = database;
        this.metadataReader = metadataReader;
        this.batchSize = batchSize;
    }

    /**
     * Indexes every root completely and reports progress (throttled to about 10 updates a second, plus a
     * final one with {@code done = true}).
     *
     * @throws InterruptedException if the calling thread is interrupted; work already written stays
     */
    public IndexProgress index(List<Path> roots, Consumer<IndexProgress> progress)
            throws InterruptedException, SQLException {
        Run run = new Run(progress);
        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Path root : roots) {
                run.indexRoot(root, readers);
            }
            run.finish(readers);
        }
        return run.done("Indexing");
    }

    /**
     * Applies changed paths reported by the file watcher, per library folder. Each path is looked at as it
     * is now: a folder is walked (new files indexed, files gone from it handled), a media file is indexed if
     * new or changed, and a path that no longer exists counts as gone, with everything that was under it.
     * A library folder that can't be reached is skipped entirely.
     */
    public IndexProgress update(Map<Path, Set<Path>> changedByRoot, Consumer<IndexProgress> progress)
            throws InterruptedException, SQLException {
        Run run = new Run(progress);
        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map.Entry<Path, Set<Path>> entry : changedByRoot.entrySet()) {
                if (!Files.isDirectory(entry.getKey())) {
                    log.info("Library folder {} can't be reached; leaving its files as they are", entry.getKey());
                    continue;
                }
                for (Path path : entry.getValue()) {
                    run.indexPath(path, readers);
                }
            }
            run.finish(readers);
        }
        return run.done("Update");
    }

    /** One run's state. Only the calling thread touches it, so it needs no locking. */
    private final class Run {

        private final Consumer<IndexProgress> progress;
        private final long startNanos = System.nanoTime();
        private final Semaphore openFiles = new Semaphore(MAX_PARALLEL_READS);
        private final List<PendingFile> chunk = new ArrayList<>();
        /** Paths already handled in this run, so a file reached twice is read once. */
        private final Set<String> handled = new HashSet<>();
        /** New files (no row before this run): candidates for the far end of a move. */
        private final List<MoveMatcher.Found> added = new ArrayList<>();
        /** Rows whose file is gone, by path. */
        private final Map<String, MediaStore.Row> gone = new LinkedHashMap<>();
        /** Watcher paths that no longer exist; their rows are looked up together in {@link #finish}. */
        private final List<Path> missing = new ArrayList<>();
        private CompletableFuture<Integer> pendingWrite = CompletableFuture.completedFuture(0);
        private long lastReportNanos;
        private int found;
        private int indexed;
        private int unchanged;
        private int failed;
        private int moved;
        private int removed;

        Run(Consumer<IndexProgress> progress) {
            this.progress = progress;
        }

        void indexRoot(Path root, ExecutorService readers) throws InterruptedException, SQLException {
            Path realRoot;
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                // Missing or unreachable (e.g. an unplugged drive): nothing under it is touched.
                log.warn("Skipping library folder {}: {}", root, e.toString());
                failed++;
                return;
            }
            indexFolder(realRoot, readers);
        }

        /** A watcher path: a folder to walk, a file to index, or something gone. */
        void indexPath(Path path, ExecutorService readers) throws InterruptedException, SQLException {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                    missing.add(path);
                } else {
                    log.debug("Cannot read {}: {}", path, e.toString()); // exists but unreadable: leave it
                }
                return;
            }
            if (attrs.isDirectory()) {
                indexFolder(path, readers);
            } else if (attrs.isRegularFile()) {
                Optional<MediaStore.Row> row = database.read(c -> MediaStore.rowAt(c, path.toString()));
                indexFile(path, attrs, row.orElse(null), readers);
            }
        }

        /** Walks {@code folder}; afterwards, rows under it whose file wasn't seen are gone. */
        private void indexFolder(Path folder, ExecutorService readers) throws InterruptedException, SQLException {
            Map<String, MediaStore.Row> known = database.read(c -> MediaStore.rowsUnder(c, folder));
            log.info("Indexing {} ({} files already known)", folder, known.size());
            Visitor visitor = new Visitor(known, readers);
            try {
                Files.walkFileTree(folder, visitor);
            } catch (IOException e) {
                // Only thrown for the folder itself; per-file problems are handled in the visitor.
                log.warn("Could not walk {}: {}", folder, e.toString());
                failed++;
                return; // an incomplete walk proves nothing is gone
            } catch (UncheckedInterrupt e) {
                throw e.cause;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Indexing interrupted");
            }
            for (MediaStore.Row row : known.values()) {
                if (!visitor.seen.contains(row.path()) && !visitor.isUnderUnreadable(row.path())) {
                    gone.put(row.path(), row);
                }
            }
        }

        private void indexFile(Path file, BasicFileAttributes attrs, MediaStore.Row row, ExecutorService readers)
                throws InterruptedException {
            MediaType type = MediaType.fromExtension(MediaType.extensionOf(file.getFileName().toString())).orElse(null);
            if (type == null || !handled.add(file.toString())) {
                return;
            }
            found++;
            long size = attrs.size();
            long modified = attrs.lastModifiedTime().toMillis();
            if (row != null && row.sizeBytes() == size && row.dateModified() == modified) {
                unchanged++;
                report();
                return;
            }
            if (row == null) {
                added.add(new MoveMatcher.Found(file.toString(), size, modified));
            }
            chunk.add(new PendingFile(file, type, size, modified));
            if (chunk.size() >= batchSize) {
                flush(readers);
            }
        }

        /**
         * Paths that no longer exist: each one's row, or every row under it if it was a folder, is gone. One
         * read connection for all of them: a folder of 1,500 deleted files can arrive as 1,500 paths
         * (measured: 2.1 s with a connection per lookup).
         */
        private void collectMissing() throws SQLException {
            if (missing.isEmpty()) {
                return;
            }
            List<MediaStore.Row> rows = database.read(c -> {
                List<MediaStore.Row> found = new ArrayList<>();
                for (Path path : missing) {
                    MediaStore.rowAt(c, path.toString()).ifPresent(found::add);
                    found.addAll(MediaStore.rowsUnder(c, path).values());
                }
                return found;
            });
            rows.forEach(row -> gone.put(row.path(), row));
        }

        /** Reads metadata for the current chunk in parallel, then hands it to the writer. */
        void flush(ExecutorService readers) throws InterruptedException {
            if (chunk.isEmpty()) {
                return;
            }
            List<Future<MediaRecord>> futures = new ArrayList<>(chunk.size());
            for (PendingFile file : chunk) {
                futures.add(readers.submit(() -> {
                    openFiles.acquire();
                    try {
                        return metadataReader.read(file.path(), file.type(), file.size(), file.modified());
                    } finally {
                        openFiles.release();
                    }
                }));
            }
            chunk.clear();

            List<MediaRecord> records = new ArrayList<>(futures.size());
            for (Future<MediaRecord> future : futures) {
                try {
                    records.add(future.get());
                } catch (ExecutionException e) {
                    log.warn("Metadata read failed", e.getCause());
                    failed++;
                }
            }

            awaitPendingWrite(); // at most one write in flight
            long now = System.currentTimeMillis();
            pendingWrite = database.write(connection -> {
                MediaStore.upsert(connection, records, now);
                return records.size();
            });
            indexed += records.size();
            report();
        }

        /** Writes the last chunk, then turns gone rows into moves or removals (one transaction). */
        void finish(ExecutorService readers) throws InterruptedException, SQLException {
            flush(readers);
            awaitPendingWrite();
            collectMissing();
            if (gone.isEmpty()) {
                return;
            }
            MoveMatcher.Result result = MoveMatcher.match(gone.values(), added);
            pendingWrite = database.write(connection -> {
                MediaStore.applyMovesAndRemovals(connection, result);
                return result.moves().size() + result.removed().size();
            });
            awaitPendingWrite();
            moved = result.moves().size();
            removed = result.removed().size();
            indexed -= moved; // a moved file was counted as new when its new path was indexed
        }

        void awaitPendingWrite() throws InterruptedException {
            try {
                pendingWrite.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException("Writing indexed files failed", e.getCause());
            }
        }

        void report() {
            long now = System.nanoTime();
            if (now - lastReportNanos >= PROGRESS_INTERVAL_NANOS) {
                lastReportNanos = now;
                progress.accept(snapshot(false));
            }
        }

        IndexProgress snapshot(boolean done) {
            return new IndexProgress(found, indexed, unchanged, failed, moved, removed, done,
                    Duration.ofNanos(System.nanoTime() - startNanos));
        }

        IndexProgress done(String what) {
            IndexProgress result = snapshot(true);
            progress.accept(result);
            log.info("{} done in {} ms: {} found, {} indexed, {} unchanged, {} failed, {} moved, {} removed", what,
                    result.elapsed().toMillis(), result.found(), result.indexed(), result.unchanged(), result.failed(),
                    result.moved(), result.removed());
            return result;
        }

        private final class Visitor extends SimpleFileVisitor<Path> {

            private final Map<String, MediaStore.Row> known;
            private final ExecutorService readers;
            /** Every media file seen on this walk. */
            final Set<String> seen = new HashSet<>();
            /** Folders (and files) that couldn't be read: rows under them are not "gone", just not visible. */
            private final List<String> unreadable = new ArrayList<>();

            Visitor(Map<String, MediaStore.Row> known, ExecutorService readers) {
                this.known = known;
                this.readers = readers;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return Thread.currentThread().isInterrupted() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                seen.add(file.toString());
                try {
                    indexFile(file, attrs, known.get(file.toString()), readers);
                } catch (InterruptedException e) {
                    // FileVisitor methods can't throw checked InterruptedException; tunnel it out.
                    throw new UncheckedInterrupt(e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // Typical: permission denied on system folders ($RECYCLE.BIN, System Volume Information).
                log.debug("Cannot read {}: {}", file, e.toString());
                unreadable.add(file.toString());
                failed++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                if (e != null) {
                    // Listing the folder broke off part-way: what wasn't listed isn't known to be gone.
                    log.debug("Could not finish reading {}: {}", dir, e.toString());
                    unreadable.add(dir.toString());
                    failed++;
                }
                return FileVisitResult.CONTINUE;
            }

            boolean isUnderUnreadable(String path) {
                for (String prefix : unreadable) {
                    if (path.equals(prefix) || path.startsWith(prefix + File.separatorChar)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    private record PendingFile(Path path, MediaType type, long size, long modified) {
    }

    /** Carries an InterruptedException through FileVisitor, whose methods may only throw IOException. */
    private static final class UncheckedInterrupt extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient InterruptedException cause;

        UncheckedInterrupt(InterruptedException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
