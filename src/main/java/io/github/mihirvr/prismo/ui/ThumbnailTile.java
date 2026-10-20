package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.thumbs.ThumbnailGenerator;
import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import java.util.Locale;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
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
 * <p>Rounded corners come from a clip (CSS can round a background, not an image). A video gets a badge in the
 * bottom-right corner: a play mark and its length ("0:12"). Hover and focus looks are in the stylesheet.
 */
final class ThumbnailTile extends StackPane {

    /** Corner radius in layout units; the focus ring's CSS radius matches it. */
    static final double CORNER_RADIUS = 6;
    /** On the progress bar once the video was watched to the end. */
    private static final PseudoClass WATCHED = PseudoClass.getPseudoClass("watched");
    /** On the tile while it is selected (P7). */
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    /** On the tile while its library folder can't be reached (P7). */
    private static final PseudoClass OFFLINE = PseudoClass.getPseudoClass("offline");
    private static final double OFFLINE_ICON_SIZE = 14;
    private static final double FAVORITE_ICON_SIZE = 18;
    private static final double BADGE_ICON_SIZE = 10;
    private static final double PLACEHOLDER_ICON_SIZE = 22;
    private static final double PROGRESS_HEIGHT = 4;
    private static final double CHECK_ICON_SIZE = 14;

    private final ThumbnailLoader loader;
    private final ImageView view = new ImageView();
    private final Label placeholder = new Label();
    /** Drawn over the image when this tile has keyboard focus. */
    private final Region focusRing = new Region();
    /** A small heart in the corner of a favorite. */
    private final Node favorite = Icon.HEART_FILLED.node(FAVORITE_ICON_SIZE);
    /** Lightens the tile under the mouse (CSS: {@code .thumb-tile:hover .thumb-hover}). */
    private final Region hover = new Region();
    /** A play mark and the length, bottom right of a video. */
    private final Label videoBadge = new Label();
    /** Watch progress: a thin bar along the bottom edge of a video (a dark track, the watched part in colour). */
    private final StackPane progress = new StackPane();
    private final Region progressFill = new Region();
    /** Tints a selected tile (CSS: {@code .thumb-tile:selected .thumb-selected}). */
    private final Region selectedTint = new Region();
    /**
     * A round check box, top left: shown under the mouse and while the grid is selecting, filled with a tick once
     * selected. Clicking it selects the tile without opening it ({@link #setOnCheck}).
     */
    private final StackPane check = new StackPane(Icon.CHECK.node(CHECK_ICON_SIZE));
    private boolean selected;
    /** Greys the tile and marks it, top right, while its library folder is offline. */
    private final Region offlineShade = new Region();
    private final StackPane offlineMark = new StackPane(Icon.OFFLINE.node(OFFLINE_ICON_SIZE));
    private boolean offline;
    /** The file wasn't there when its thumbnail was asked for (ThumbnailService.Missing): asked again when back. */
    private boolean missing;
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
        placeholder.setContentDisplay(ContentDisplay.TOP); // a warning icon above "Can't read"
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
        StackPane.setMargin(favorite, new Insets(0, 0, 6, 6));
        hover.getStyleClass().add("thumb-hover");
        hover.setMouseTransparent(true);
        videoBadge.getStyleClass().add("thumb-video-badge");
        videoBadge.setGraphic(Icon.PLAY.node(BADGE_ICON_SIZE));
        videoBadge.setVisible(false);
        videoBadge.setMouseTransparent(true);
        StackPane.setAlignment(videoBadge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(videoBadge, new Insets(0, 6, 6, 0));
        progress.getStyleClass().add("thumb-progress");
        progressFill.getStyleClass().add("thumb-progress-fill");
        progress.getChildren().add(progressFill);
        StackPane.setAlignment(progressFill, Pos.CENTER_LEFT);
        progress.setMaxHeight(PROGRESS_HEIGHT);
        progress.setMinHeight(PROGRESS_HEIGHT);
        progress.setVisible(false);
        progress.setMouseTransparent(true);
        StackPane.setAlignment(progress, Pos.BOTTOM_CENTER);
        selectedTint.getStyleClass().add("thumb-selected");
        selectedTint.setMouseTransparent(true);
        offlineShade.getStyleClass().add("thumb-offline-shade");
        offlineShade.setMouseTransparent(true);
        offlineMark.getStyleClass().add("thumb-offline-mark");
        offlineMark.setMouseTransparent(true);
        offlineMark.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        StackPane.setAlignment(offlineMark, Pos.TOP_RIGHT);
        StackPane.setMargin(offlineMark, new Insets(6, 6, 0, 0));
        check.getStyleClass().add("thumb-check");
        check.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        StackPane.setAlignment(check, Pos.TOP_LEFT);
        StackPane.setMargin(check, new Insets(6, 0, 0, 6));
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(CORNER_RADIUS * 2);
        clip.setArcHeight(CORNER_RADIUS * 2);
        setClip(clip);
        getChildren().addAll(view, placeholder, offlineShade, hover, selectedTint, favorite, videoBadge, progress,
                offlineMark, focusRing, check);
    }

    /** The file this tile shows, or null if it's empty. */
    MediaRef ref() {
        return shown;
    }

    void setFocusRing(boolean on) {
        focusRing.setVisible(on);
    }

    /** Selected (P7): tinted, with a tick in its check circle. */
    void setSelected(boolean on) {
        if (on != selected) {
            selected = on;
            pseudoClassStateChanged(SELECTED, on);
        }
    }

    /** Its library folder can't be reached right now: greyed, with a mark. */
    void setOffline(boolean on) {
        if (on != offline) {
            offline = on;
            pseudoClassStateChanged(OFFLINE, on);
            if (!on && missing && shown != null && pending == null) {
                // Its folder is back: the thumbnail can be made now.
                missing = false;
                placeholder.setText("");
                placeholder.setVisible(false);
                pending = loader.load(shown, this::showImage, this::showNoImage);
            }
        }
    }

    boolean isOffline() {
        return offline;
    }

    boolean isSelected() {
        return selected;
    }

    /** Called when the check circle is clicked (it doesn't open the item); the tile's own click is not run. */
    void setOnCheck(Runnable onCheck) {
        check.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && ref() != null) {
                onCheck.run();
            }
            event.consume();
        });
    }

    void setFavorite(boolean on) {
        favorite.setVisible(on);
    }

    /** A video's length for its badge (0 = unknown: just the play mark). Call after {@link #show}. */
    void setVideoLength(double seconds) {
        if (videoBadge.isVisible()) {
            videoBadge.setText(seconds > 0 ? lengthText(seconds) : "");
            videoBadge.setContentDisplay(seconds > 0 ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY);
        }
    }

    /**
     * The watch-progress bar of a video: how much was watched ({@link WatchProgress#barFraction}); full and in
     * another colour once watched. Call after {@link #show}.
     */
    void setWatchProgress(double watchedSeconds, double durationSeconds) {
        double fraction = videoBadge.isVisible() ? WatchProgress.barFraction(watchedSeconds, durationSeconds) : -1;
        progress.setVisible(fraction >= 0);
        if (fraction >= 0) {
            progressFill.maxWidthProperty().bind(progress.widthProperty().multiply(fraction));
            progressFill.setPrefWidth(Region.USE_COMPUTED_SIZE);
            progress.pseudoClassStateChanged(WATCHED, fraction >= 1);
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
            videoBadge.setText("");
            videoBadge.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
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
        setSelected(false);
        missing = false;
        setOffline(false);
        favorite.setVisible(false);
        videoBadge.setVisible(false);
        progress.setVisible(false);
        view.setImage(null);
        placeholder.setText("");
        placeholder.setGraphic(null);
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
            case ThumbnailService.Missing _ -> {
                missing = true; // offline, or just deleted
                showPlaceholder("Not available");
            }
            case ThumbnailService.Failed _ -> {
                getStyleClass().add("thumb-failed");
                placeholder.setGraphic(Icon.WARNING.node(PLACEHOLDER_ICON_SIZE));
                showPlaceholder("Can't read");
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
