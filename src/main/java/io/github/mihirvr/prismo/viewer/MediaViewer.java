package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.ItemAction;
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The media viewer: one photo or video at a time, drawn over the whole main window (an overlay, not a
 * second window). It works on the Timeline's list, so Left / Right step through the Timeline's order.
 *
 * <p><b>Cache-first.</b> A still shows its cached 320 px thumbnail at once, already at the size and place the
 * full image will take (from the indexed width / height), then the full image is swapped in when decoded. The
 * neighbours are decoded ahead ({@link FullImages}). Anything that finishes for a photo that's no longer
 * shown is dropped.
 *
 * <p><b>Keys:</b> Left / Right previous / next, Esc back (or out of full screen first), F11 full screen,
 * R rotate (view only), + / − / 0 zoom in / out / fit, Space play / pause a video or start / stop the slideshow,
 * F favorite, H hide, Delete to Trash (in Trash: delete permanently). An action that takes the item out of the
 * list it was opened on (hide, Trash, restore, delete) moves on to the next one; favoriting keeps it.
 * <b>Mouse:</b> wheel zooms around the cursor, drag pans, double-click toggles fit / 100%, trackpad pinch zooms.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class MediaViewer extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(MediaViewer.class);
    /** Zoom per mouse-wheel notch (JavaFX reports about 40 units per notch on Windows: not measured here). */
    private static final double WHEEL_ZOOM_PER_NOTCH = 1.2;
    private static final double WHEEL_UNITS_PER_NOTCH = 40;
    private static final double KEY_ZOOM_STEP = 1.25;

    private final ThumbnailLoader thumbnails;
    private final Consumer<String> openExternally;
    private final FullImages fullImages = new FullImages();
    private final ViewTransform transform = new ViewTransform();
    private final Slideshow slideshow = new Slideshow(this::slideshowTick);

    private final Pane imageLayer = new Pane();
    private final ImageView imageView = new ImageView();
    private final VideoPane video = new VideoPane();
    private final Label message = new Label();
    private final Button openExternal = new Button("Open with default app");
    private final VBox messageBox = new VBox(12, message, openExternal);
    private final Label fileName = new Label();
    private final Label counter = new Label();
    private final Label zoom = new Label();
    private final Button slideshowButton = new Button();
    /** One button per action {@link ItemAction#available} for the shown item, rebuilt for each item. */
    private final HBox actionButtons = new HBox(6);

    private List<TimelineItem> items = List.of();
    private int index = -1;
    private Consumer<TimelineItem> onClose = _ -> { };
    private ItemAction.Handler onAction = (_, _) -> false;

    /** The thumbnail load for the shown item, if one is running. */
    private ThumbnailLoader.Load thumbnailLoad;
    /** True once the full image (or the animated GIF) is on screen: a late thumbnail must not replace it. */
    private boolean fullShown;
    /** True once the image's real size is known (indexed, or from the thumbnail / decoded image). */
    private boolean sizeKnown;
    private Image animated;
    private double dragX;
    private double dragY;

    /**
     * @param thumbnails     the Timeline's loader, so the photo just clicked is already in memory
     * @param openExternally opens a file URI in the system's default app ({@code HostServices::showDocument})
     */
    public MediaViewer(ThumbnailLoader thumbnails, Consumer<String> openExternally) {
        this.thumbnails = thumbnails;
        this.openExternally = openExternally;
        getStyleClass().add("viewer");
        setVisible(false);
        setFocusTraversable(true);

        imageView.setManaged(false); // placed by applyTransform()
        imageView.setSmooth(true);
        imageLayer.getChildren().add(imageView);
        imageLayer.widthProperty().addListener((_, _, _) -> viewResized());
        imageLayer.heightProperty().addListener((_, _, _) -> viewResized());
        setUpMouse();

        message.getStyleClass().add("viewer-message");
        message.setWrapText(true);
        messageBox.setAlignment(Pos.CENTER);
        messageBox.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        messageBox.getStyleClass().add("viewer-message-box");
        openExternal.setOnAction(_ -> openCurrentExternally());
        openExternal.setFocusTraversable(false);

        getChildren().addAll(imageLayer, video, messageBox, topBar());
        clearStage();
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** Called with the item that was showing when the viewer closes, so the Timeline can scroll to it. */
    public void setOnClose(Consumer<TimelineItem> onClose) {
        this.onClose = onClose;
    }

    /** Runs favorite / hide / Trash / restore / delete for the shown item. */
    public void setOnAction(ItemAction.Handler onAction) {
        this.onAction = onAction;
    }

    /** Opens on {@code items.get(index)}. {@code items} is kept as is: a Timeline reload doesn't change it. */
    public void open(List<TimelineItem> items, int index) {
        if (items.isEmpty() || index < 0 || index >= items.size()) {
            return;
        }
        this.items = List.copyOf(items);
        setVisible(true);
        requestFocus();
        show(index);
    }

    public boolean isOpen() {
        return isVisible();
    }

    /** Back to the Timeline: stops everything and frees the images. */
    public void close() {
        if (!isOpen()) {
            return;
        }
        TimelineItem last = current();
        slideshow.stop();
        clearStage();
        fullImages.keepOnly(List.of());
        if (stage() instanceof Stage stage && stage.isFullScreen()) {
            stage.setFullScreen(false);
        }
        setVisible(false);
        items = List.of();
        index = -1;
        if (last != null) {
            onClose.accept(last);
        }
    }

    /** App shutdown: stops any video and the decode threads. */
    public void dispose() {
        slideshow.stop();
        clearStage();
        fullImages.close();
    }

    // --- Showing one item ---

    private void show(int newIndex) {
        clearStage();
        index = newIndex;
        TimelineItem item = items.get(index);
        MediaRef ref = item.ref();
        ViewMode mode = ViewMode.of(ref.extension());
        if (slideshow.isRunning() && !mode.inSlideshow()) {
            slideshow.stop(); // stepped onto a video by hand: don't move on from under it
        }
        fileName.setText(ref.path().getFileName().toString());
        counter.setText("%,d / %,d".formatted(index + 1, items.size()));
        updateActionButtons();
        sizeKnown = item.width() > 0 && item.height() > 0;
        transform.setImage(sizeKnown ? item.width() : 1, sizeKnown ? item.height() : 1);
        applyTransform();

        switch (mode) {
            case STILL -> {
                showThumbnail(ref);
                fullImages.get(ref).whenComplete((loaded, error) -> Platform.runLater(() -> fullImageDone(ref, loaded, error)));
            }
            case ANIMATED -> {
                showThumbnail(ref);
                loadAnimated(ref);
            }
            case VIDEO -> {
                video.setVisible(true);
                video.play(ref.path(), reason -> {
                    if (isCurrent(ref)) {
                        video.setVisible(false);
                        showMessage("This video can't be played in Prismo: " + reason + ".", true);
                    }
                });
            }
            case EXTERNAL -> {
                showThumbnail(ref);
                String kind = ref.extension().toUpperCase(Locale.ROOT);
                checkExists(ref, () -> showMessage(kind + " files can't be shown in Prismo yet.", true));
            }
        }
        preloadNeighbours();
    }

    /** Empties the stage: no image, no video, no message. Rotation and zoom are reset by the next show(). */
    private void clearStage() {
        if (thumbnailLoad != null) {
            thumbnailLoad.cancel();
            thumbnailLoad = null;
        }
        if (animated != null) {
            animated.cancel(); // stops a background GIF load that's still running
            animated = null;
        }
        video.stop();
        video.setVisible(false);
        imageView.setImage(null);
        messageBox.setVisible(false);
        fullShown = false;
        zoom.setText("");
    }

    private void showThumbnail(MediaRef ref) {
        Image cached = thumbnails.cached(ref);
        if (cached != null) {
            showImage(cached, false);
            return;
        }
        thumbnailLoad = thumbnails.load(ref, image -> {
            thumbnailLoad = null;
            if (isCurrent(ref) && !fullShown) {
                showImage(image, false);
            }
        }, _ -> thumbnailLoad = null); // no thumbnail (video, HEIC…): the full image or a message follows
    }

    private void fullImageDone(MediaRef ref, FullImages.Loaded loaded, Throwable error) {
        if (!isCurrent(ref)) {
            return; // stale: the viewer moved on (the result stays cached if it's a neighbour)
        }
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            log.info("Can't show {}: {}", ref.path(), cause.toString());
            showMessage(cause instanceof NoSuchFileException
                    ? "File not found:\n" + ref.path()
                    : "Can't open this file (" + cause.getMessage() + ").", false);
            return;
        }
        useRealSize(loaded.fullWidth(), loaded.fullHeight());
        showImage(loaded.image(), true);
    }

    private void loadAnimated(MediaRef ref) {
        Image gif = new Image(ref.path().toUri().toString(), true); // decodes in the background, then animates
        animated = gif;
        gif.progressProperty().addListener((_, _, progress) -> {
            if (progress.doubleValue() < 1 || animated != gif || !isCurrent(ref)) {
                return;
            }
            if (gif.isError()) {
                checkExists(ref, () -> showMessage("Can't open this file.", false));
            } else {
                useRealSize((int) gif.getWidth(), (int) gif.getHeight());
                showImage(gif, true);
            }
        });
    }

    private void showImage(Image image, boolean full) {
        if (!sizeKnown) {
            // No indexed size: take the shape from what arrived (the thumbnail has the right proportions).
            whenLoaded(image, () -> useRealSize((int) image.getWidth(), (int) image.getHeight()));
        }
        imageView.setImage(image);
        fullShown |= full;
        applyTransform();
    }

    /** Sets the image's real size if it differs from what's assumed (resets zoom only in that case). */
    private void useRealSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        boolean differs = transform.imageWidth() != width || transform.imageHeight() != height;
        sizeKnown = true;
        if (differs) {
            transform.setImage(width, height);
            applyTransform();
        }
    }

    private static void whenLoaded(Image image, Runnable action) {
        if (image.getProgress() >= 1) {
            action.run();
        } else {
            image.progressProperty().addListener((_, _, p) -> {
                if (p.doubleValue() >= 1 && !image.isError()) {
                    action.run();
                }
            });
        }
    }

    private void showMessage(String text, boolean offerExternal) {
        message.setText(text);
        openExternal.setVisible(offerExternal);
        openExternal.setManaged(offerExternal);
        messageBox.setVisible(true);
    }

    /** For files the viewer doesn't draw: a missing file gets "File not found" instead of the open button. */
    private void checkExists(MediaRef ref, Runnable whenPresent) {
        Thread.ofVirtual().start(() -> { // a file check is I/O: not on the UI thread
            boolean exists = Files.exists(ref.path());
            Platform.runLater(() -> {
                if (isCurrent(ref)) {
                    if (exists) {
                        whenPresent.run();
                    } else {
                        showMessage("File not found:\n" + ref.path(), false);
                    }
                }
            });
        });
    }

    private void preloadNeighbours() {
        List<MediaRef> keep = new ArrayList<>(3);
        for (int i = Math.max(0, index - 1); i <= Math.min(items.size() - 1, index + 1); i++) {
            MediaRef ref = items.get(i).ref();
            if (ViewMode.of(ref.extension()) == ViewMode.STILL) {
                keep.add(ref);
            }
        }
        fullImages.keepOnly(keep);
        // Current first (already asked for), then next, then previous: the usual direction of travel.
        if (index + 1 < items.size() && keep.contains(items.get(index + 1).ref())) {
            fullImages.get(items.get(index + 1).ref());
        }
        if (index > 0 && keep.contains(items.get(index - 1).ref())) {
            fullImages.get(items.get(index - 1).ref());
        }
    }

    private void openCurrentExternally() {
        TimelineItem item = current();
        if (item != null) {
            log.info("Opening {} in the default app", item.ref().path());
            openExternally.accept(item.ref().path().toUri().toString());
        }
    }

    private TimelineItem current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    private boolean isCurrent(MediaRef ref) {
        TimelineItem item = current();
        return isOpen() && item != null && item.ref().equals(ref);
    }

    // --- Item actions ---

    private void updateActionButtons() {
        TimelineItem item = current();
        actionButtons.getChildren().clear();
        if (item == null) {
            return;
        }
        for (ItemAction action : ItemAction.available(item)) {
            Button button = button(action.buttonText(), action.tooltip(), () -> act(action));
            button.getStyleClass().add("viewer-action");
            if (action == ItemAction.UNFAVORITE) {
                button.getStyleClass().add("viewer-favorite-on");
            }
            actionButtons.getChildren().add(button);
        }
    }

    /**
     * Does {@code action} to the shown item. Favoriting updates it in place; anything else takes it out of this
     * list (it now belongs to another page): the next item shows, or the previous at the end, or the viewer closes.
     */
    private void act(ItemAction action) {
        TimelineItem item = current();
        if (item == null || !ItemAction.available(item).contains(action) || !onAction.perform(action, item)) {
            return;
        }
        List<TimelineItem> changed = new ArrayList<>(items);
        if (action.keepsItemInView()) {
            changed.set(index, action.applyTo(item));
            items = List.copyOf(changed);
            updateActionButtons();
            return;
        }
        changed.remove(index);
        items = List.copyOf(changed);
        if (items.isEmpty()) {
            slideshow.stop();
            clearStage();
            fullImages.keepOnly(List.of());
            if (stage() instanceof Stage stage && stage.isFullScreen()) {
                stage.setFullScreen(false);
            }
            setVisible(false);
            index = -1;
            onClose.accept(item);
            return;
        }
        show(Math.min(index, items.size() - 1));
        slideshow.restart();
    }

    // --- Navigation, slideshow, full screen ---

    private void step(int delta) {
        int next = index + delta;
        if (next >= 0 && next < items.size()) {
            show(next);
            slideshow.restart();
        }
    }

    private void toggleSlideshow() {
        if (slideshow.isRunning()) {
            slideshow.stop();
            return;
        }
        if (current() != null && ViewMode.of(current().ref().extension()).inSlideshow()) {
            slideshow.start();
        } else {
            int next = Slideshow.next(items, index);
            if (next >= 0) {
                show(next);
                slideshow.start();
            }
        }
    }

    private void slideshowTick() {
        int next = Slideshow.next(items, index);
        if (next < 0) {
            slideshow.stop(); // the end
            return;
        }
        show(next);
        slideshow.restart();
    }

    private void toggleFullScreen() {
        if (stage() instanceof Stage stage) {
            stage.setFullScreen(!stage.isFullScreen());
        }
    }

    private void escape() {
        if (stage() instanceof Stage stage && stage.isFullScreen()) {
            stage.setFullScreen(false);
        } else {
            close();
        }
    }

    private Window stage() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void rotate() {
        if (imageView.getImage() != null && !video.isVisible()) {
            transform.rotateClockwise();
            applyTransform();
        }
    }

    // --- Zoom / pan ---

    private void viewResized() {
        Window window = stage();
        transform.setView(imageLayer.getWidth(), imageLayer.getHeight(), window == null ? 1 : window.getOutputScaleX());
        applyTransform();
    }

    /** Sizes, places and turns the ImageView from the transform. */
    private void applyTransform() {
        double w = transform.displayWidth();
        double h = transform.displayHeight();
        imageView.setFitWidth(w);
        imageView.setFitHeight(h);
        imageView.relocate(transform.centreX() - w / 2, transform.centreY() - h / 2);
        imageView.setRotate(transform.rotationDegrees());
        imageLayer.setCursor(transform.canPan() ? Cursor.OPEN_HAND : Cursor.DEFAULT);
        zoom.setText(imageView.getImage() == null ? "" : "%.0f%%".formatted(transform.zoomPercent()));
    }

    private void setUpMouse() {
        imageLayer.addEventHandler(ScrollEvent.SCROLL, event -> {
            if (imageView.getImage() != null && event.getDeltaY() != 0) {
                transform.zoomAt(Math.pow(WHEEL_ZOOM_PER_NOTCH, event.getDeltaY() / WHEEL_UNITS_PER_NOTCH),
                        event.getX(), event.getY());
                applyTransform();
            }
            event.consume();
        });
        imageLayer.addEventHandler(ZoomEvent.ZOOM, event -> {
            if (imageView.getImage() != null) {
                transform.zoomAt(event.getZoomFactor(), event.getX(), event.getY());
                applyTransform();
            }
            event.consume();
        });
        imageLayer.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            dragX = event.getX();
            dragY = event.getY();
            if (transform.canPan()) {
                imageLayer.setCursor(Cursor.CLOSED_HAND);
            }
        });
        imageLayer.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            transform.panBy(event.getX() - dragX, event.getY() - dragY);
            dragX = event.getX();
            dragY = event.getY();
            applyTransform();
        });
        imageLayer.addEventHandler(MouseEvent.MOUSE_RELEASED, _ -> applyTransform()); // cursor back to open hand
        imageLayer.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && event.isStillSincePress()
                    && imageView.getImage() != null) {
                transform.toggleFitActual(event.getX(), event.getY());
                applyTransform();
            }
        });
    }

    // --- Keys and the top bar ---

    private void onKey(KeyEvent event) {
        boolean handled = true;
        switch (event.getCode()) {
            case LEFT -> step(-1);
            case RIGHT -> step(+1);
            case ESCAPE -> escape();
            case F11 -> toggleFullScreen();
            case R -> rotate();
            case SPACE -> {
                // By type, not by what's on screen: a video that failed to play must not start the slideshow.
                if (current() != null && ViewMode.of(current().ref().extension()) == ViewMode.VIDEO) {
                    video.togglePlay();
                } else {
                    toggleSlideshow();
                }
            }
            case PLUS, ADD, EQUALS -> zoomBy(KEY_ZOOM_STEP);
            case MINUS, SUBTRACT -> zoomBy(1 / KEY_ZOOM_STEP);
            case DIGIT0, NUMPAD0 -> {
                transform.fit();
                applyTransform();
            }
            case F, H, DELETE -> {
                ItemAction action = current() == null || event.isShortcutDown() || event.isAltDown()
                        ? null : ItemAction.forKey(event.getCode(), current());
                if (action != null) {
                    act(action);
                }
            }
            default -> handled = false;
        }
        if (handled) {
            event.consume();
        }
    }

    private void zoomBy(double factor) {
        if (imageView.getImage() != null) {
            transform.zoomAtCentre(factor);
            applyTransform();
        }
    }

    private HBox topBar() {
        Button back = button("← Back", "Back to the Timeline (Esc)", this::close);
        Button previous = button("‹", "Previous (Left)", () -> step(-1));
        Button next = button("›", "Next (Right)", () -> step(+1));
        Button rotate = button("⟳", "Rotate the view (R); the file isn't changed", this::rotate);
        slideshowButton.textProperty().bind(slideshow.runningProperty().map(on -> on ? "Stop slideshow" : "Slideshow"));
        configure(slideshowButton, "Slideshow, 3 s per photo (Space)", this::toggleSlideshow);
        Button fullScreen = button("⛶", "Full screen (F11)", this::toggleFullScreen);
        fileName.getStyleClass().add("viewer-file-name");
        counter.getStyleClass().add("viewer-counter");
        zoom.getStyleClass().add("viewer-counter");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actionButtons.setAlignment(Pos.CENTER_LEFT);
        HBox bar = new HBox(10, back, fileName, counter, spacer, zoom, actionButtons, previous, next, rotate,
                slideshowButton, fullScreen);
        bar.getStyleClass().add("viewer-top-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxHeight(USE_PREF_SIZE);
        StackPane.setAlignment(bar, Pos.TOP_CENTER);
        return bar;
    }

    private static Button button(String text, String tooltip, Runnable action) {
        Button button = new Button(text);
        configure(button, tooltip, action);
        return button;
    }

    private static void configure(Button button, String tooltip, Runnable action) {
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(_ -> action.run());
        button.setFocusTraversable(false); // keys always reach the viewer, never a focused button
    }

    // --- For tests and tools ---

    ViewTransform transform() {
        return transform;
    }

    ImageView imageView() {
        return imageView;
    }

    VideoPane video() {
        return video;
    }

    Label message() {
        return messageBox.isVisible() ? message : null;
    }

    Slideshow slideshow() {
        return slideshow;
    }

    int index() {
        return index;
    }

    /** For tests and tools: the action buttons for the shown item. */
    HBox actionButtons() {
        return actionButtons;
    }

    /** For tests: the path of the item showing. */
    Path currentPath() {
        TimelineItem item = current();
        return item == null ? null : item.ref().path();
    }
}
