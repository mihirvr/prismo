package io.github.mihirvr.prismo.thumbs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands out thumbnails: from the cache when fresh, otherwise generated in the background.
 *
 * <p>Generation is CPU-bound (decode + scale + encode), so it runs on a fixed pool of platform threads,
 * one per core minus one (the UI keeps a core). Virtual threads wouldn't help: they add concurrency, not
 * CPU.
 *
 * <p>Concurrent requests for the same media id share one job (see {@link #request}); the UI asking for
 * the same thumbnail twice while scrolling never decodes it twice.
 */
public final class ThumbnailService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;

    /** The outcome of a thumbnail request. */
    public sealed interface Result permits Ready, Unsupported, Failed {
    }

    /** The thumbnail exists at {@code file}. */
    public record Ready(Path file) implements Result {
    }

    /** No decoder for this format yet (video, HEIC, RAW until their issues land). */
    public record Unsupported(String extension) implements Result {
    }

    /** The file couldn't be decoded (corrupt, truncated, missing). Not retried until the file changes. */
    public record Failed(String reason) implements Result {
    }

    /** Warm-up progress: how many of {@code total} are done, by outcome. */
    public record WarmUpProgress(int total, int ready, int unsupported, int failed, boolean done) {
    }

    /** Writes a thumbnail of {@code source} to {@code target}. Swappable in tests. */
    @FunctionalInterface
    interface Generator {
        void generate(Path source, Path target) throws IOException;
    }

    private final ThumbnailCache cache;
    private final Generator generator;
    private final ExecutorService pool;
    private final int poolSize;
    private final ConcurrentHashMap<Long, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

    public ThumbnailService(ThumbnailCache cache, ThumbnailGenerator generator) {
        this(cache, generator::generate, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    ThumbnailService(ThumbnailCache cache, Generator generator, int poolSize) {
        this.cache = cache;
        this.generator = generator;
        this.poolSize = poolSize;
        this.pool = Executors.newFixedThreadPool(poolSize,
                Thread.ofPlatform().name("prismo-thumbs-", 0).daemon(true).factory());
    }

    /**
     * The thumbnail for {@code media}. Completes immediately when the cached file is fresh; otherwise
     * queues generation, or joins a generation already queued for the same id.
     */
    public CompletableFuture<Result> request(MediaRef media) {
        Path file = cache.fileFor(media.id());
        if (ThumbnailCache.isFresh(file, media.dateModified())) {
            return CompletableFuture.completedFuture(new Ready(file)); // fast path, no thread hop
        }
        CompletableFuture<Result> created = new CompletableFuture<>();
        CompletableFuture<Result> existing = inFlight.putIfAbsent(media.id(), created);
        if (existing != null) {
            return existing; // someone already asked; share their job
        }
        try {
            pool.execute(() -> {
                try {
                    created.complete(produce(media));
                } catch (Throwable t) {
                    created.completeExceptionally(t);
                } finally {
                    inFlight.remove(media.id(), created);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(media.id(), created);
            created.completeExceptionally(e);
        }
        return created;
    }

    private Result produce(MediaRef media) {
        Path file = cache.fileFor(media.id());
        Path marker = cache.failMarkerFor(media.id());
        // Re-check: another request may have finished this one while we waited in the queue.
        if (ThumbnailCache.isFresh(file, media.dateModified())) {
            return new Ready(file);
        }
        if (ThumbnailCache.isFresh(marker, media.dateModified())) {
            return new Failed("failed before; waiting for the file to change");
        }
        if (!ThumbnailGenerator.canDecode(media.extension())) {
            return new Unsupported(media.extension());
        }

        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            // Write to a temp file, then rename into place: readers never see a half-written JPEG, and
            // a crash mid-write leaves only a stray .tmp, never a corrupt thumbnail.
            temp = Files.createTempFile(file.getParent(), media.id() + "-", ".tmp");
            generator.generate(media.path(), temp);
            Files.setLastModifiedTime(temp, FileTime.fromMillis(media.dateModified()));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(marker);
            return new Ready(file);
        } catch (IOException | RuntimeException e) {
            log.debug("Thumbnail failed for {}: {}", media.path(), e.toString());
            deleteQuietly(temp);
            writeFailMarker(marker, media.dateModified());
            return new Failed(e.toString());
        }
    }

    /**
     * Generates thumbnails for {@code media} in order (pass newest first), blocking until all are done.
     * Keeps at most a few jobs per thread queued, so a 100,000-item library doesn't create 100,000
     * futures at once. Interrupting the calling thread stops it.
     */
    public WarmUpProgress warmUp(List<MediaRef> media, Consumer<WarmUpProgress> progress) throws InterruptedException {
        Semaphore window = new Semaphore(poolSize * 4);
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger unsupported = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        long lastReport = 0;
        for (MediaRef ref : media) {
            window.acquire(); // throws InterruptedException when the app shuts down
            request(ref).whenComplete((result, error) -> {
                switch (result) {
                    case Ready _ -> ready.incrementAndGet();
                    case Unsupported _ -> unsupported.incrementAndGet();
                    case Failed _ -> failed.incrementAndGet();
                    case null -> failed.incrementAndGet(); // the job itself threw
                }
                window.release();
            });
            long now = System.nanoTime();
            if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                lastReport = now;
                progress.accept(new WarmUpProgress(media.size(), ready.get(), unsupported.get(), failed.get(), false));
            }
        }
        window.acquire(poolSize * 4); // wait for the last jobs
        window.release(poolSize * 4);
        WarmUpProgress done = new WarmUpProgress(media.size(), ready.get(), unsupported.get(), failed.get(), true);
        progress.accept(done);
        return done;
    }

    private static void writeFailMarker(Path marker, long sourceModified) {
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, new byte[0]);
            Files.setLastModifiedTime(marker, FileTime.fromMillis(sourceModified));
        } catch (IOException e) {
            log.debug("Could not write fail marker {}", marker, e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete {}", path, e);
        }
    }

    /** Stops queued work; running jobs get a few seconds to finish their current file. */
    @Override
    public void close() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Thumbnail threads did not stop within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
