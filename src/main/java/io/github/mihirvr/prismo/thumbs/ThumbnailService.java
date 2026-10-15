package io.github.mihirvr.prismo.thumbs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>The queue is ordered by {@link Priority}: thumbnails on screen jump ahead of warm-up, and a request
 * whose caller cancelled it (the row scrolled away) is skipped instead of decoded.
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

    /** How urgent a request is. Declared most urgent first; the queue orders by this. */
    public enum Priority {
        /** On screen right now. The newest of these runs first: it's the most likely to still be visible. */
        VISIBLE,
        /** Warm-up. Runs in the order it was asked for, after every visible request. */
        BACKGROUND
    }

    /** Writes a thumbnail of {@code source} to {@code target}. Swappable in tests. */
    @FunctionalInterface
    interface Generator {
        void generate(Path source, Path target) throws IOException;
    }

    private final ThumbnailCache cache;
    private final Generator generator;
    private final ThreadPoolExecutor pool;
    private final int poolSize;
    private final ConcurrentHashMap<Long, Job> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public ThumbnailService(ThumbnailCache cache, ThumbnailGenerator generator) {
        this(cache, generator::generate, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    ThumbnailService(ThumbnailCache cache, Generator generator, int poolSize) {
        this.cache = cache;
        this.generator = generator;
        this.poolSize = poolSize;
        // Same as newFixedThreadPool, but the queue is sorted by Task.compareTo instead of first-in-first-out.
        this.pool = new ThreadPoolExecutor(poolSize, poolSize, 0, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), Thread.ofPlatform().name("prismo-thumbs-", 0).daemon(true)
                        // Below normal: when the UI thread and a decode both want a core, the UI wins.
                        .priority(Thread.NORM_PRIORITY - 1).factory());
        // A task handed to a newly started thread skips the queue (and so the ordering). Starting every
        // thread now means every task goes through the queue.
        this.pool.prestartAllCoreThreads();
    }

    /** Same as {@code request(media, Priority.BACKGROUND)}. */
    public CompletableFuture<Result> request(MediaRef media) {
        return request(media, Priority.BACKGROUND);
    }

    /**
     * The thumbnail for {@code media}. Completes immediately when the cached file is fresh; otherwise
     * queues generation, or joins a generation already queued for the same id.
     *
     * <p>Every caller gets its own future. Cancelling it ({@code cancel(false)}) says "I don't need this
     * any more": a job nobody is waiting for is skipped when it reaches the front of the queue. Other
     * callers waiting for the same thumbnail are not affected.
     */
    public CompletableFuture<Result> request(MediaRef media, Priority priority) {
        Path file = cache.fileFor(media.id());
        if (ThumbnailCache.isFresh(file, media.dateModified())) {
            return CompletableFuture.completedFuture(new Ready(file)); // fast path, no thread hop
        }
        CompletableFuture<Result> waiter = new CompletableFuture<>();
        while (true) {
            Job job = inFlight.computeIfAbsent(media.id(), _ -> new Job(media));
            Join join = job.join(waiter, priority);
            switch (join) {
                case JOINED -> {
                    return waiter; // someone already asked at this priority; share their job
                }
                case JOINED_NEEDS_TASK -> {
                    enqueue(new Task(job, priority, sequence.getAndIncrement()));
                    return waiter;
                }
                // The job finished or was dropped a moment ago: forget it and start a new one.
                case CLOSED -> inFlight.remove(media.id(), job);
            }
        }
    }

    private void enqueue(Task task) {
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            finish(task.job(), null, e); // shutting down
        }
    }

    /** Runs on a worker when {@code task} reaches the front of the queue. */
    private void run(Task task) {
        Job job = task.job();
        switch (job.claim(task.priority())) {
            case RUN -> {
                try {
                    finish(job, produce(job.media), null);
                } catch (Throwable t) {
                    finish(job, null, t);
                }
            }
            case ABANDONED -> {
                inFlight.remove(job.media.id(), job);
                log.trace("Skipped thumbnail {}: nobody is waiting for it", job.media.id());
            }
            case SKIP -> {
                // Already running or done, or only lower-priority callers are left (their own task is queued).
            }
        }
    }

    private void finish(Job job, Result result, Throwable error) {
        List<CompletableFuture<Result>> waiters = job.close();
        inFlight.remove(job.media.id(), job);
        for (CompletableFuture<Result> waiter : waiters) { // outside the job's lock: callbacks run here
            if (error == null) {
                waiter.complete(result);
            } else {
                waiter.completeExceptionally(error);
            }
        }
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

    /** Outcome of {@link Job#join}. */
    private enum Join { JOINED, JOINED_NEEDS_TASK, CLOSED }

    /** Outcome of {@link Job#claim}. */
    private enum Claim { RUN, SKIP, ABANDONED }

    private record Waiter(CompletableFuture<Result> future, Priority priority) {
    }

    /**
     * One thumbnail to make, shared by everyone who asked for it. A job can sit in the queue as more
     * than one {@link Task} (one per priority it was asked at); whichever task reaches a worker first
     * and still has someone waiting at its priority runs it, and the rest do nothing.
     */
    private static final class Job {
        final MediaRef media;
        // All guarded by "this".
        private final List<Waiter> waiters = new ArrayList<>();
        private final EnumSet<Priority> queuedAt = EnumSet.noneOf(Priority.class);
        private boolean started;
        private boolean closed;

        Job(MediaRef media) {
            this.media = media;
        }

        synchronized Join join(CompletableFuture<Result> future, Priority priority) {
            if (closed) {
                return Join.CLOSED;
            }
            waiters.add(new Waiter(future, priority));
            // Not started yet and no task at this priority in the queue: the caller must add one.
            // (A background job someone now sees on screen gets a second, visible task this way.)
            return !started && queuedAt.add(priority) ? Join.JOINED_NEEDS_TASK : Join.JOINED;
        }

        /** Called by a worker that took this job's task at {@code priority} off the queue. */
        synchronized Claim claim(Priority priority) {
            queuedAt.remove(priority);
            if (started || closed) {
                return Claim.SKIP;
            }
            waiters.removeIf(waiter -> waiter.future().isDone()); // cancelled by their caller
            if (waiters.isEmpty()) {
                closed = true;
                return Claim.ABANDONED;
            }
            // Only run at this priority if someone is still waiting at it (or more urgently). A visible
            // request that scrolled away must not keep its job at the front of the queue for warm-up.
            if (waiters.stream().noneMatch(waiter -> waiter.priority().compareTo(priority) <= 0)) {
                return Claim.SKIP;
            }
            started = true;
            return Claim.RUN;
        }

        /** Marks the job finished; returns who to tell. */
        synchronized List<CompletableFuture<Result>> close() {
            closed = true;
            List<CompletableFuture<Result>> futures = waiters.stream().map(Waiter::future).toList();
            waiters.clear();
            return futures;
        }
    }

    /**
     * A job's place in the queue. Comparable so the {@link PriorityBlockingQueue} can sort it. An inner
     * (non-static) class so {@link #run} can reach the service.
     */
    private final class Task implements Runnable, Comparable<Task> {
        private final Job job;
        private final Priority priority;
        private final long sequence;

        Task(Job job, Priority priority, long sequence) {
            this.job = job;
            this.priority = priority;
            this.sequence = sequence;
        }

        Job job() {
            return job;
        }

        Priority priority() {
            return priority;
        }

        @Override
        public void run() {
            ThumbnailService.this.run(this);
        }

        @Override
        public int compareTo(Task other) {
            if (priority != other.priority) {
                return priority.compareTo(other.priority);
            }
            return priority == Priority.VISIBLE
                    ? Long.compare(other.sequence, sequence) // newest first
                    : Long.compare(sequence, other.sequence); // in the order asked
        }
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
