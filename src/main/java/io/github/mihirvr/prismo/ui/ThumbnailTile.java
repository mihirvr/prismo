package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.thumbs.ThumbnailGenerator;
import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import java.util.Locale;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/**
 * One square thumbnail: the image cropped to a centred square (like a phone gallery), or a placeholder
 * while it loads, or for files without a thumbnail (HEIC / RAW for now, or unreadable files).
 *
 * <p>Tiles are reused as the list scrolls: {@link #show} switches a tile to another photo and cancels
 * the old one's load.
 *
 * <p>Rounded corners come from a clip (CSS can round a background, not an image). A video gets a "▶ 0:12"
 * badge in the bottom-right corner. Hover and focus looks are in the stylesheet.
 */
final class ThumbnailTile extends StackPane {

    /** Corner radius in layout units; the focus ring's CSS radius matches it. */
    static final double CORNER_RADIUS = 6;

    private final ThumbnailLoader loader;
    private final ImageView view = new ImageView();
    private final Label placeholder = new Label();
    /** Drawn over the image when this tile has keyboard focus. */
    private final Region focusRing = new Region();
    /** A small heart in the corner of a favorite. */
    private final Label favorite = new Label("♥");
    /** Lightens the tile under the mouse (CSS: {@code .thumb-tile:hover .thumb-hover}). */
    private final Region hover = new Region();
    /** "▶" and the length, bottom right of a video. */
    private final Label videoBadge = new Label();
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
        focusRing.getStyleClass().add("thumb-focus-ring");
        focusRing.setVisible(false);
        focusRing.setMouseTransparent(true);
        favorite.getStyleClass().add("thumb-favorite");
        favorite.setVisible(false);
        favorite.setMouseTransparent(true);
        StackPane.setAlignment(favorite, Pos.BOTTOM_LEFT);
        hover.getStyleClass().add("thumb-hover");
        hover.setMouseTransparent(true);
        videoBadge.getStyleClass().add("thumb-video-badge");
        videoBadge.setVisible(false);
        videoBadge.setMouseTransparent(true);
        StackPane.setAlignment(videoBadge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(videoBadge, new Insets(0, 6, 6, 0));
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(CORNER_RADIUS * 2);
        clip.setArcHeight(CORNER_RADIUS * 2);
        setClip(clip);
        getChildren().addAll(view, placeholder, hover, favorite, videoBadge, focusRing);
    }

    /** The file this tile shows, or null if it's empty. */
    MediaRef ref() {
        return shown;
    }

    void setFocusRing(boolean on) {
        focusRing.setVisible(on);
    }

    void setFavorite(boolean on) {
        favorite.setVisible(on);
    }

    /** A video's length for its badge (0 = unknown: just "▶"). Call after {@link #show}. */
    void setVideoLength(double seconds) {
        if (videoBadge.isVisible()) {
            videoBadge.setText(seconds > 0 ? "▶ " + lengthText(seconds) : "▶");
        }
    }

    /** Shows only {@code text} at {@code size} × {@code size} (an album with no photo to use as its cover). */
    void showText(String text, double size) {
        clear();
        setPrefSize(size, size);
        showPlaceholder(text);
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
        if (MediaType.fromExtension(ref.extension()).orElse(null) == MediaType.VIDEO) {
            videoBadge.setText("▶");
            videoBadge.setVisible(true);
        }
        if (!ThumbnailGenerator.canMake(ref.extension())) {
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
        focusRing.setVisible(false);
        favorite.setVisible(false);
        videoBadge.setVisible(false);
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

    /** "0:07", "12:30", "1:02:03": a video's length, rounded to the second (at least "0:01", like phone galleries). */
    static String lengthText(double seconds) {
        long total = Math.max(1, Math.round(seconds));
        long hours = total / 3600;
        long minutes = total / 60 % 60;
        long secs = total % 60;
        return hours > 0 ? "%d:%02d:%02d".formatted(hours, minutes, secs) : "%d:%02d".formatted(minutes, secs);
    }

    /** "HEIC", "NEF" …: a type Prismo can't make thumbnails for yet (videos have real ones). */
    static String unsupportedText(String extension) {
        return extension.toUpperCase(Locale.ROOT);
    }
}
