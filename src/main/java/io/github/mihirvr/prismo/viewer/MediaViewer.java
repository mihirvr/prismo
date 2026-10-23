package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.platform.OsHiddenMark;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.FileItems;
import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.ItemAction;
import io.github.mihirvr.prismo.ui.Menus;
import io.github.mihirvr.prismo.ui.OfflineFolders;
import io.github.mihirvr.prismo.ui.SubtitleBackground;
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCharacterCombination;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
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
import javafx.util.Duration;
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
 * <p><b>Keys:</b> Left / Right previous / next (in a video: back / forward 5 s), Page Up / Page Down previous /
 * next (photos and videos alike), Esc back (closing the Info panel first, then leaving full screen), F11 full
 * screen, R rotate (view only), + / − / 0 zoom in / out / fit, Space play / pause a video or start / stop the
 * slideshow, F favorite, H hide, Delete to Trash (in Trash: delete permanently), Ctrl+C copy the file, I the Info
 * panel ({@link InfoPanel}, on the right).
 * An action that takes the item out of the list it was opened on (hide, Trash, restore, delete) moves on to the
 * next one; favoriting keeps it. A video adds J / L back / forward 10 s, Up / Down volume, M mute, [ / ] speed,
 * C subtitles, Z / X subtitles earlier / later, A sound track, comma / period one picture back / on, Home from
 * the start ({@link VideoPane}).
 * <b>Mouse:</b> wheel zooms around the cursor, drag pans, double-click toggles fit / 100% (a video: full screen),
 * trackpad pinch zooms. Right-click opens a menu with all of the above, each entry with its key; the top bar's
 * "More" button has the file items, the "i" button opens the Info panel.
 *
 * <p><b>Back / Forward</b> (P8, the mouse's side buttons and Alt+Left / Alt+Right) are handled by the app for the
 * whole window: Back closes the viewer ({@link #close}), and {@link #setOnLeave} tells the app where it was, so
 * Forward can open it again on the same item.
 *
 * <p><b>Full screen:</b> the top bar, the previous / next buttons, the video controls and the pointer fade out
 * after {@link #IDLE_TIME} without a mouse move, and come back on the next one. Not while a menu is open or the
 * pointer rests on one of them.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class MediaViewer extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(MediaViewer.class);
    /** Zoom per mouse-wheel notch (JavaFX reports about 40 units per notch on Windows: not measured here). */
    private static final double WHEEL_ZOOM_PER_NOTCH = 1.2;
    private static final double WHEEL_UNITS_PER_NOTCH = 40;
    private static final double KEY_ZOOM_STEP = 1.25;
    private static final double ICON_SIZE = 20;
    private static final double STEP_ICON_SIZE = 28;
    /** On a switch-like button while it's on (the slideshow button while it runs). */
    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");
    /** In full screen, the controls and pointer hide after this long without a mouse move. */
    static final Duration IDLE_TIME = Duration.seconds(2.5);
    private static final Duration FADE_TIME = Duration.millis(250);

    private final ThumbnailLoader thumbnails;
    private final FileItems fileItems;
    private final FullImages fullImages = new FullImages();
    private final ViewTransform transform = new ViewTransform();
    private final Slideshow slideshow = new Slideshow(this::slideshowTick);

    private final Pane imageLayer = new Pane();
    private final ImageView imageView = new ImageView();
    private final VideoPane video;
    private final Label message = new Label();
    private final Button openExternal = new Button("Open with default app");
    private final VBox messageBox = new VBox(12, message, openExternal);
    private final Label fileName = new Label();
    private final Label counter = new Label();
    private final Label zoom = new Label();
    private final Button slideshowButton = new Button();
    /**
     * Previous / next: round buttons at the left and right edges, over photos and videos alike, hidden at the ends
     * of the list. A video's control bar has a pair too ({@link VideoPane#setMediaSteps}).
     */
    private final Button previous = new Button();
    private final Button next = new Button();
    /** One button per action {@link ItemAction#available} for the shown item, rebuilt for each item. */
    private final HBox actionButtons = new HBox(6);
    private final Button more = new Button();
    private final Button infoButton;
    private final HBox topBar;
    /** Everything but the Info panel: the photo or video with its bars and buttons over it. */
    private final StackPane stageArea = new StackPane();
    private final InfoPanel info;
    private boolean infoOpen;
    /** The right-click menu or the More button's menu, while one is open. */
    private ContextMenu menu;
    /** Full screen: hides the controls and pointer after {@link #IDLE_TIME} without a mouse move. */
    private final PauseTransition idle = new PauseTransition(IDLE_TIME);
    private boolean chromeHidden;

    private List<TimelineItem> items = List.of();
    private int index = -1;
    private Consumer<TimelineItem> onClose = _ -> { };
    /** Told the list and the item shown just before the viewer closes (the app's Back / Forward history). */
    private BiConsumer<List<TimelineItem>, Integer> onLeave = (_, _) -> { };
    private ItemAction.Handler onAction = (_, _) -> false;
    /** The Settings switch "Show files hidden in File Explorer" (which actions an item gets). */
    private BooleanSupplier osHiddenShown = () -> false;
    /** Library folders that can't be reached right now (P7): their items say so instead of "File not found". */
    private Supplier<OfflineFolders> offline = () -> OfflineFolders.NONE;

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
     * @param thumbnails the Timeline's loader, so the photo just clicked is already in memory
     * @param memory     where videos were left (watch progress)
     * @param fileItems  Show in folder, Open with default app, Copy, and the details for the Info panel
     */
    public MediaViewer(ThumbnailLoader thumbnails, PlaybackMemory memory, FileItems fileItems) {
        this.thumbnails = thumbnails;
        this.fileItems = fileItems;
        video = new VideoPane(memory);
        video.setOnDoubleClick(this::toggleFullScreen);
        video.setMediaSteps(() -> step(-1), () -> step(+1));
        info = new InfoPanel(fileItems, this::closeInfo);
        info.setVisible(false);
        info.setManaged(false);
        video.setOnInfo(found -> {
            if (infoOpen) {
                info.setVideo(found);
            }
        });
        getStyleClass().add("viewer");
        setVisible(false);
        setMinSize(0, 0); // an overlay: always the window's size, never wider (see VideoPane's constructor)
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

        infoButton = button(Icon.INFO, "Info (I)", this::toggleInfo);
        topBar = buildTopBar();
        stageArea.getChildren().addAll(imageLayer, video, messageBox,
                stepButton(previous, Icon.CHEVRON_LEFT, -1, Pos.CENTER_LEFT),
                stepButton(next, Icon.CHEVRON_RIGHT, +1, Pos.CENTER_RIGHT), topBar);
        stageArea.setMinSize(0, 0);
        HBox.setHgrow(stageArea, Priority.ALWAYS);
        HBox layout = new HBox(stageArea, info);
        layout.setMinSize(0, 0);
        getChildren().add(layout);
        clearStage();
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            showMenu(menuItems(), this, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        setUpAutoHide();
    }

    /** Called with the item that was showing when the viewer closes, so the Timeline can scroll to it. */
    public void setOnClose(Consumer<TimelineItem> onClose) {
        this.onClose = onClose;
    }

    /**
     * Called with the list and the index of the item shown when the viewer is closed ({@link #close}: Esc, the Back
     * button, the mouse's back button), before anything is cleared. Not called when it closes because the last item
     * left the list (nothing to come back to).
     */
    public void setOnLeave(BiConsumer<List<TimelineItem>, Integer> onLeave) {
        this.onLeave = onLeave;
    }

    /** Runs favorite / hide / Trash / restore / delete for the shown item. */
    public void setOnAction(ItemAction.Handler onAction) {
        this.onAction = onAction;
    }

    /** The Settings switch "Show files hidden in File Explorer", read whenever the actions are worked out. */
    public void setOsHiddenShown(BooleanSupplier osHiddenShown) {
        this.osHiddenShown = osHiddenShown;
    }

    /** The library folders that can't be reached right now, read each time an item is shown. */
    public void setOffline(Supplier<OfflineFolders> offline) {
        this.offline = offline;
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

    /** Opens on {@code items.get(index)} with the Info panel open (the grids' Info item and I key). */
    public void openWithInfo(List<TimelineItem> items, int index) {
        open(items, index);
        if (isOpen()) {
            openInfo();
        }
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
        if (last != null) {
            onLeave.accept(items, index);
        }
        slideshow.stop();
        hideMenu();
        closeInfo();
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

    // --- Rename / Move (FileItems) ---

    /**
     * For {@link FileItems}: before the shown item's file is renamed or moved, the viewer lets go of it (a playing
     * video holds its file open; its place is saved); afterwards it follows the files to their new paths and shows
     * the item again (a video carries on where it was).
     */
    public FileItems.Listener fileListener() {
        return new FileItems.Listener() {
            private boolean released;

            @Override
            public void releasing(Set<Long> ids) {
                TimelineItem shown = current();
                released = isOpen() && shown != null && ids.contains(shown.ref().id());
                if (released) {
                    clearStage();
                }
            }

            @Override
            public void moved(Map<Long, Path> newPaths) {
                if (!isOpen()) {
                    return;
                }
                List<TimelineItem> updated = new ArrayList<>(items);
                for (int i = 0; i < updated.size(); i++) {
                    Path now = newPaths.get(updated.get(i).ref().id());
                    if (now != null) {
                        updated.set(i, updated.get(i).withPath(now));
                    }
                }
                items = List.copyOf(updated);
                if (released) {
                    released = false;
                    show(index);
                }
            }
        };
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
        previous.setVisible(index > 0);
        next.setVisible(index < items.size() - 1);
        video.setNeighbours(index > 0, index < items.size() - 1);
        // In a video Left / Right skip within it, so only Page Up / Page Down move on.
        boolean videoKeys = mode == ViewMode.VIDEO;
        previous.getTooltip().setText(videoKeys ? "Previous (Page Up)" : "Previous (Left or Page Up)");
        next.getTooltip().setText(videoKeys ? "Next (Page Down)" : "Next (Right or Page Down)");
        updateActionButtons();
        if (infoOpen) {
            info.show(item, null); // a video's part follows when the player has opened it
        }
        sizeKnown = item.width() > 0 && item.height() > 0;
        transform.setImage(sizeKnown ? item.width() : 1, sizeKnown ? item.height() : 1);
        applyTransform();

        Path offlineRoot = offline.get().rootOf(ref.path());
        if (offlineRoot != null) {
            showThumbnail(ref); // the cached thumbnail still shows, behind the message
            showMessage(OfflineFolders.viewerText(offlineRoot), false);
            return;
        }
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
                showThumbnail(ref); // a poster while the video opens; stays behind the message if it can't play
                video.setVisible(true);
                video.play(item, reason -> {
                    if (isCurrent(ref)) {
                        video.setVisible(false);
                        showMessage("This video can't be played in Prismo: " + reason + ".", true);
                    }
                }, () -> {
                    if (isCurrent(ref)) {
                        fullShown = true; // a thumbnail arriving late must not come back
                        imageView.setImage(null);
                        applyTransform();
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
            showMessage(cause instanceof NoSuchFileException ? notFoundText(ref)
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

    /** "File not found", or, if its library folder went offline meanwhile, that. */
    private String notFoundText(MediaRef ref) {
        Path root = offline.get().rootOf(ref.path());
        return root != null ? OfflineFolders.viewerText(root) : "File not found:\n" + ref.path();
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
                        showMessage(notFoundText(ref), false);
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
            fileItems.openWithDefaultApp(item);
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
        for (ItemAction action : ItemAction.available(item, osHiddenShown.getAsBoolean())) {
            Button button = button(action.icon(), action.tooltip(item, osHiddenShown.getAsBoolean()), () -> act(action));
            if (action.buttonText() != null) {
                button.setText(action.buttonText());
                button.setContentDisplay(ContentDisplay.LEFT);
            }
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
        boolean shown = osHiddenShown.getAsBoolean();
        if (item == null || !ItemAction.forMenu(item, OsHiddenMark.SUPPORTED, shown).contains(action)
                || !onAction.perform(action, item)) {
            return;
        }
        List<TimelineItem> changed = new ArrayList<>(items);
        if (action.keepsItemInView(item, shown)) {
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
        if (infoOpen) {
            closeInfo();
        } else if (stage() instanceof Stage stage && stage.isFullScreen()) {
            stage.setFullScreen(false);
        } else {
            close();
        }
    }

    // --- The Info panel ---

    private void toggleInfo() {
        if (infoOpen) {
            closeInfo();
        } else {
            openInfo();
        }
    }

    private void openInfo() {
        TimelineItem item = current();
        if (item == null || infoOpen) {
            return;
        }
        infoOpen = true;
        info.setVisible(true);
        info.setManaged(true);
        infoButton.pseudoClassStateChanged(ON, true); // lit while open, like the slideshow button
        infoButton.getTooltip().setText("Close the info (I)");
        info.show(item, video.info());
    }

    private void closeInfo() {
        if (!infoOpen) {
            return;
        }
        infoOpen = false;
        info.setVisible(false);
        info.setManaged(false);
        info.clear();
        infoButton.pseudoClassStateChanged(ON, false);
        infoButton.getTooltip().setText("Info (I)");
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
        imageLayer.setCursor(chromeHidden ? Cursor.NONE : transform.canPan() ? Cursor.OPEN_HAND : Cursor.DEFAULT);
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
        if (fileItems.handleKey(event, current())) {
            event.consume(); // Ctrl+C
            return;
        }
        Move move = event.isShortcutDown() || event.isAltDown() ? null : Move.forKey(event.getCode(), isVideo());
        if (move != null) {
            switch (move) {
                case PREVIOUS -> step(-1);
                case NEXT -> step(+1);
                case BACK -> video.skip(-VideoPane.ARROW_SKIP_MICROS);
                case FORWARD -> video.skip(VideoPane.ARROW_SKIP_MICROS);
            }
            event.consume();
            return;
        }
        if (isVideo() && !event.isShortcutDown() && !event.isAltDown() && videoKey(event.getCode())) {
            event.consume();
            return;
        }
        boolean handled = true;
        switch (event.getCode()) {
            case ESCAPE -> escape();
            case F11 -> toggleFullScreen();
            case R -> rotate();
            case SPACE -> {
                // By type, not by what's on screen: a video that failed to play must not start the slideshow.
                if (isVideo()) {
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
            case I -> {
                if (event.isShortcutDown() || event.isAltDown()) {
                    handled = false;
                } else {
                    toggleInfo();
                }
            }
            case F, H, DELETE -> {
                ItemAction action = current() == null || event.isShortcutDown() || event.isAltDown()
                        ? null : ItemAction.forKey(event.getCode(), current(), osHiddenShown.getAsBoolean());
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

    /** What the arrow and page keys do: move to another item, or skip within a video. */
    enum Move {
        PREVIOUS, NEXT, BACK, FORWARD;

        /**
         * Page Up / Page Down move to the previous / next item, always. Left / Right do too on a photo; in a video
         * they skip back / forward ({@link VideoPane#ARROW_SKIP_MICROS}), as in other players. Null for other keys.
         */
        static Move forKey(KeyCode code, boolean video) {
            return switch (code) {
                case PAGE_UP -> PREVIOUS;
                case PAGE_DOWN -> NEXT;
                case LEFT -> video ? BACK : PREVIOUS;
                case RIGHT -> video ? FORWARD : NEXT;
                default -> null;
            };
        }
    }

    /** The keys that only mean something for a video. True if {@code code} was one. */
    private boolean videoKey(KeyCode code) {
        switch (code) {
            case J -> video.skip(-VideoPane.SKIP_MICROS);
            case L -> video.skip(VideoPane.SKIP_MICROS);
            case UP -> video.volumeUp();
            case DOWN -> video.volumeDown();
            case M -> video.toggleMute();
            case OPEN_BRACKET -> video.changeSpeed(-1);
            case CLOSE_BRACKET -> video.changeSpeed(1);
            case C -> video.nextSubtitles();
            case Z -> video.shiftSubtitles(-VideoPane.SUBTITLE_DELAY_STEP_MICROS);
            case X -> video.shiftSubtitles(VideoPane.SUBTITLE_DELAY_STEP_MICROS);
            case A -> video.nextSoundTrack();
            case COMMA -> video.step(-1);
            case PERIOD -> video.step(1);
            case HOME -> video.restart();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** By the file's type, not by what's on screen: a video that failed to play is still a video. */
    private boolean isVideo() {
        return current() != null && ViewMode.of(current().ref().extension()) == ViewMode.VIDEO;
    }

    private void zoomBy(double factor) {
        if (imageView.getImage() != null) {
            transform.zoomAtCentre(factor);
            applyTransform();
        }
    }

    private HBox buildTopBar() {
        Button back = button(Icon.BACK, "Back (Esc, Alt+Left or the mouse's back button)", this::close);
        Button rotate = button(Icon.ROTATE, "Rotate the view (R); the file isn't changed", this::rotate);
        slideshowButton.setGraphic(Icon.SLIDESHOW.node(ICON_SIZE));
        slideshowButton.getStyleClass().add("icon-button");
        configure(slideshowButton, "", this::toggleSlideshow);
        slideshowButton.getTooltip().textProperty().bind(slideshow.runningProperty()
                .map(on -> on ? "Stop the slideshow (Space)" : "Slideshow, 3 s per photo (Space)"));
        // Lit while the slideshow runs, like a switch that is on.
        slideshow.runningProperty().subscribe(on -> slideshowButton.pseudoClassStateChanged(ON, on));
        Button fullScreen = button(Icon.FULL_SCREEN, "", this::toggleFullScreen);
        ObservableValue<Boolean> inFullScreen = sceneProperty().flatMap(Scene::windowProperty)
                .flatMap(w -> w instanceof Stage s ? s.fullScreenProperty() : null).orElse(false);
        fullScreen.graphicProperty().bind(inFullScreen.map(on -> (on ? Icon.EXIT_FULL_SCREEN : Icon.FULL_SCREEN)
                .node(ICON_SIZE)));
        fullScreen.getTooltip().textProperty().bind(inFullScreen.map(on -> on ? "Leave full screen (F11 or Esc)"
                : "Full screen (F11)"));
        fileName.getStyleClass().add("viewer-file-name");
        fileName.setMinWidth(0); // a long name shortens; the buttons keep their size
        counter.getStyleClass().add("viewer-counter");
        counter.setMinWidth(USE_PREF_SIZE);
        zoom.getStyleClass().add("viewer-counter");
        zoom.setMinWidth(USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actionButtons.setAlignment(Pos.CENTER_LEFT);
        more.setGraphic(Icon.MORE.node(ICON_SIZE));
        more.getStyleClass().add("icon-button");
        more.setMinWidth(USE_PREF_SIZE);
        configure(more, "More: show in folder, open with another app, copy", this::showMoreMenu);
        HBox bar = new HBox(8, back, fileName, counter, spacer, zoom, actionButtons, divider(), rotate,
                slideshowButton, fullScreen, infoButton, more);
        bar.getStyleClass().add("viewer-top-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxHeight(USE_PREF_SIZE);
        StackPane.setAlignment(bar, Pos.TOP_CENTER);
        return bar;
    }

    /** An icon button; its tooltip says what it does. */
    private static Button button(Icon icon, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon.node(ICON_SIZE));
        button.getStyleClass().add("icon-button");
        button.setMinWidth(USE_PREF_SIZE);
        configure(button, tooltip, action);
        return button;
    }

    /** A thin upright line between groups of buttons. */
    private static Separator divider() {
        Separator line = new Separator(Orientation.VERTICAL);
        line.getStyleClass().add("viewer-divider");
        line.setMaxHeight(22);
        return line;
    }

    /** {@code button} as a round previous / next button at one edge, over the photo. Its tooltip is set in show(). */
    private Button stepButton(Button button, Icon icon, int delta, Pos edge) {
        button.setGraphic(icon.node(STEP_ICON_SIZE));
        button.getStyleClass().add("viewer-step");
        configure(button, "", () -> step(delta));
        StackPane.setAlignment(button, edge);
        StackPane.setMargin(button, new Insets(0, 16, 0, 16));
        return button;
    }

    private static void configure(Button button, String tooltip, Runnable action) {
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(_ -> action.run());
        button.setFocusTraversable(false); // keys always reach the viewer, never a focused button
    }

    // --- Menus ---

    /**
     * The right-click menu: what fits the item (a video's playback items; a photo's slideshow, rotate and zoom),
     * full screen, the item actions, then the file items. Every entry shows its key.
     */
    private List<MenuItem> menuItems() {
        TimelineItem item = current();
        if (item == null) {
            return List.of();
        }
        List<MenuItem> entries = new ArrayList<>();
        ViewMode mode = ViewMode.of(item.ref().extension());
        if (mode == ViewMode.VIDEO && video.player() != null) {
            entries.addAll(video.menuItems());
            entries.add(new SeparatorMenuItem());
        } else if (mode.inSlideshow() && imageView.getImage() != null) {
            entries.add(Menus.item(slideshow.isRunning() ? "Stop the slideshow" : "Slideshow", Icon.SLIDESHOW,
                    new KeyCodeCombination(KeyCode.SPACE), this::toggleSlideshow));
            entries.add(Menus.item("Rotate", Icon.ROTATE, new KeyCodeCombination(KeyCode.R), this::rotate));
            entries.add(Menus.item("Zoom in", Icon.ZOOM_IN, new KeyCharacterCombination("+"),
                    () -> zoomBy(KEY_ZOOM_STEP)));
            entries.add(Menus.item("Zoom out", Icon.ZOOM_OUT, new KeyCharacterCombination("-"),
                    () -> zoomBy(1 / KEY_ZOOM_STEP)));
            entries.add(Menus.item("Fit to window", null, new KeyCharacterCombination("0"), () -> {
                transform.fit();
                applyTransform();
            }));
            entries.add(new SeparatorMenuItem());
        }
        boolean video = mode == ViewMode.VIDEO;
        MenuItem previousItem = Menus.item("Previous", Icon.PREVIOUS_MEDIA,
                new KeyCodeCombination(video ? KeyCode.PAGE_UP : KeyCode.LEFT), () -> step(-1));
        previousItem.setDisable(index <= 0);
        MenuItem nextItem = Menus.item("Next", Icon.NEXT_MEDIA,
                new KeyCodeCombination(video ? KeyCode.PAGE_DOWN : KeyCode.RIGHT), () -> step(+1));
        nextItem.setDisable(index >= items.size() - 1);
        entries.add(previousItem);
        entries.add(nextItem);
        boolean full = stage() instanceof Stage stage && stage.isFullScreen();
        entries.add(Menus.item(full ? "Leave full screen" : "Full screen",
                full ? Icon.EXIT_FULL_SCREEN : Icon.FULL_SCREEN, new KeyCodeCombination(KeyCode.F11),
                this::toggleFullScreen));
        entries.add(new SeparatorMenuItem());
        for (ItemAction action : ItemAction.forMenu(item, OsHiddenMark.SUPPORTED, osHiddenShown.getAsBoolean())) {
            entries.add(Menus.item(action.menuText(), action.icon(),
                    action.shortcut(item, osHiddenShown.getAsBoolean()), () -> act(action)));
        }
        entries.add(new SeparatorMenuItem());
        entries.addAll(fileItems.menuItems(item));
        entries.add(Menus.item(infoOpen ? "Close the info" : "Info", Icon.INFO, new KeyCodeCombination(KeyCode.I),
                this::toggleInfo));
        return entries;
    }

    /** The More button: the file items, and the File Explorer Hidden mark where there is one. */
    private void showMoreMenu() {
        TimelineItem item = current();
        if (item == null) {
            return;
        }
        List<MenuItem> entries = new ArrayList<>(fileItems.menuItems(item));
        if (OsHiddenMark.SUPPORTED && !item.trashed()) {
            ItemAction mark = item.osHidden() ? ItemAction.UNHIDE_IN_EXPLORER : ItemAction.HIDE_IN_EXPLORER;
            entries.add(new SeparatorMenuItem());
            entries.add(Menus.item(mark.menuText(), mark.icon(), mark.shortcut(item, osHiddenShown.getAsBoolean()),
                    () -> act(mark)));
        }
        hideMenu();
        menu = new ContextMenu(entries.toArray(MenuItem[]::new));
        menu.setOnHidden(_ -> restartIdle());
        menu.show(more, Side.BOTTOM, 0, 4);
    }

    private void showMenu(List<MenuItem> entries, Node anchor, double screenX, double screenY) {
        hideMenu();
        if (entries.isEmpty()) {
            return;
        }
        menu = new ContextMenu(entries.toArray(MenuItem[]::new));
        menu.setOnHidden(_ -> restartIdle());
        menu.show(anchor, screenX, screenY);
    }

    private void hideMenu() {
        if (menu != null) {
            menu.hide();
            menu = null;
        }
    }

    private boolean menuShowing() {
        return (menu != null && menu.isShowing()) || video.menuShowing();
    }

    // --- Full screen: the controls and the pointer hide while the mouse rests ---

    private void setUpAutoHide() {
        idle.setOnFinished(_ -> hideChromeIfIdle());
        addEventFilter(MouseEvent.MOUSE_MOVED, _ -> mouseActive());
        addEventFilter(MouseEvent.MOUSE_DRAGGED, _ -> mouseActive());
        addEventFilter(MouseEvent.MOUSE_PRESSED, _ -> mouseActive());
        sceneProperty().flatMap(Scene::windowProperty)
                .flatMap(w -> w instanceof Stage s ? s.fullScreenProperty() : null).orElse(false)
                .subscribe(full -> {
                    if (full) {
                        idle.playFromStart();
                    } else {
                        idle.stop();
                        showChrome();
                    }
                });
    }

    private void mouseActive() {
        if (chromeHidden) {
            showChrome();
        }
        restartIdle();
    }

    /** Counts the idle time again from now, in full screen only. */
    private void restartIdle() {
        if (isFullScreen()) {
            idle.playFromStart();
        }
    }

    private void hideChromeIfIdle() {
        if (!isFullScreen() || !isOpen() || chromeHidden) {
            return;
        }
        if (menuShowing() || pointerOnChrome()) {
            idle.playFromStart(); // look again later; never hide what is being used
            return;
        }
        chromeHidden = true;
        for (Node node : chrome()) {
            node.setMouseTransparent(true);
            FadeTransition fade = new FadeTransition(FADE_TIME, node);
            fade.setToValue(0);
            fade.play();
        }
        video.setControlsHidden(true);
        setCursor(Cursor.NONE);
        applyTransform();
    }

    private void showChrome() {
        if (!chromeHidden) {
            return;
        }
        chromeHidden = false;
        for (Node node : chrome()) {
            node.setMouseTransparent(false);
            FadeTransition fade = new FadeTransition(FADE_TIME.divide(2), node);
            fade.setToValue(1);
            fade.play();
        }
        video.setControlsHidden(false);
        setCursor(null);
        applyTransform();
    }

    /** What hides in full screen. */
    private List<Node> chrome() {
        return List.of(topBar, previous, next, video.controls());
    }

    private boolean pointerOnChrome() {
        for (Node node : chrome()) {
            if (node.isVisible() && node.isHover()) {
                return true;
            }
        }
        return infoOpen && info.isHover(); // reading the panel: the pointer stays
    }

    private boolean isFullScreen() {
        return stage() instanceof Stage stage && stage.isFullScreen();
    }

    // --- Video settings the app keeps across runs ---

    /** The video volume, 0..1 (the app saves it). */
    public DoubleProperty videoVolumeProperty() {
        return video.volumeProperty();
    }

    public BooleanProperty videoMutedProperty() {
        return video.mutedProperty();
    }

    /** Playback speed, 0.5..2. */
    public DoubleProperty videoSpeedProperty() {
        return video.speedProperty();
    }

    /** The box behind video subtitles (Settings › Video; the app saves it). */
    public ObjectProperty<SubtitleBackground> subtitleBackgroundProperty() {
        return video.subtitleBackgroundProperty();
    }

    /** Where the sound track and subtitles picked in each video are kept (P8). */
    public void setTrackMemory(TrackMemory trackMemory) {
        video.setTrackMemory(trackMemory);
    }

    // --- For tests and tools ---

    boolean chromeHidden() {
        return chromeHidden;
    }

    ContextMenu menu() {
        return menu;
    }

    /** For tools: the right-click menu's entries for the shown item, without showing it. */
    List<MenuItem> contextMenuItems() {
        return menuItems();
    }

    HBox topBar() {
        return topBar;
    }

    Button moreButton() {
        return more;
    }

    Button infoButton() {
        return infoButton;
    }

    Button previousButton() {
        return previous;
    }

    Button nextButton() {
        return next;
    }

    InfoPanel infoPanel() {
        return info;
    }

    boolean infoOpen() {
        return infoOpen;
    }

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
