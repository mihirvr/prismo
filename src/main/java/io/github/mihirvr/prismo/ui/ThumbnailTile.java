package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.thumbs.ThumbnailGenerator;
import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import java.util.Locale;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

/**
 * One square thumbnail: the image cropped to a centred square (like a phone gallery), or a placeholder
 * while it loads, or for files without a thumbnail (video / HEIC / RAW for now, or unreadable files).
 *
 * <p>Tiles are reused as the list scrolls: {@link #show} switches a tile to another photo and cancels
 * the old one's load.
 */
final class ThumbnailTile extends StackPane {

    private final ThumbnailLoader loader;
    private final ImageView view = new ImageView();
    private final Label placeholder = new Label();
    private MediaRef shown;
    private ThumbnailLoader.Load pending;
    private Image waitingFor;
    private final ChangeListener<Number> onProgress = (_, _, progress) -> {
        if (progress.doubleValue() >= 1) {
            imageFinished();
        }
    };

    ThumbnailTile(ThumbnailLoader loader) {
        this.loader = loader;
        getStyleClass().add("thumb-tile");
        placeholder.getStyleClass().add("thumb-placeholder");
        placeholder.setWrapText(true);
        view.setSmooth(true);
        setMinSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        getChildren().addAll(view, placeholder);
    }

    /** Shows {@code ref} at {@code size} × {@code size}. Cheap if it's already showing {@code ref}. */
    void show(MediaRef ref, double size) {
        setPrefSize(size, size);
        view.setFitWidth(size);
        view.setFitHeight(size);
        if (ref.equals(shown)) {
            return;
        }
        clear();
        shown = ref;
        if (!ThumbnailGenerator.canDecode(ref.extension())) {
            showPlaceholder(unsupportedText(ref.extension())); // known up front: no need to ask the service
            return;
        }
        Image image = loader.cached(ref);
        if (image != null) {
            showImage(image);
        } else {
            pending = loader.load(ref, this::showImage, this::showNoImage);
        }
    }

    /** Empties the tile and cancels its load (the row scrolled away or was reused). */
    void clear() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        stopWaiting();
        shown = null;
        view.setImage(null);
        placeholder.setText("");
        placeholder.setVisible(false);
        getStyleClass().remove("thumb-failed");
    }

    private void showImage(Image image) {
        pending = null;
        view.setImage(image);
        if (image.getProgress() >= 1) {
            imageFinished();
        } else {
            view.setVisible(false); // until decoded: its size isn't known yet, so it can't be cropped
            waitingFor = image;
            image.progressProperty().addListener(onProgress);
        }
    }

    private void imageFinished() {
        Image image = view.getImage();
        stopWaiting();
        if (image == null) {
            return;
        }
        if (image.isError()) {
            showNoImage(new ThumbnailService.Failed("cached thumbnail unreadable"));
            return;
        }
        // Centre square of the image, scaled to the tile by fitWidth/fitHeight.
        double side = Math.min(image.getWidth(), image.getHeight());
        view.setViewport(new Rectangle2D((image.getWidth() - side) / 2, (image.getHeight() - side) / 2, side, side));
        view.setVisible(true);
    }

    private void stopWaiting() {
        if (waitingFor != null) {
            waitingFor.progressProperty().removeListener(onProgress);
            waitingFor = null;
        }
    }

    private void showNoImage(ThumbnailService.Result result) {
        pending = null;
        view.setImage(null);
        switch (result) {
            case ThumbnailService.Unsupported(String extension) -> showPlaceholder(unsupportedText(extension));
            case ThumbnailService.Failed _ -> {
                getStyleClass().add("thumb-failed");
                showPlaceholder("⚠\nCan't read");
            }
            case ThumbnailService.Ready _ -> {
                // not passed here
            }
        }
    }

    private void showPlaceholder(String text) {
        placeholder.setText(text);
        placeholder.setVisible(true);
    }

    /** "▶ MP4" for videos, "HEIC", "NEF" … for the rest. */
    static String unsupportedText(String extension) {
        String name = extension.toUpperCase(Locale.ROOT);
        boolean video = MediaType.fromExtension(extension).orElse(null) == MediaType.VIDEO;
        return video ? "▶\n" + name : name;
    }
}
