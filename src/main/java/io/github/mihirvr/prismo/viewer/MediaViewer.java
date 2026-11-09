package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.platform.OsHiddenMark;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.FileItems;
import io.github.mihirvr.prismo.ui.DragOut;
import io.github.mihirvr.prismo.ui.Filmstrip;
import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.ItemAction;
import io.github.mihirvr.prismo.ui.Menus;
import io.github.mihirvr.prismo.ui.OfflineFolders;
import io.github.mihirvr.prismo.ui.SubtitleBackground;
import io.github.mihirvr.prismo.ui.TagKind;
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * <p><b>Keys</b> (1.1.0: YouTube's first, VLC's where YouTube has none; the list in Settings is
 * {@code settings.Shortcuts}): Left / Right previous / next (in a video: back / forward 5 s), Page Up / Page Down
 * and Shift+P / Shift+N previous / next (photos and videos alike), Esc back (closing the Info panel first, then
 * leaving full screen), F or F11 full screen, R rotate (view only), + / − / 0 zoom in / out / fit, Space play /
 * pause a video or start / stop the slideshow, Ctrl+D favorite, Ctrl+H hide, Delete to Trash (in Trash: delete
 * permanently), Ctrl+C copy the file, I the Info panel ({@link InfoPanel}, on the right).
 * An action that takes the item out of the list it was opened on (hide, Trash, restore, delete) moves on to the
 * next one; favoriting keeps it. A video adds K play / pause, J / L back / forward 10 s, Ctrl+Left / Right a
 * minute, 0 to 9 a tenth of the way in, Home / End, Up / Down volume, M mute, &lt; / &gt; speed, [ / ] a little
 * slower / faster, = normal speed, Shift+L loop, C subtitles on / off, V next subtitles, G / H subtitles earlier /
 * later, B sound track, comma / period one picture back / on, T the time ({@link VideoPane}).
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
    /** "People…" (1.2.0) and "Categories…" (1.3.0) in the right-click menu; a kind left out = not offered. */
    private final Map<TagKind, Consumer<List<TimelineItem>>> onTags = new EnumMap<>(TagKind.class);
    /** Full screen: hides the controls and pointer after {@link #IDLE_TIME} without a mouse move. */
    private final PauseTransition idle = new PauseTransition(IDLE_TIME);
    private boolean chromeHidden;
    /** The filmstrip (1.1.0): its own row in a window, floating over the photo in full screen. */
    private final Filmstrip filmstrip;
    private final StackPane stripRow = new StackPane();
    /**
     * Full screen: one dark band from the bottom edge up past the floating strip, behind the strip and a video's
     * control bar. (The strip used to carry its own band: over a video it ended in a hard line on top of the control
     * bar's own shading.) Fades with the strip; lets every click through.
     */
    private final Region stripBand = new Region();
    /** How far the band fades out above the strip. */
    private static final double BAND_FADE = 64;
    /** The filmstrip hides after {@link #IDLE_TIME} without a mouse move, in a window and in full screen alike. */
    private final PauseTransition stripIdle = new PauseTransition(IDLE_TIME);
    private boolean stripHidden;
    /** True while a slideshow that started in a window runs in full screen (1.3.0): it goes back when it stops. */
    private boolean slideshowMadeFullScreen;
    /** False in the standalone viewer: no Favorite / Hide / Trash, as there is no library. */
    private boolean itemActionsShown = true;

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
        // The filmstrip has its own row under the photo, so the photo never moves when the strip fades in or out;
        // in full screen it floats over the bottom of the photo instead (placeFilmstrip).
        filmstrip = new Filmstrip(thumbnails);
        filmstrip.setOnPick(at -> {
            show(at);
            slideshow.restart();
        });
        stripRow.getStyleClass().add("filmstrip-row");
        stripRow.setMinHeight(Filmstrip.HEIGHT);
        stripRow.getChildren().add(filmstrip);
        stripBand.getStyleClass().add("filmstrip-band");
        stripBand.setMouseTransparent(true);
        stripBand.setMaxHeight(USE_PREF_SIZE);
        stripBand.opacityProperty().bind(filmstrip.opacityProperty());
        StackPane.setAlignment(stripBand, Pos.BOTTOM_CENTER);
        // The control bar gets its real height only once laid out: the floating strip and its band follow it.
        video.controls().heightProperty().addListener((_, _, _) -> {
            TimelineItem shown = current();
            floatFilmstripAbove(shown != null && ViewMode.of(shown.ref().extension()) == ViewMode.VIDEO);
        });
        VBox column = new VBox(stageArea, stripRow);
        column.setMinSize(0, 0);
        VBox.setVgrow(stageArea, Priority.ALWAYS);
        HBox.setHgrow(column, Priority.ALWAYS);
        HBox layout = new HBox(column, info);
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
        showStrip();
        stripIdle.playFromStart(); // the strip shows on opening, then fades once the mouse rests
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

    /**
     * Puts a longer list around the item on screen without showing it again (1.1.0, the standalone viewer: the file
     * opens at once, its folder is listed after). Nothing happens if the item on screen isn't {@code newItems.get(at)}.
     */
    public void extendList(List<TimelineItem> newItems, int at) {
        TimelineItem shown = current();
        if (!isOpen() || shown == null || at < 0 || at >= newItems.size()
                || newItems.get(at).ref().id() != shown.ref().id()) {
            return;
        }
        items = List.copyOf(newItems);
        index = at;
        counter.setText("%,d / %,d".formatted(index + 1, items.size()));
        previous.setVisible(index > 0);
        next.setVisible(index < items.size() - 1);
        video.setNeighbours(index > 0, index < items.size() - 1);
        filmstrip.show(items, index);
    }

    // --- Showing one item ---

    private void show(int newIndex) {
        clearStage();
        index = newIndex;
        TimelineItem item = items.get(index);
        MediaRef ref = item.ref();
        ViewMode mode = ViewMode.of(ref.extension());
        filmstrip.show(items, index);
        floatFilmstripAbove(mode == ViewMode.VIDEO);
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
            // No indexed size: take the shape from what arrived (the thumbnail has the right proportions). Only if
            // that image is still the one on screen when it has loaded: a full image that came first has the real
            // size, and a thumbnail finishing late must not shrink it back (1.1.0, the standalone viewer has no sizes).
            whenLoaded(image, () -> {
                if (imageView.getImage() == image) {
                    useRealSize((int) image.getWidth(), (int) image.getHeight());
                }
            });
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

    /**
     * Turns the library's item actions (Favorite, Hide, Trash…) off or on: the standalone viewer has no library, so
     * it shows only what works on a plain file (1.1.0).
     */
    public void setItemActionsShown(boolean shown) {
        itemActionsShown = shown;
        updateActionButtons();
    }

    /** The item actions for the menu, or none in the standalone viewer. */
    private List<ItemAction> menuActions(TimelineItem item) {
        return itemActionsShown ? ItemAction.forMenu(item, OsHiddenMark.SUPPORTED, osHiddenShown.getAsBoolean())
                : List.of();
    }

    private void updateActionButtons() {
        TimelineItem item = current();
        actionButtons.getChildren().clear();
        if (item == null || !itemActionsShown) {
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
        if (item == null || !itemActionsShown || !ItemAction.forMenu(item, OsHiddenMark.SUPPORTED, shown).contains(action)
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

    /** R: a quarter turn clockwise of the view only (the file never changes); videos too since 1.3.0. */
    private void rotate() {
        if (video.isVisible()) {
            video.rotateView();
            return;
        }
        if (imageView.getImage() != null) {
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
        // 1.3.0: dragging a photo that isn't zoomed drags its file out (as from the grid); zoomed in, dragging pans.
        imageLayer.addEventHandler(MouseEvent.DRAG_DETECTED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && imageView.getImage() != null && transform.isFitted()) {
                dragOut(imageLayer, imageView.getImage());
                event.consume();
            }
        });
        video.setOnDragOut(picture -> dragOut(video, picture));
        imageLayer.addEventHandler(MouseEvent.MOUSE_RELEASED, _ -> applyTransform()); // cursor back to open hand
        imageLayer.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && event.isStillSincePress()
                    && imageView.getImage() != null) {
                transform.toggleFitActual(event.getX(), event.getY());
                applyTransform();
            }
        });
    }

    /** Drags the shown item's file out of Prismo from {@code source} (1.3.0), {@code picture} small under the mouse. */
    private void dragOut(Node source, javafx.scene.image.Image picture) {
        TimelineItem item = current();
        if (item != null) {
            DragOut.start(source, List.of(item.ref().path()), picture);
        }
    }

    // --- Keys and the top bar ---

    private void onKey(KeyEvent event) {
        interruptSlideshow(event); // any key only stops a running slideshow
        if (event.isConsumed()) {
            return;
        }
        if (fileItems.handleKey(event, current())) {
            event.consume(); // Ctrl+C
            return;
        }
        // 1.1.0 key map: YouTube's keys first, VLC's where YouTube has none.
        KeyCode actionKey = ItemAction.keyOf(event); // Ctrl+D favorite, Ctrl+H hide, Delete
        if (actionKey != null) {
            ItemAction action = current() == null || !itemActionsShown ? null
                    : ItemAction.forKey(actionKey, current(), osHiddenShown.getAsBoolean());
            if (action != null) {
                act(action);
            }
            event.consume();
            return;
        }
        if (event.isShiftDown() && !event.isShortcutDown() && !event.isAltDown()
                && (event.getCode() == KeyCode.N || event.getCode() == KeyCode.P)) {
            step(event.getCode() == KeyCode.N ? +1 : -1); // Shift+N / Shift+P, as on YouTube
            event.consume();
            return;
        }
        if (isVideo() && !event.isAltDown() && videoKey(event)) {
            event.consume();
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
        boolean handled = true;
        boolean plain = !event.isShortcutDown() && !event.isAltDown() && !event.isShiftDown();
        switch (event.getCode()) {
            case ESCAPE -> escape();
            case F11 -> toggleFullScreen();
            case F -> {
                if (plain) {
                    toggleFullScreen(); // F, as in YouTube and VLC
                } else {
                    handled = false;
                }
            }
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

    /**
     * The keys that only mean something for a video (1.1.0 map: YouTube first, then VLC). True if the key was one.
     * Ctrl+Left / Right jump a minute; Shift+L loops; &lt; and &gt; (Shift+, and Shift+.) change the speed a step.
     */
    private boolean videoKey(KeyEvent event) {
        KeyCode code = event.getCode();
        if (event.isShortcutDown()) {
            switch (code) {
                case LEFT -> video.skip(-VideoPane.MINUTE_SKIP_MICROS);
                case RIGHT -> video.skip(VideoPane.MINUTE_SKIP_MICROS);
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (event.isShiftDown()) {
            switch (code) {
                case L -> video.toggleLoop();
                case COMMA -> video.changeSpeed(-1);
                case PERIOD -> video.changeSpeed(1);
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (code.isDigitKey()) {
            video.seekToTenth(Integer.parseInt(code.getChar())); // 0 to 9: that tenth of the video
            return true;
        }
        switch (code) {
            case K -> video.togglePlay();
            case J -> video.skip(-VideoPane.SKIP_MICROS);
            case L -> video.skip(VideoPane.SKIP_MICROS);
            case UP -> video.volumeUp();
            case DOWN -> video.volumeDown();
            case M -> video.toggleMute();
            case LESS -> video.changeSpeed(-1); // keyboards with their own < and > keys
            case GREATER -> video.changeSpeed(1);
            case OPEN_BRACKET -> video.fineSpeed(-1);
            case CLOSE_BRACKET -> video.fineSpeed(1);
            case EQUALS -> video.normalSpeed();
            case C -> video.toggleCaptions();
            case V -> video.nextSubtitles();
            case G -> video.shiftSubtitles(-VideoPane.SUBTITLE_DELAY_STEP_MICROS);
            case H -> video.shiftSubtitles(VideoPane.SUBTITLE_DELAY_STEP_MICROS);
            case B -> video.nextSoundTrack();
            case COMMA -> video.step(-1);
            case PERIOD -> video.step(1);
            case HOME -> video.restart();
            case END -> video.toEnd();
            case T -> video.announceTime();
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
        Button back = button(Icon.BACK, "Back (Esc)", this::close);
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
        fullScreen.getTooltip().textProperty().bind(inFullScreen.map(on -> on ? "Leave full screen (F or Esc)"
                : "Full screen (F)"));
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
        configure(more, "More", this::showMoreMenu);
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
        // 1.1.0: Playback, Audio, Subtitles (videos) and View are submenus that open on hover (as in VLC): many
        // entries each. 1.2.0: no File submenu any more; the file's own entries are on the menu itself (Microsoft's
        // guidelines: no frequent entries in a submenu). A photo has them on top; a video at the bottom, under its
        // playback menus, so they are still in sight.
        ViewMode mode = ViewMode.of(item.ref().extension());
        List<MenuItem> playback = new ArrayList<>();
        if (mode == ViewMode.VIDEO && video.player() != null) {
            playback.addAll(video.menuItems());
        }
        List<MenuItem> view = new ArrayList<>();
        if (mode != ViewMode.VIDEO && imageView.getImage() != null) {
            view.add(Menus.item("Zoom in", Icon.ZOOM_IN, new KeyCharacterCombination("+"),
                    () -> zoomBy(KEY_ZOOM_STEP)));
            view.add(Menus.item("Zoom out", Icon.ZOOM_OUT, new KeyCharacterCombination("-"),
                    () -> zoomBy(1 / KEY_ZOOM_STEP)));
            view.add(Menus.item("Fit to window", null, new KeyCharacterCombination("0"), () -> {
                transform.fit();
                applyTransform();
            }));
            view.add(Menus.item("Rotate", Icon.ROTATE, new KeyCodeCombination(KeyCode.R), this::rotate));
        }
        if (mode == ViewMode.VIDEO && video.player() != null) {
            view.add(Menus.item("Rotate", Icon.ROTATE, new KeyCodeCombination(KeyCode.R), this::rotate));
        }
        boolean full = stage() instanceof Stage stage && stage.isFullScreen();
        view.add(Menus.item(full ? "Leave full screen" : "Full screen",
                full ? Icon.EXIT_FULL_SCREEN : Icon.FULL_SCREEN, new KeyCodeCombination(KeyCode.F),
                this::toggleFullScreen));
        if (mode.inSlideshow() && imageView.getImage() != null) {
            view.add(Menus.item(slideshow.isRunning() ? "Stop the slideshow" : "Slideshow", Icon.SLIDESHOW,
                    new KeyCodeCombination(KeyCode.SPACE), this::toggleSlideshow));
        }
        playback.add(Menus.submenu("View", Icon.IMAGE, view));

        boolean shown = osHiddenShown.getAsBoolean();
        List<MenuItem> settings = new ArrayList<>();
        List<MenuItem> changes = new ArrayList<>(fileItems.changeItems(item));
        List<MenuItem> removing = new ArrayList<>();
        for (ItemAction action : menuActions(item)) {
            MenuItem entry = Menus.item(action.menuText(), action.icon(), action.shortcut(item, shown),
                    () -> act(action));
            switch (action) {
                case TRASH -> changes.add(entry);
                case DELETE_PERMANENTLY -> removing.add(entry);
                default -> settings.add(entry);
            }
            if (action == ItemAction.FAVORITE || action == ItemAction.UNFAVORITE) {
                onTags.forEach((kind, handler) -> settings.add(Menus.item(kind.menuText(), Icon.of(kind), null,
                        () -> handler.accept(List.of(item)))));
            }
        }
        if (!itemActionsShown) {
            changes.clear(); // the standalone viewer: no library, so no Rename / Move here either
        }
        List<MenuItem> info = List.of(Menus.item(infoOpen ? "Close the info" : "Info", Icon.INFO,
                new KeyCodeCombination(KeyCode.I), this::toggleInfo));
        boolean video = mode == ViewMode.VIDEO;
        MenuItem previousItem = Menus.item("Previous", Icon.PREVIOUS_MEDIA,
                new KeyCodeCombination(video ? KeyCode.PAGE_UP : KeyCode.LEFT), () -> step(-1));
        previousItem.setDisable(index <= 0);
        MenuItem nextItem = Menus.item("Next", Icon.NEXT_MEDIA,
                new KeyCodeCombination(video ? KeyCode.PAGE_DOWN : KeyCode.RIGHT), () -> step(+1));
        nextItem.setDisable(index >= items.size() - 1);
        List<MenuItem> stepping = List.of(previousItem, nextItem);
        List<MenuItem> opening = fileItems.openItems(item);
        List<MenuItem> copying = List.of(fileItems.copyItem(item));
        if (video) {
            return Menus.grouped(playback, settings, info, stepping, opening, copying, changes, removing);
        }
        return Menus.grouped(opening, copying, settings, changes, removing, info, playback, stepping);
    }

    /**
     * What "People…" (1.2.0) or "Categories…" (1.3.0) in the right-click menu does: opens that tag window for the item
     * on screen. Null (the default, and the standalone viewer) leaves the entry out.
     */
    public void setOnTags(TagKind kind, Consumer<List<TimelineItem>> handler) {
        if (handler == null) {
            onTags.remove(kind);
        } else {
            onTags.put(kind, handler);
        }
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
        stripIdle.setOnFinished(_ -> hideStripIfIdle());
        // While the slideshow runs the screen is clean: moving the mouse does nothing, and any click or key press
        // only stops the slideshow (the press is eaten, like an interrupt; keys: see onKey) and brings the controls back.
        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> interruptSlideshow(event));
        addEventFilter(MouseEvent.MOUSE_MOVED, _ -> mouseActive());
        addEventFilter(MouseEvent.MOUSE_DRAGGED, _ -> mouseActive());
        addEventFilter(MouseEvent.MOUSE_PRESSED, _ -> mouseActive());
        // 1.3.0: a slideshow runs in full screen; when it stops the window goes back to how it was.
        slideshow.runningProperty().subscribe(on -> {
            if (on) {
                hideChromeNow();
                hideStripNow();
                slideshowMadeFullScreen = !isFullScreen() && stage() instanceof Stage;
                if (slideshowMadeFullScreen) {
                    ((Stage) stage()).setFullScreen(true);
                }
            } else {
                showChrome();
                showStrip();
                stripIdle.playFromStart();
                restartIdle();
                if (slideshowMadeFullScreen && stage() instanceof Stage stage && stage.isFullScreen()) {
                    stage.setFullScreen(false);
                }
                slideshowMadeFullScreen = false;
            }
        });
        sceneProperty().flatMap(Scene::windowProperty)
                .flatMap(w -> w instanceof Stage s ? s.fullScreenProperty() : null).orElse(false)
                .subscribe(full -> {
                    placeFilmstrip(full);
                    if (slideshow.isRunning()) {
                        return;
                    }
                    if (full) {
                        idle.playFromStart();
                    } else {
                        idle.stop();
                        showChrome();
                        showStrip(); // in a window the strip is always there
                    }
                });
    }

    private void interruptSlideshow(javafx.event.Event event) {
        if (slideshow.isRunning()) {
            slideshow.stop();
            event.consume();
        }
    }

    private void mouseActive() {
        if (slideshow.isRunning()) {
            return;
        }
        if (chromeHidden) {
            showChrome();
        }
        restartIdle();
        showStrip();
        stripIdle.playFromStart();
    }

    /** In a window: the strip in its own row under the photo. In full screen: floating over the photo's bottom. */
    private void placeFilmstrip(boolean full) {
        stageArea.getChildren().removeAll(filmstrip, stripBand);
        stripRow.getChildren().remove(filmstrip);
        filmstrip.getStyleClass().remove("floating");
        stripRow.setVisible(!full);
        stripRow.setManaged(!full);
        if (full) {
            filmstrip.getStyleClass().add("floating");
            StackPane.setAlignment(filmstrip, Pos.BOTTOM_CENTER);
            stageArea.getChildren().addAll(stripBand, filmstrip);
            TimelineItem shown = current();
            floatFilmstripAbove(shown != null && ViewMode.of(shown.ref().extension()) == ViewMode.VIDEO);
        } else {
            StackPane.setMargin(filmstrip, null);
            stripRow.getChildren().add(filmstrip);
        }
    }

    /** In full screen, a video's strip floats just above its control bar; a photo's at the bottom edge. */
    private void floatFilmstripAbove(boolean videoControls) {
        if (filmstrip.getParent() == stageArea) {
            double bottom = videoControls ? video.controls().getHeight() + 4 : 0;
            StackPane.setMargin(filmstrip, new Insets(0, 0, bottom, 0));
            stripBand.setPrefHeight(bottom + Filmstrip.HEIGHT + BAND_FADE);
        }
    }

    private void showStrip() {
        if (!stripHidden) {
            return;
        }
        stripHidden = false;
        filmstrip.setMouseTransparent(false);
        FadeTransition fade = new FadeTransition(FADE_TIME.divide(2), filmstrip);
        fade.setToValue(1);
        fade.play();
    }

    /** The strip fades out once the mouse rests, unless the pointer is on it or a menu is open. */
    private void hideStripIfIdle() {
        if (!isOpen() || stripHidden || !isFullScreen()) {
            return; // in a window the strip stays
        }
        if (menuShowing() || filmstrip.isHover()) {
            stripIdle.playFromStart();
            return;
        }
        hideStripNow();
    }

    private void hideStripNow() {
        if (stripHidden) {
            return;
        }
        stripHidden = true;
        filmstrip.setMouseTransparent(true);
        FadeTransition fade = new FadeTransition(FADE_TIME, filmstrip);
        fade.setToValue(0);
        fade.play();
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
        hideChromeNow();
    }

    private void hideChromeNow() {
        if (chromeHidden) {
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

    /** The video time shows the time left (clicking the time switches it); saved by the app. */
    public BooleanProperty videoRemainingShownProperty() {
        return video.remainingShownProperty();
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
