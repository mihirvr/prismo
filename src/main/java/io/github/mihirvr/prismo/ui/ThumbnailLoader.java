package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * Gets thumbnails onto the screen. Called on the JavaFX Application Thread only. One instance is shared
 * by the Timeline and the viewer, so the photo you click is already in memory when the viewer opens.
 *
 * <ul>
 *   <li>Keeps the most recently shown images in memory, so scrolling back up shows them instantly.</li>
 *   <li>Asks {@link ThumbnailService} off the UI thread: even a cached thumbnail costs a file check
 *       (the freshness {@code stat}), and the UI thread does no file I/O.</li>
 *   <li>Every load can be cancelled; the timeline cancels when a row scrolls away.</li>
 * </ul>
 */
public final class ThumbnailLoader implements AutoCloseable {

    /**
     * Images kept in memory. A 320 × 240 thumbnail is about 300 KB decoded, so 300 ≈ 90 MB at most.
     * More than a full 4K screen of small thumbnails. A starting value, not tuned.
     */
    private static final int MEMORY_CACHE_SIZE = 300;

    private final ThumbnailService service;
    /** Virtual threads: each lookup is a short blocking file check, the case they're made for. */
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();
    /** Least-recently-used first. Access order: {@code get} moves an entry to the end. */
    private final Map<MediaRef, Image> images = new LinkedHashMap<>(MEMORY_CACHE_SIZE * 4 / 3 + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MediaRef, Image> eldest) {
            return size() > MEMORY_CACHE_SIZE;
        }
    };

    public ThumbnailLoader(ThumbnailService service) {
        this.service = service;
    }

    /** The image if it's in memory, else null. Keyed by the whole {@link MediaRef}, so an edited photo misses. */
    public Image cached(MediaRef ref) {
        return images.get(ref);
    }

    /**
     * Starts loading {@code ref}'s thumbnail. Exactly one callback runs later on the UI thread, unless the
     * returned load is cancelled first: {@code onImage} with an image that may still be decoding (JavaFX
     * background loading), or {@code onNoImage} with why there is none.
     */
    public Load load(MediaRef ref, Consumer<Image> onImage, Consumer<ThumbnailService.Result> onNoImage) {
        Load load = new Load();
        lookups.execute(() -> {
            if (load.isCancelled()) {
                return; // scrolled away before we even started
            }
            CompletableFuture<ThumbnailService.Result> request = service.request(ref, ThumbnailService.Priority.VISIBLE);
            if (!load.attach(request)) {
                return;
            }
            request.whenComplete((result, error) -> Platform.runLater(() -> {
                if (load.isCancelled()) {
                    return;
                }
                if (result instanceof ThumbnailService.Ready(var file)) {
                    // Background loading: JavaFX decodes it on its own thread; the image shows when done.
                    Image image = new Image(file.toUri().toString(), true);
                    images.put(ref, image);
                    image.errorProperty().addListener((_, _, failed) -> {
                        if (failed) {
                            images.remove(ref, image);
                        }
                    });
                    onImage.accept(image);
                } else {
                    onNoImage.accept(result != null ? result : new ThumbnailService.Failed(String.valueOf(error)));
                }
            }));
        });
        return load;
    }

    @Override
    public void close() {
        lookups.shutdownNow();
    }

    /** A load in progress. {@link #cancel} is safe from any thread and more than once. */
    public static final class Load {
        private boolean cancelled; // guarded by this
        private CompletableFuture<ThumbnailService.Result> request; // guarded by this

        synchronized boolean isCancelled() {
            return cancelled;
        }

        /** Stops the load; the service skips the job if nobody else wants it. */
        public synchronized void cancel() {
            cancelled = true;
            if (request != null) {
                request.cancel(false);
            }
        }

        /** Links the service request; false (and cancels it) if this load was cancelled meanwhile. */
        private synchronized boolean attach(CompletableFuture<ThumbnailService.Result> future) {
            if (cancelled) {
                future.cancel(false);
                return false;
            }
            request = future;
            return true;
        }
    }
}
