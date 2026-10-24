package io.github.mihirvr.prismo.thumbs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import io.github.mihirvr.prismo.index.MediaType;
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
 *
 * <p>Warm-up goes over the library twice (1.0.1): first only the thumbnails that are quick to make (little of the file
 * is read), then the rest, videos last and only {@link #VIDEO_JOBS} of them at once. On a hard disk or a NAS most of
 * the grid is filled in minutes this way, instead of waiting behind every big video and old photo in date order.
 */
public final class ThumbnailService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;

    /** The outcome of a thumbnail request. */
    public sealed interface Result permits Ready, Unsupported, Failed, Missing {
    }

    /** The thumbnail exists at {@code file}. */
    public record Ready(Path file) implements Result {
    }

    /** No decoder for this format yet (HEIC, RAW until their issues land). */
    public record Unsupported(String extension) implements Result {
    }

    /** The file couldn't be decoded (corrupt, truncated). Not retried until the file changes. */
    public record Failed(String reason) implements Result {
    }

    /**
     * The file isn't there: its drive is unplugged or its network folder is off (P7), or it was just deleted. Nothing
     * is remembered, so it is tried again next time: a drive that is back must not keep "failed" thumbnails.
     */
    public record Missing(String reason) implements Result {
    }

    /** Warm-up progress: how many of {@code total} are done, by outcome ({@code missing}: files not there). */
    public record WarmUpProgress(int total, int ready, int unsupported, int failed, int missing, boolean done) {
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

        /** Pass 1 of the warm-up: makes the thumbnail only if that is quick; false, writing nothing, if it isn't. */
        default boolean generateQuick(Path source, Path target) throws IOException {
            return false;
        }
    }

    /**
     * Video thumbnails the warm-up makes at once. A video thumbnail can read a lot of a big file (old camera AVIs are
     * 100+ MB); with 7 at once a hard disk's queue was 17 deep and the quick photo reads waited behind them.
     */
    static final int VIDEO_JOBS = 2;

    private final ThumbnailCache cache;
    private final Generator generator;
    private final ThreadPoolExecutor pool;
    /** The number of generating threads now (Settings › Performance can change it while the app runs). */
    private volatile int poolSize;
    private final ConcurrentHashMap<Long, Job> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /** The most threads that can make thumbnails: one per core. */
    public static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();
    /** The default: one per core minus one, so the UI keeps a core. */
    public static final int DEFAULT_THREADS = Math.max(1, MAX_THREADS - 1);

    public ThumbnailService(ThumbnailCache cache, ThumbnailGenerator generator) {
        this(cache, new Generator() {
            @Override
            public void generate(Path source, Path target) throws IOException {
                generator.generate(source, target);
            }

            @Override
            public boolean generateQuick(Path source, Path target) throws IOException {
                return generator.generateQuick(source, target);
            }
        }, DEFAULT_THREADS);
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

    /**
     * Settings › Performance: how many threads make thumbnails, 1 to {@link #MAX_THREADS}. Takes effect at
     * once: more threads start now; with fewer, the extra ones end after the job they are on.
     */
    public void setThreads(int threads) {
        int wanted = Math.clamp(threads, 1, MAX_THREADS);
        // The core size may never be above the maximum, so the order depends on the direction.
        if (wanted > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(wanted);
            pool.setCorePoolSize(wanted);
        } else {
            pool.setCorePoolSize(wanted);
            pool.setMaximumPoolSize(wanted);
        }
        pool.prestartAllCoreThreads(); // so every task still goes through the sorted queue
        poolSize = wanted;
    }

    /** The number of generating threads now. */
    public int threads() {
        return poolSize;
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
        if (!ThumbnailGenerator.canMake(media.extension())) {
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
            deleteQuietly(temp);
            if (e instanceof NoSuchFileException || !Files.exists(media.path())) {
                log.debug("No thumbnail for {}: the file isn't there", media.path());
                return new Missing(e.toString()); // no marker: tried again once the file is back
            }
            log.warn("Thumbnail failed for {}: {}", media.path(), e.toString()); // once per file: then it is marked
            writeFailMarker(marker, media.dateModified());
            return new Failed(e.toString());
        }
    }

    /**
     * Generates thumbnails for {@code media} (pass newest first), blocking until all are done: pass 1 makes the quick
     * ones in that order, pass 2 the rest, stills before videos. Keeps at most a few jobs per thread queued, so a
     * 100,000-item library doesn't create 100,000 futures at once. Interrupting the calling thread stops it.
     */
    public WarmUpProgress warmUp(List<MediaRef> media, Consumer<WarmUpProgress> progress) throws InterruptedException {
        int jobs = poolSize * 4; // read once: Settings may change the thread count meanwhile
        Semaphore window = new Semaphore(jobs);
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger unsupported = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();
        AtomicLong lastReport = new AtomicLong();
        // Called from the loops below and from worker threads: once everything is queued a loop is
        // waiting, so only the finished jobs can keep the count moving.
        Runnable report = () -> {
            long now = System.nanoTime();
            long last = lastReport.get();
            if (now - last >= PROGRESS_INTERVAL_NANOS && lastReport.compareAndSet(last, now)) {
                progress.accept(new WarmUpProgress(media.size(), ready.get(), unsupported.get(), failed.get(),
                        missing.get(), false));
            }
        };
        Consumer<Result> count = result -> {
            switch (result) {
                case Ready _ -> ready.incrementAndGet();
                case Unsupported _ -> unsupported.incrementAndGet();
                case Failed _ -> failed.incrementAndGet();
                case Missing _ -> missing.incrementAndGet();
                case null -> failed.incrementAndGet(); // the job itself threw
            }
        };

        // Pass 1: only what is quick. Set by worker threads, read after the window is whole again (which the
        // semaphore orders after every write).
        boolean[] later = new boolean[media.size()];
        for (int i = 0; i < media.size(); i++) {
            int index = i;
            window.acquire(); // throws InterruptedException when the app shuts down
            requestQuick(media.get(i)).whenComplete((result, error) -> {
                if (result == null) {
                    later[index] = true; // not quick: pass 2
                } else {
                    count.accept(result);
                }
                report.run(); // before the release: the final report below comes after every callback
                window.release();
            });
            report.run();
        }
        window.acquire(jobs);
        window.release(jobs);

        // Pass 2: the rest, stills first, then videos, at most VIDEO_JOBS of those at once.
        List<MediaRef> stills = new ArrayList<>();
        List<MediaRef> videos = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            if (later[i]) {
                MediaRef ref = media.get(i);
                (MediaType.fromExtension(ref.extension()).orElse(null) == MediaType.VIDEO ? videos : stills).add(ref);
            }
        }
        Semaphore videoJobs = new Semaphore(VIDEO_JOBS);
        for (List<MediaRef> part : List.of(stills, videos)) {
            boolean video = part == videos;
            for (MediaRef ref : part) {
                window.acquire();
                if (video) {
                    try {
                        videoJobs.acquire();
                    } catch (InterruptedException e) {
                        window.release();
                        throw e;
                    }
                }
                request(ref).whenComplete((result, error) -> {
                    count.accept(result);
                    report.run();
                    if (video) {
                        videoJobs.release();
                    }
                    window.release();
                });
                report.run();
            }
        }
        window.acquire(jobs); // wait for the last jobs
        window.release(jobs);
        WarmUpProgress done = new WarmUpProgress(media.size(), ready.get(), unsupported.get(), failed.get(),
                missing.get(), true);
        progress.accept(done);
        return done;
    }

    /**
     * Pass 1 of {@link #warmUp}: the thumbnail if it is quick to make, queued at warm-up priority. Completes with null
     * when it isn't, or when the quick try fails: pass 2 then makes it the usual way, which also says why it failed
     * and remembers that. A file someone else is already making is left to them (null too).
     */
    CompletableFuture<Result> requestQuick(MediaRef media) {
        Path file = cache.fileFor(media.id());
        if (ThumbnailCache.isFresh(file, media.dateModified())) {
            return CompletableFuture.completedFuture(new Ready(file));
        }
        if (ThumbnailCache.isFresh(cache.failMarkerFor(media.id()), media.dateModified())) {
            return CompletableFuture.completedFuture(new Failed("failed before; waiting for the file to change"));
        }
        if (!ThumbnailGenerator.canMake(media.extension())) {
            return CompletableFuture.completedFuture(new Unsupported(media.extension()));
        }
        CompletableFuture<Result> result = new CompletableFuture<>();
        try {
            pool.execute(new QuickTask(media, result, sequence.getAndIncrement()));
        } catch (RejectedExecutionException e) {
            result.complete(null); // shutting down
        }
        return result;
    }

    /** Runs on a worker: makes {@code media}'s thumbnail if that is quick, else completes {@code result} with null. */
    private void runQuick(MediaRef media, CompletableFuture<Result> result) {
        Path file = cache.fileFor(media.id());
        if (ThumbnailCache.isFresh(file, media.dateModified())) {
            result.complete(new Ready(file));
            return;
        }
        if (inFlight.containsKey(media.id())) {
            result.complete(null); // a visible request is making it in full; pass 2 finds it made
            return;
        }
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), media.id() + "-", ".tmp");
            if (!generator.generateQuick(media.path(), temp)) {
                result.complete(null);
                return;
            }
            Files.setLastModifiedTime(temp, FileTime.fromMillis(media.dateModified()));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            result.complete(new Ready(file));
        } catch (IOException | RuntimeException e) {
            result.complete(null); // pass 2 tries again and says why
        } finally {
            deleteQuietly(temp);
        }
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
     * A place in the queue. Comparable so the {@link PriorityBlockingQueue} can sort it. Inner (non-static)
     * classes so they can reach the service.
     */
    private abstract class Queued implements Runnable, Comparable<Queued> {
        private final Priority priority;
        private final long sequence;

        Queued(Priority priority, long sequence) {
            this.priority = priority;
            this.sequence = sequence;
        }

        Priority priority() {
            return priority;
        }

        @Override
        public int compareTo(Queued other) {
            if (priority != other.priority) {
                return priority.compareTo(other.priority);
            }
            return priority == Priority.VISIBLE
                    ? Long.compare(other.sequence, sequence) // newest first
                    : Long.compare(sequence, other.sequence); // in the order asked
        }
    }

    /** A job's place in the queue. */
    private final class Task extends Queued {
        private final Job job;

        Task(Job job, Priority priority, long sequence) {
            super(priority, sequence);
            this.job = job;
        }

        Job job() {
            return job;
        }

        @Override
        public void run() {
            ThumbnailService.this.run(this);
        }
    }

    /** A quick try for the warm-up's first pass ({@link #requestQuick}), queued like warm-up. */
    private final class QuickTask extends Queued {
        private final MediaRef media;
        private final CompletableFuture<Result> result;

        QuickTask(MediaRef media, CompletableFuture<Result> result, long sequence) {
            super(Priority.BACKGROUND, sequence);
            this.media = media;
            this.result = result;
        }

        @Override
        public void run() {
            runQuick(media, result);
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
