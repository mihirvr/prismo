package io.github.mihirvr.prismo.index;

import io.github.mihirvr.prismo.db.Database;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Indexes library folders into the {@code media} table.
 *
 * <p>The pipeline, per root:
 * <ol>
 *   <li>Load size + modified time of everything already indexed under the root (one query).</li>
 *   <li>Walk the folder tree on the calling thread. Media files whose size and modified time are
 *       unchanged are skipped without opening them.</li>
 *   <li>New or changed files are collected into chunks of {@code batchSize}. Each chunk's metadata is
 *       read in parallel on virtual threads (at most {@code MAX_PARALLEL_READS} files open at once).</li>
 *   <li>Each chunk is written in one transaction by the database's single writer thread. While chunk N
 *       is being written, chunk N+1's metadata is already being read; at most one write is in flight,
 *       which gives back-pressure if the disk is slower than the readers.</li>
 * </ol>
 *
 * <p>{@link #index} blocks; run it on a background thread. Interrupting that thread stops the run.
 * Files that disappeared since the last run are left in the database for now (they may be on a drive
 * that is just unplugged); handling them is part of Broken &amp; Unavailable Media.
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
     * Indexes every root and reports progress (throttled to about 10 updates a second, plus a final one
     * with {@code done = true}).
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
            run.flush(readers);
            run.awaitPendingWrite();
        }
        IndexProgress result = run.snapshot(true);
        progress.accept(result);
        log.info("Indexing done in {} ms: {} found, {} indexed, {} unchanged, {} failed",
                result.elapsed().toMillis(), result.found(), result.indexed(), result.unchanged(), result.failed());
        return result;
    }

    /** One indexing run's state. Only the calling thread touches it, so it needs no locking. */
    private final class Run {

        private final Consumer<IndexProgress> progress;
        private final long startNanos = System.nanoTime();
        private final Semaphore openFiles = new Semaphore(MAX_PARALLEL_READS);
        private final List<PendingFile> chunk = new ArrayList<>();
        private CompletableFuture<Integer> pendingWrite = CompletableFuture.completedFuture(0);
        private long lastReportNanos;
        private int found;
        private int indexed;
        private int unchanged;
        private int failed;

        Run(Consumer<IndexProgress> progress) {
            this.progress = progress;
        }

        void indexRoot(Path root, ExecutorService readers) throws InterruptedException, SQLException {
            Path realRoot;
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                log.warn("Skipping library folder {}: {}", root, e.toString());
                failed++;
                return;
            }
            Map<String, MediaStore.Stamp> known = database.read(c -> MediaStore.stampsUnder(c, realRoot));
            log.info("Indexing {} ({} files already known)", realRoot, known.size());

            try {
                Files.walkFileTree(realRoot, new Visitor(known, readers));
            } catch (IOException e) {
                // Only thrown for the root itself; per-file problems are handled in visitFileFailed.
                log.warn("Could not walk {}: {}", realRoot, e.toString());
                failed++;
            } catch (UncheckedInterrupt e) {
                throw e.cause;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Indexing interrupted");
            }
        }

        void add(PendingFile file, ExecutorService readers) throws InterruptedException {
            chunk.add(file);
            if (chunk.size() >= batchSize) {
                flush(readers);
            }
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
            return new IndexProgress(found, indexed, unchanged, failed, done,
                    Duration.ofNanos(System.nanoTime() - startNanos));
        }

        private final class Visitor extends SimpleFileVisitor<Path> {

            private final Map<String, MediaStore.Stamp> known;
            private final ExecutorService readers;

            Visitor(Map<String, MediaStore.Stamp> known, ExecutorService readers) {
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
                MediaType type = MediaType.fromExtension(MediaType.extensionOf(file.getFileName().toString()))
                        .orElse(null);
                if (type == null) {
                    return FileVisitResult.CONTINUE;
                }
                found++;
                long size = attrs.size();
                long modified = attrs.lastModifiedTime().toMillis();
                MediaStore.Stamp stamp = known.get(file.toString());
                if (stamp != null && stamp.sizeBytes() == size && stamp.dateModified() == modified) {
                    unchanged++;
                    report();
                    return FileVisitResult.CONTINUE;
                }
                try {
                    add(new PendingFile(file, type, size, modified), readers);
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
                failed++;
                return FileVisitResult.CONTINUE;
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
