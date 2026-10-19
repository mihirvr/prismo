package io.github.mihirvr.prismo.watch;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Turns the watcher's stream of events into batches: debouncing.
 *
 * <p>Events are collected until there has been no new one for {@code quiet} (a file being copied sends many
 * "modified" events; a folder move sends several at once), but never longer than {@code maxWait} after the
 * first one, so a folder that never stops changing is still picked up. Each path appears once in a batch.
 * A path inside another path of the same batch is dropped (handling the outer one covers it), and an
 * overflowed library folder drops all its paths (it will be rescanned whole).
 *
 * <p>The listener methods are called on the watcher thread; batches are handed over on this class's own
 * thread. Both only take a short lock.
 */
public final class ChangeBatcher implements FileWatcher.Listener, AutoCloseable {

    /**
     * One batch of work.
     *
     * @param changed by library folder: paths to look at again (outermost only)
     * @param rescan  library folders that lost events and need a full rescan
     * @param stopped library folders no longer watched, with the reason
     */
    public record Batch(Map<Path, Set<Path>> changed, Set<Path> rescan, Map<Path, String> stopped) {

        public boolean isEmpty() {
            return changed.isEmpty() && rescan.isEmpty() && stopped.isEmpty();
        }
    }

    private final long quietNanos;
    private final long maxWaitNanos;
    private final Consumer<Batch> onBatch;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("prismo-watch-batch").daemon(true).factory());

    // Guarded by "this".
    private Map<Path, Set<Path>> changed = new LinkedHashMap<>();
    private Set<Path> rescan = new LinkedHashSet<>();
    private Map<Path, String> stopped = new LinkedHashMap<>();
    private long firstEventNanos;
    private ScheduledFuture<?> pending;

    public ChangeBatcher(Duration quiet, Duration maxWait, Consumer<Batch> onBatch) {
        this.quietNanos = quiet.toNanos();
        this.maxWaitNanos = maxWait.toNanos();
        this.onBatch = onBatch;
    }

    @Override
    public synchronized void changed(Path root, Path path) {
        if (!rescan.contains(root)) {
            changed.computeIfAbsent(root, _ -> new TreeSet<>()).add(path);
        }
        schedule();
    }

    @Override
    public synchronized void overflow(Path root) {
        rescan.add(root);
        changed.remove(root);
        schedule();
    }

    @Override
    public synchronized void stopped(Path root, String reason) {
        stopped.put(root, reason);
        schedule();
    }

    /** Starts or restarts the quiet-period timer, capped at {@code maxWait} after the batch's first event. */
    private void schedule() {
        long now = System.nanoTime();
        if (pending == null) {
            firstEventNanos = now;
        } else {
            pending.cancel(false);
        }
        long delay = Math.max(0, Math.min(quietNanos, firstEventNanos + maxWaitNanos - now));
        pending = timer.schedule(this::flush, delay, TimeUnit.NANOSECONDS);
    }

    private void flush() {
        Batch batch;
        synchronized (this) {
            Map<Path, Set<Path>> outermost = new LinkedHashMap<>();
            changed.forEach((root, paths) -> outermost.put(root, outermost(paths)));
            batch = new Batch(outermost, rescan, stopped);
            changed = new LinkedHashMap<>();
            rescan = new LinkedHashSet<>();
            stopped = new LinkedHashMap<>();
            pending = null;
        }
        if (!batch.isEmpty()) {
            onBatch.accept(batch);
        }
    }

    /**
     * Drops every path that has another path of the set as an ancestor. Pure. {@code Path.startsWith}
     * compares whole names, so {@code a\b} contains {@code a\b\x} but not {@code a\bc}. In sorted order an
     * ancestor always comes first; each path is checked against every kept one (a batch is small).
     */
    static Set<Path> outermost(Set<Path> paths) {
        List<Path> kept = new ArrayList<>();
        for (Path path : new TreeSet<>(paths)) {
            boolean inside = false;
            for (Path outer : kept) {
                if (path.startsWith(outer)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                kept.add(path);
            }
        }
        return new LinkedHashSet<>(kept);
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
