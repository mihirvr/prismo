package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full-size stills for the viewer: decoded in the background, kept for the current photo and its two
 * neighbours (so Left / Right is instant once they're decoded), dropped as soon as they're out of that window.
 *
 * <p>Methods run on the JavaFX Application Thread. The futures complete on a decode thread.
 *
 * <p><b>Threads.</b> Two platform threads: decoding is CPU-bound (virtual threads would add nothing), and two
 * let the photo you're looking at and the next one decode side by side. Normal priority (the thumbnail
 * warm-up runs below normal), because someone is waiting for these.
 *
 * <p><b>Stale work.</b> A queued decode whose photo has left the window is skipped when its turn comes (its
 * future was cancelled). One that is already running finishes and is thrown away.
 */
final class FullImages implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FullImages.class);
    private static final int THREADS = 2;

    /** A decoded image and the photo's real size (it may have been decoded smaller, see {@link StillDecoder}). */
    record Loaded(Image image, int fullWidth, int fullHeight) {
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS,
            Thread.ofPlatform().name("prismo-viewer-decode-", 0).daemon(true).factory());
    /** Keyed by the whole ref, so an edited file (new modified time) is decoded again. */
    private final Map<MediaRef, CompletableFuture<Loaded>> entries = new HashMap<>();

    /** The decoded image for {@code ref}: the one already kept or in progress, or a new decode. */
    CompletableFuture<Loaded> get(MediaRef ref) {
        return entries.computeIfAbsent(ref, this::start);
    }

    /** Forgets every entry not in {@code keep}, so its memory can be reclaimed and queued work skipped. */
    void keepOnly(Collection<MediaRef> keep) {
        Set<MediaRef> wanted = Set.copyOf(keep);
        entries.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().cancel(false);
            return true;
        });
    }

    private CompletableFuture<Loaded> start(MediaRef ref) {
        CompletableFuture<Loaded> future = new CompletableFuture<>();
        pool.execute(() -> {
            if (future.isDone()) {
                return; // cancelled while queued: the viewer has moved on
            }
            long startNanos = System.nanoTime();
            try {
                StillDecoder.Pixels pixels = StillDecoder.decode(ref.path(), StillDecoder.MAX_PIXELS);
                Image image = StillDecoder.toImage(pixels);
                log.debug("Decoded {} ({}×{}, shown {}×{}) in {} ms", ref.path().getFileName(),
                        pixels.fullWidth(), pixels.fullHeight(), pixels.width(), pixels.height(),
                        (System.nanoTime() - startNanos) / 1_000_000);
                future.complete(new Loaded(image, pixels.fullWidth(), pixels.fullHeight()));
            } catch (Exception | OutOfMemoryError e) {
                // OutOfMemoryError: a huge image is one photo's problem, not the app's; its arrays are already unreachable.
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public void close() {
        entries.values().forEach(future -> future.cancel(false));
        entries.clear();
        pool.shutdownNow();
    }
}
