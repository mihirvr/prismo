package io.github.mihirvr.prismo;

import io.github.mihirvr.prismo.collections.CollectionsPage;
import io.github.mihirvr.prismo.collections.FileRemover;
import io.github.mihirvr.prismo.collections.HiddenMode;
import io.github.mihirvr.prismo.collections.ItemActions;
import io.github.mihirvr.prismo.collections.LibraryFoldersDialog;
import io.github.mihirvr.prismo.collections.MediaFlags;
import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.db.LibraryRootStore;
import io.github.mihirvr.prismo.db.PlaybackPositions;
import io.github.mihirvr.prismo.db.SettingsStore;
import io.github.mihirvr.prismo.index.FileOperations;
import io.github.mihirvr.prismo.index.IgnoreRules;
import io.github.mihirvr.prismo.index.IndexProgress;
import io.github.mihirvr.prismo.index.Indexer;
import io.github.mihirvr.prismo.index.MetadataReader;
import io.github.mihirvr.prismo.platform.AppPaths;
import io.github.mihirvr.prismo.platform.NetworkDrives;
import io.github.mihirvr.prismo.platform.Reachability;
import io.github.mihirvr.prismo.search.SearchBar;
import io.github.mihirvr.prismo.search.Searcher;
import io.github.mihirvr.prismo.settings.AppInfo;
import io.github.mihirvr.prismo.settings.SettingsDialog;
import io.github.mihirvr.prismo.thumbs.MediaRefs;
import io.github.mihirvr.prismo.thumbs.ThumbnailCache;
import io.github.mihirvr.prismo.thumbs.ThumbnailFolder;
import io.github.mihirvr.prismo.thumbs.ThumbnailGenerator;
import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import io.github.mihirvr.prismo.ui.Appearance;
import io.github.mihirvr.prismo.ui.DialogTexts;
import io.github.mihirvr.prismo.ui.EmptyState;
import io.github.mihirvr.prismo.ui.FileItems;
import io.github.mihirvr.prismo.ui.Grouping;
import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.OfflineFolders;
import io.github.mihirvr.prismo.ui.Theme;
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineQuery;
import io.github.mihirvr.prismo.ui.TimelineView;
import io.github.mihirvr.prismo.ui.ViewOptions;
import io.github.mihirvr.prismo.ui.ViewOptionsBar;
import io.github.mihirvr.prismo.ui.WindowPlacement;
import io.github.mihirvr.prismo.viewer.MediaViewer;
import io.github.mihirvr.prismo.viewer.PlaybackMemory;
import io.github.mihirvr.prismo.watch.ChangeBatcher;
import io.github.mihirvr.prismo.watch.FileWatcher;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Dimension2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The JavaFX application: owns the main window and the app's long-lived services. */
public final class PrismoApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(PrismoApp.class);

    private static final double DEFAULT_WIDTH = 1200;
    private static final double DEFAULT_HEIGHT = 800;
    private static final int GRID_THUMBNAIL_SIZE = 320;
    private static final String THUMBNAIL_SIZE_SETTING = "timeline.thumbnail_size";
    private static final String THEME_SETTING = "appearance.theme";
    private static final String WINDOW_SETTING = "window.placement";
    private static final String HIDDEN_MODE_SETTING = "hidden.mode";
    private static final String OS_HIDDEN_SHOWN_SETTING = "hidden.show_os_hidden";
    private static final String IGNORED_SETTING = "index.ignored";
    private static final String GROUPING_SETTING = "timeline.grouping";
    private static final String THUMBNAIL_THREADS_SETTING = "thumbnails.threads";
    private static final String VOLUME_SETTING = "video.volume";
    private static final String MUTED_SETTING = "video.muted";
    private static final String SPEED_SETTING = "video.speed";
    /** The app icon in these sizes; the system picks the one closest to what it needs (title bar, taskbar). */
    private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    private static final double TOP_BAR_ICON_SIZE = 18;
    /** While indexing, show newly found files at most this often. */
    private static final long TIMELINE_RELOAD_NANOS = 3_000_000_000L;
    /** Watcher events are handled once a path has been quiet this long, but no later than MAX_WAIT. */
    private static final Duration CHANGES_QUIET = Duration.ofSeconds(1);
    private static final Duration CHANGES_MAX_WAIT = Duration.ofSeconds(5);
    /** The safety net: a full rescan this often catches anything the watcher missed. */
    private static final long RESCAN_MINUTES = 30;
    /** How often the app looks whether every library folder can still be reached (an unplugged drive, P7). */
    private static final long REACHABLE_CHECK_SECONDS = 30;
    /** A folder that hasn't answered "are you there?" within this counts as offline (a dead network drive). */
    private static final Duration REACHABLE_LIMIT = Duration.ofSeconds(5);

    private Database database;
    private SettingsStore settings;
    private LibraryRootStore libraryRoots;
    private Indexer indexer;
    private ThumbnailCache thumbnailCache;
    private ThumbnailService thumbnails;
    private Searcher searcher;
    private ChangeBatcher changes;
    private FileWatcher watcher;
    /** Library folders being watched. Indexing thread only. */
    private final Set<Path> watchedRoots = new HashSet<>();
    /** Library folders on another computer: never watched, rescanned every few minutes (P7). Any thread. */
    private final Set<Path> networkRoots = ConcurrentHashMap.newKeySet();
    /** The library folders found offline at the last check. The rescan timer's thread only. */
    private Set<Path> lastOffline = Set.of();
    /** {@link #lastOffline}, published for other threads. */
    private volatile Set<Path> offlineNow = Set.of();
    /** The same, for the UI: the banner, the greyed tiles, the viewer's message. UI thread. */
    private final ObjectProperty<OfflineFolders> offlineFolders = new SimpleObjectProperty<>(OfflineFolders.NONE);
    private final Label offlineText = new Label();
    private final HBox offlineBanner = new HBox(10);
    private final ScheduledExecutorService rescanTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("prismo-rescan-timer").daemon(true).factory());
    private double savedThumbnailSize = TimelineView.DEFAULT_THUMBNAIL_SIZE;
    private Theme savedTheme = Theme.DEFAULT;
    private Optional<WindowPlacement> savedPlacement = Optional.empty();
    /** Video volume (0..1), mute and speed as last used; kept across runs. */
    private double savedVolume = 1;
    private boolean savedMuted;
    private double savedSpeed = 1;
    /** Settings › Hidden items. UI thread. */
    private final ObjectProperty<HiddenMode> hiddenMode = new SimpleObjectProperty<>(HiddenMode.DEFAULT);
    /** Settings › Hidden items › "Show files hidden in File Explorer" (off by default). UI thread. */
    private final BooleanProperty osHiddenShown = new SimpleBooleanProperty(false);
    /** Settings › Library › Ignored folders. UI thread; the indexing thread reads {@link #ignoreRulesNow}. */
    private final ObjectProperty<IgnoreRules> ignoredFolders = new SimpleObjectProperty<>(IgnoreRules.NONE);
    private volatile IgnoreRules ignoreRulesNow = IgnoreRules.NONE;
    /** Settings › Timeline › date headers. UI thread. */
    private final ObjectProperty<Grouping> grouping = new SimpleObjectProperty<>(Grouping.DEFAULT);
    /** Settings › Performance › threads making thumbnails. UI thread. */
    private final IntegerProperty thumbnailThreads = new SimpleIntegerProperty(ThumbnailService.DEFAULT_THREADS);
    private Path cacheDir;
    private PlaybackPositions playbackPositions;
    /** The last watch-progress save, and whether one happened since the viewer opened. UI thread. */
    private CompletableFuture<Void> lastPositionSave = CompletableFuture.completedFuture(null);
    private boolean positionsSaved;
    /** The window's last normal (not maximised, not full screen) place, for saving at the end. UI thread only. */
    private WindowPlacement normalPlacement;
    private Appearance appearance;
    /** One indexing run at a time; extra requests queue up behind it. */
    private final ExecutorService indexingThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("prismo-indexer").daemon(true).factory());
    /**
     * Loads the Timeline, one load at a time, in the order asked; a load that a newer one has replaced while it
     * waited is skipped. Its own thread, so a filter or sort change never waits behind an index run.
     */
    private final ExecutorService timelineLoads = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("prismo-timeline").daemon(true).factory());
    private final AtomicLong latestTimelineLoad = new AtomicLong();
    /** The Filters + Sort choice. Set on the UI thread, read by the load thread. */
    private volatile ViewOptions viewOptions = ViewOptions.DEFAULT;
    /** UI thread only: the options of the items the Timeline shows now (null before the first load). */
    private ViewOptions shownOptions;

    private final Label status = new Label("Starting…");
    private final EmptyState timelinePlaceholder = new EmptyState();
    /** The "Library folders…" dialog; set in start(), also used by the empty Timeline's button. */
    private Runnable openLibraryFolders = () -> { };
    private ThumbnailLoader thumbnailLoader;
    private TimelineView timeline;
    private TimelineView searchResults;
    private SearchBar searchBar;
    private MediaViewer viewer;
    private CollectionsPage collections;
    private ViewOptionsBar viewBar;
    private final ToggleButton timelineTab = new ToggleButton("Timeline");
    private final ToggleButton collectionsTab = new ToggleButton("Collections");

    /** Runs on the JavaFX-Launcher thread, before any window exists, so slow setup here never freezes the UI. */
    @Override
    public void init() throws SQLException, IOException {
        log.info("Starting Prismo on Java {} / JavaFX {} ({})",
                System.getProperty("java.version"),
                System.getProperty("javafx.runtime.version"),
                System.getProperty("os.name"));

        AppPaths paths = AppPaths.forCurrentOs();
        database = Database.open(paths.databaseFile());
        settings = new SettingsStore(database);
        libraryRoots = new LibraryRootStore(database);
        indexer = new Indexer(database, new MetadataReader(ZoneId.systemDefault()), () -> ignoreRulesNow);
        cacheDir = paths.cacheDir();
        thumbnailCache = new ThumbnailCache(paths.cacheDir(), GRID_THUMBNAIL_SIZE);
        thumbnails = new ThumbnailService(thumbnailCache, new ThumbnailGenerator(GRID_THUMBNAIL_SIZE));
        searcher = new Searcher(database);
        playbackPositions = new PlaybackPositions(database);
        // Watcher events → batches (debounced) → applied on the indexing thread, after any run already queued.
        changes = new ChangeBatcher(CHANGES_QUIET, CHANGES_MAX_WAIT,
                batch -> indexingThread.execute(() -> applyChanges(batch)));
        watcher = FileWatcher.forThisComputer(changes);
        try {
            settings.get(THUMBNAIL_SIZE_SETTING).ifPresent(value -> savedThumbnailSize = Double.parseDouble(value));
        } catch (NumberFormatException e) {
            log.warn("Ignoring bad {} setting: {}", THUMBNAIL_SIZE_SETTING, e.getMessage());
        }
        savedTheme = Theme.fromSetting(settings.get(THEME_SETTING).orElse(null));
        savedPlacement = settings.get(WINDOW_SETTING).flatMap(WindowPlacement::parse);
        hiddenMode.set(HiddenMode.fromSetting(settings.get(HIDDEN_MODE_SETTING).orElse(null)));
        osHiddenShown.set(settings.get(OS_HIDDEN_SHOWN_SETTING).map(Boolean::parseBoolean).orElse(false));
        viewOptions = ViewOptions.DEFAULT.withOsHiddenShown(osHiddenShown.get());
        savedVolume = number(VOLUME_SETTING, 0, 1, 1);
        savedSpeed = number(SPEED_SETTING, 0.5, 2, 1);
        savedMuted = settings.get(MUTED_SETTING).map(Boolean::parseBoolean).orElse(false);
        ignoredFolders.set(IgnoreRules.fromSetting(settings.get(IGNORED_SETTING).orElse(null)));
        ignoreRulesNow = ignoredFolders.get();
        grouping.set(Grouping.fromSetting(settings.get(GROUPING_SETTING).orElse(null)));
        thumbnailThreads.set((int) number(THUMBNAIL_THREADS_SETTING, 1, ThumbnailService.MAX_THREADS,
                ThumbnailService.DEFAULT_THREADS));
        thumbnails.setThreads(thumbnailThreads.get());
    }

    /** The thumbnails on disk for Settings › Performance: size and clearing, on a virtual thread each. */
    private SettingsDialog.ThumbnailDisk thumbnailDisk() {
        return new SettingsDialog.ThumbnailDisk() {
            @Override
            public CompletableFuture<Long> size() {
                return onVirtualThread(() -> ThumbnailFolder.size(cacheDir));
            }

            @Override
            public CompletableFuture<Long> clear() {
                return onVirtualThread(() -> ThumbnailFolder.clear(cacheDir));
            }
        };
    }

    private static CompletableFuture<Long> onVirtualThread(Callable<Long> work) {
        CompletableFuture<Long> result = new CompletableFuture<>();
        Thread.ofVirtual().name("prismo-thumbnail-folder").start(() -> {
            try {
                result.complete(work.call());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** A number setting within min..max, or {@code fallback} if it's missing or not a number. */
    private double number(String key, double min, double max, double fallback) throws SQLException {
        try {
            return settings.get(key).map(Double::parseDouble).filter(v -> v >= min && v <= max).orElse(fallback);
        } catch (NumberFormatException e) {
            log.warn("Ignoring bad {} setting: {}", key, e.getMessage());
            return fallback;
        }
    }

    /**
     * Where videos were left, for the viewer: read from the database on the UI thread (one row by its key, well
     * under a millisecond), written in the background. Grids reload after the viewer closes, so the thumbnails'
     * progress bars show the new position.
     */
    private PlaybackMemory playbackMemory() {
        return new PlaybackMemory() {
            @Override
            public double load(long mediaId) {
                try {
                    return playbackPositions.load(mediaId);
                } catch (SQLException e) {
                    log.warn("Could not read where video {} was left", mediaId, e);
                    return 0;
                }
            }

            @Override
            public void save(long mediaId, double seconds, double durationSeconds) {
                lastPositionSave = playbackPositions.save(mediaId, seconds, durationSeconds);
                positionsSaved = true;
            }
        };
    }

    /** Runs on the JavaFX Application Thread. */
    @Override
    public void start(Stage stage) {
        thumbnailLoader = new ThumbnailLoader(thumbnails);
        timeline = new TimelineView(thumbnailLoader);
        timeline.setThumbnailSize(savedThumbnailSize);
        timeline.setPlaceholder(timelinePlaceholder);
        // HostServices opens a file in the system's default app (HEIC, RAW, "Open with default app").
        FileItems fileItems = new FileItems(database, getHostServices()::showDocument, status::setText);
        timeline.setFileItems(fileItems);
        viewer = new MediaViewer(thumbnailLoader, playbackMemory(), fileItems);
        // Rename / Move: a folder counts as in the library if it's inside a library folder that is on.
        fileItems.setOperations(new FileOperations(database, folder -> {
            try {
                return libraryRoots.enabledRoots().stream().anyMatch(folder::startsWith);
            } catch (SQLException e) {
                return true; // can't tell: keep the row rather than lose favorites and progress
            }
        }), () -> stage, this::libraryChanged);
        fileItems.setListener(viewer.fileListener());
        viewer.videoVolumeProperty().set(savedVolume);
        viewer.videoMutedProperty().set(savedMuted);
        viewer.videoSpeedProperty().set(savedSpeed);
        timeline.setOnOpen(viewer::open);
        timeline.setOnInfo(viewer::openWithInfo);

        // The Collections tab: its pages load on the Timeline's load thread, so loads never overlap.
        collections = new CollectionsPage(database, thumbnailLoader, timelineLoads);
        collections.grid().thumbnailSizeProperty().bindBidirectional(timeline.thumbnailSizeProperty());
        collections.grid().setFileItems(fileItems);
        collections.setOnOpen(viewer::open);
        collections.setOnInfo(viewer::openWithInfo);
        collections.setVisible(false);

        // Search results get their own grid, so the Timeline keeps its place while searching.
        searchResults = new TimelineView(thumbnailLoader);
        searchResults.thumbnailSizeProperty().bindBidirectional(timeline.thumbnailSizeProperty());
        searchResults.setFileItems(fileItems);
        searchResults.setOnOpen(viewer::open);
        searchResults.setOnInfo(viewer::openWithInfo);
        searchResults.setVisible(false);
        searchBar = new SearchBar(searcher, searchResults);
        searchBar.activeProperty().addListener((_, _, _) -> showCurrentView());

        // Favorite / hide / Trash / restore / delete, the same from every grid and the viewer.
        FileRemover remover = FileRemover.forThisComputer();
        ItemActions actions = new ItemActions(new MediaFlags(database), remover, thumbnailCache::fileFor,
                items -> confirmDelete(stage, remover, items), Platform::runLater, status::setText,
                this::libraryChanged, hiddenMode::get);
        timeline.setOnAction(actions);
        searchResults.setOnAction(actions);
        collections.setOnAction(actions);
        viewer.setOnAction(actions);
        // "Show files hidden in File Explorer": which actions an item gets, and what they say they did.
        actions.setOsHiddenShown(osHiddenShown::get);
        timeline.setOsHiddenShown(osHiddenShown::get);
        searchResults.setOsHiddenShown(osHiddenShown::get);
        collections.grid().setOsHiddenShown(osHiddenShown::get);
        viewer.setOsHiddenShown(osHiddenShown::get);
        viewer.setOffline(offlineFolders::get);
        offlineFolders.addListener((_, _, offline) -> {
            for (TimelineView grid : List.of(timeline, searchResults, collections.grid())) {
                grid.setOffline(offline);
            }
            offlineText.setText(offline.isEmpty() ? "" : offline.bannerText());
            offlineBanner.setVisible(!offline.isEmpty());
            offlineBanner.setManaged(!offline.isEmpty());
        });
        searchBar.setOptions(viewOptions);
        collections.setOptions(viewOptions);
        searchBar.setOnLeave(() -> currentGrid().requestFocus());
        viewer.setOnClose(item -> {
            TimelineView grid = currentGrid();
            grid.reveal(item.ref().id());
            grid.requestFocus();
            if (positionsSaved) {
                positionsSaved = false; // a video was watched: reload once the save is written, for its progress bar
                lastPositionSave.whenComplete((_, _) -> libraryChanged());
            }
        });

        ImageView appIcon = new ImageView(new Image(PrismoApp.class.getResource("/ui/icon/prismo-48.png").toExternalForm()));
        appIcon.setFitWidth(24);
        appIcon.setFitHeight(24);
        appIcon.setSmooth(true);
        Label title = new Label("Prismo", appIcon);
        title.setGraphicTextGap(8);
        title.getStyleClass().add("app-title");
        title.setMinWidth(Region.USE_PREF_SIZE); // in a narrow window the search box gets smaller, not the name
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToggleGroup tabs = new ToggleGroup();
        timelineTab.setToggleGroup(tabs);
        collectionsTab.setToggleGroup(tabs);
        timelineTab.setTooltip(new Tooltip("Everything, by date (Ctrl+1)"));
        collectionsTab.setTooltip(new Tooltip("Folders, Albums, Favorites, Recently Added, Hidden, Trash (Ctrl+2)"));
        // Mouse clicks don't move keyboard focus to a tab (Ctrl+1 / Ctrl+2 switch them), so no focus ring
        // stays on the tab that isn't open.
        timelineTab.setFocusTraversable(false);
        collectionsTab.setFocusTraversable(false);
        timelineTab.setSelected(true);
        tabs.selectedToggleProperty().addListener((_, old, selected) -> {
            if (selected == null) {
                old.setSelected(true); // clicking the open tab again keeps it open
            } else {
                showCurrentView();
                if (!searchBar.isActive()) {
                    // Keys go to the page just opened (the old one is hidden; JavaFX would pick the search box).
                    if (selected == timelineTab) {
                        timeline.requestFocus();
                    } else {
                        collections.focusPageList();
                    }
                }
            }
        });
        HBox tabBar = new HBox(timelineTab, collectionsTab);
        tabBar.getStyleClass().add("tabs");
        viewBar = new ViewOptionsBar();
        viewBar.optionsProperty().addListener((_, _, options) -> {
            viewOptions = options.withOsHiddenShown(osHiddenShown.get());
            searchBar.setOptions(viewOptions);
            collections.setOptions(viewOptions);
            loadTimeline();
        });
        // The File Explorer switch goes with the Filters + Sort, so every view reloads at once.
        osHiddenShown.addListener((_, _, on) -> {
            settings.put(OS_HIDDEN_SHOWN_SETTING, Boolean.toString(on));
            viewOptions = viewOptions.withOsHiddenShown(on);
            searchBar.setOptions(viewOptions);
            collections.setOptions(viewOptions);
            loadTimeline();
        });
        collections.sortLockedProperty().addListener((_, _, _) -> updateSortLock());
        Slider size = new Slider(TimelineView.MIN_THUMBNAIL_SIZE, TimelineView.MAX_THUMBNAIL_SIZE,
                timeline.thumbnailSizeProperty().get());
        size.valueProperty().bindBidirectional(timeline.thumbnailSizeProperty());
        size.setTooltip(new Tooltip("Thumbnail size (or Ctrl + mouse wheel)"));
        Button libraryFolders = new Button("Library folders…", Icon.FOLDER.node(TOP_BAR_ICON_SIZE));
        libraryFolders.setTooltip(new Tooltip("Add, turn off or remove the folders Prismo shows"));
        libraryFolders.setMinWidth(Region.USE_PREF_SIZE);
        openLibraryFolders = () -> new LibraryFoldersDialog(stage, libraryRoots, libraryOperations()).show();
        libraryFolders.setOnAction(_ -> openLibraryFolders.run());
        Button settingsButton = new Button(null, Icon.SETTINGS.node(TOP_BAR_ICON_SIZE));
        settingsButton.getStyleClass().add("icon-button");
        settingsButton.setTooltip(new Tooltip("Settings (Ctrl+,)"));
        hiddenMode.addListener((_, _, mode) -> settings.put(HIDDEN_MODE_SETTING, mode.settingValue()));
        // Ignored folders: saved, then a full index run drops what's now ignored and brings back what isn't.
        ignoredFolders.addListener((_, _, rules) -> {
            settings.put(IGNORED_SETTING, rules.toSetting());
            ignoreRulesNow = rules;
            startIndexing();
        });
        for (TimelineView grid : List.of(timeline, searchResults, collections.grid())) {
            grid.setGrouping(grouping.get());
        }
        grouping.addListener((_, _, chosen) -> {
            settings.put(GROUPING_SETTING, chosen.settingValue());
            for (TimelineView grid : List.of(timeline, searchResults, collections.grid())) {
                grid.setGrouping(chosen);
            }
        });
        thumbnailThreads.addListener((_, _, threads) -> {
            settings.put(THUMBNAIL_THREADS_SETTING, Integer.toString(threads.intValue()));
            thumbnails.setThreads(threads.intValue());
        });
        SettingsDialog.Options settingsOptions = new SettingsDialog.Options(hiddenMode, osHiddenShown, ignoredFolders,
                grouping, thumbnailThreads, thumbnailDisk(), AppInfo.load(),
                address -> getHostServices().showDocument(address.toString()));
        Runnable openSettings = () -> new SettingsDialog(stage, appearance,
                theme -> settings.put(THEME_SETTING, theme.settingValue()), settingsOptions).show();
        settingsButton.setOnAction(_ -> openSettings.run());
        HBox topBar = new HBox(title, tabBar, searchBar, spacer, libraryFolders, settingsButton);
        topBar.getStyleClass().add("top-bar");
        Region viewSpacer = new Region();
        HBox.setHgrow(viewSpacer, Priority.ALWAYS);
        viewBar.getChildren().addAll(viewSpacer, new Label("Size"), size);
        status.getStyleClass().add("status-bar");
        status.setMaxWidth(Double.MAX_VALUE);

        // Shown while a library folder can't be reached: which one, and that nothing is lost.
        offlineBanner.getStyleClass().add("offline-banner");
        offlineText.getStyleClass().add("offline-text");
        offlineText.setWrapText(true);
        offlineText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(offlineText, Priority.ALWAYS);
        Button offlineFoldersButton = new Button("Library folders\u2026");
        offlineFoldersButton.setMinWidth(Region.USE_PREF_SIZE);
        offlineFoldersButton.setOnAction(_ -> openLibraryFolders.run());
        Node offlineIcon = Icon.OFFLINE.node(TOP_BAR_ICON_SIZE);
        offlineBanner.getChildren().addAll(offlineIcon, offlineText, offlineFoldersButton);
        offlineBanner.setVisible(false);
        offlineBanner.setManaged(false);

        BorderPane main = new BorderPane(new StackPane(timeline, collections, searchResults),
                new VBox(topBar, viewBar, offlineBanner), null, status, null);
        // The viewer is an overlay over the whole window, hidden until a photo is opened.
        StackPane root = new StackPane(main, viewer);
        // A first start (or a saved place that no longer fits any screen) opens at the default size, made smaller
        // on a screen too small for it; a saved place then sets the real size in placeWindow.
        Dimension2D firstSize = WindowPlacement.firstSize(DEFAULT_WIDTH, DEFAULT_HEIGHT,
                Screen.getPrimary().getVisualBounds());
        Scene scene = new Scene(root, firstSize.getWidth(), firstSize.getHeight());
        appearance = new Appearance(scene, savedTheme);
        stage.setTitle("Prismo");
        for (int iconSize : ICON_SIZES) {
            stage.getIcons().add(new Image(PrismoApp.class.getResource("/ui/icon/prismo-" + iconSize + ".png").toExternalForm()));
        }
        stage.setMinWidth(WindowPlacement.MIN_WIDTH);
        stage.setMinHeight(WindowPlacement.MIN_HEIGHT);
        placeWindow(stage);
        stage.setScene(scene);
        // The viewer handles Esc itself (leave full screen first, then close), so JavaFX's own Esc is turned off.
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("Press F11 or Esc to leave full screen");
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN), () -> {
            if (!viewer.isOpen()) {
                searchBar.focusField();
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN), () -> {
            if (!viewer.isOpen()) {
                timelineTab.setSelected(true);
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN), () -> {
            if (!viewer.isOpen()) {
                collectionsTab.setSelected(true);
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN), () -> {
            if (!viewer.isOpen()) {
                openSettings.run();
            }
        });
        stage.show();
        timeline.requestFocus();
        log.info("Main window shown");

        // Show what's already indexed straight away, then pick up anything that changed since the last run.
        startIndexing();
        rescanTimer.scheduleWithFixedDelay(this::startIndexing, RESCAN_MINUTES, RESCAN_MINUTES, TimeUnit.MINUTES);
        rescanTimer.scheduleWithFixedDelay(this::checkReachable, REACHABLE_CHECK_SECONDS, REACHABLE_CHECK_SECONDS,
                TimeUnit.SECONDS);
        rescanTimer.scheduleWithFixedDelay(this::rescanNetworkFolders, NetworkDrives.RESCAN_MINUTES,
                NetworkDrives.RESCAN_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * The rescan timer's thread: looks whether every library folder that is on can be reached (each with a time
     * limit, so a dead network drive never holds this up), and tells the UI. A folder that is back gets an index run,
     * which catches up on what changed meanwhile and watches it again.
     */
    private void checkReachable() {
        try {
            Set<Path> offline = Reachability.unreachable(libraryRoots.enabledRoots(), REACHABLE_LIMIT);
            boolean back = !offline.containsAll(lastOffline);
            if (!offline.equals(lastOffline)) {
                log.info("Library folders offline: {}", offline.isEmpty() ? "none" : offline);
                Platform.runLater(() -> offlineFolders.set(OfflineFolders.of(offline)));
            }
            lastOffline = offline;
            offlineNow = Set.copyOf(offline);
            if (back) {
                startIndexing();
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not check the library folders", e);
        }
    }

    /**
     * Network library folders aren't watched (their change notices are unreliable), so they are looked through this
     * often instead, on the indexing thread like every other index run.
     */
    private void rescanNetworkFolders() {
        indexingThread.execute(() -> {
            try {
                List<Path> roots = libraryRoots.enabledRoots().stream()
                        .filter(networkRoots::contains).filter(root -> !offlineNow.contains(root)).toList();
                if (!roots.isEmpty() && indexer.index(roots, _ -> { }).changedAnything()) {
                    libraryChanged();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SQLException | RuntimeException e) {
                log.warn("Rescanning the network folders failed", e);
            }
        });
    }

    /**
     * Puts the window where it was last time (fitted to today's screens), maximised if it was; otherwise it opens
     * centred at the default size. Then remembers its normal place as it moves, for {@link #stop}.
     *
     * <p>Changes are read one pulse later (runLater): maximising moves and resizes the window before it reports
     * "maximised", and those maximised bounds must not be taken as the normal place.
     */
    private void placeWindow(Stage stage) {
        List<javafx.geometry.Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
        savedPlacement.flatMap(saved -> saved.fitTo(screens)).ifPresent(place -> {
            stage.setX(place.x());
            stage.setY(place.y());
            stage.setWidth(place.width());
            stage.setHeight(place.height());
            normalPlacement = new WindowPlacement(place.x(), place.y(), place.width(), place.height(), false);
            stage.setMaximized(place.maximized());
        });
        InvalidationListener remember = _ -> Platform.runLater(() -> {
            if (stage.isShowing() && !stage.isMaximized() && !stage.isFullScreen() && !stage.isIconified()) {
                normalPlacement = new WindowPlacement(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                        false);
            }
        });
        for (var property : List.of(stage.xProperty(), stage.yProperty(), stage.widthProperty(), stage.heightProperty(),
                stage.maximizedProperty(), stage.fullScreenProperty(), stage.iconifiedProperty(),
                stage.showingProperty())) {
            property.addListener(remember);
        }
    }

    /** The grid on screen: the search results while searching, otherwise the open tab's grid. */
    private TimelineView currentGrid() {
        if (searchBar.isActive()) {
            return searchResults;
        }
        return collectionsTab.isSelected() ? collections.grid() : timeline;
    }

    /** Shows the search results while searching, otherwise the open tab. */
    private void showCurrentView() {
        boolean searching = searchBar.isActive();
        searchResults.setVisible(searching);
        timeline.setVisible(!searching && timelineTab.isSelected());
        collections.setVisible(!searching && collectionsTab.isSelected());
        updateSortLock();
    }

    /** Recently Added has a fixed order: the Sort controls show it, locked, while that page is on screen. */
    private void updateSortLock() {
        boolean locked = collections.isVisible() && collections.sortLockedProperty().get();
        viewBar.setSortLocked(locked ? MediaScope.RECENTLY_ADDED.applyTo(viewOptions) : null);
    }

    /**
     * Asks before "Delete permanently" (one item, or a selection). Cancel is the default button, so Enter never
     * deletes. The text says what will really happen: the Recycle Bin where Java can use it, otherwise a real delete.
     */
    private static boolean confirmDelete(Stage owner, FileRemover remover, List<TimelineItem> items) {
        boolean one = items.size() == 1;
        ButtonType delete = new ButtonType(remover.usesRecycleBin() ? "Move to Recycle Bin" : "Delete for good",
                ButtonBar.ButtonData.OK_DONE);
        String text = remover.usesRecycleBin()
                ? (one ? "The file is" : "The files are") + " moved from " + (one ? "its folder" : "their folders")
                        + " to the Recycle Bin, and Prismo forgets " + (one ? "it" : "them") + ". You can get "
                        + (one ? "it" : "them") + " back from the Recycle Bin. On drives that have no Recycle Bin"
                        + " (USB sticks, network drives), Windows may delete " + (one ? "it" : "them") + " for good."
                : (one ? "The file is" : "The files are") + " deleted from disk for good. This can't be undone.";
        String where = one ? items.getFirst().ref().path().toString()
                : DialogTexts.fileList(items.stream().map(item -> item.ref().path()).toList());
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text + "\n\n" + where, delete, ButtonType.CANCEL);
        alert.initOwner(owner);
        Appearance.matchOwner(alert);
        alert.setGraphic(null); // no clip-art question mark: the header asks the question
        alert.setTitle("Delete permanently");
        alert.setHeaderText(one ? "Delete " + DialogTexts.quoted(items.getFirst().ref().path().getFileName().toString())
                + " permanently?" : "Delete %,d items permanently?".formatted(items.size()));
        ((Button) alert.getDialogPane().lookupButton(delete)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return alert.showAndWait().filter(delete::equals).isPresent();
    }

    /**
     * Something in the library changed (an index run, file changes, an item action): reload the Timeline (which
     * also re-runs the search on screen) and the Collections page. Called from any thread.
     */
    private void libraryChanged() {
        loadTimeline();
        Platform.runLater(collections::refresh);
    }

    /**
     * What the Library folders dialog does. Every change runs on the indexing thread, after any index run or
     * watcher batch already queued, so it never races them; then every view reloads.
     */
    private LibraryFoldersDialog.Operations libraryOperations() {
        return new LibraryFoldersDialog.Operations() {
            @Override
            public CompletableFuture<String> add(Path folder) {
                return onIndexingThread(() -> {
                    LibraryRootStore.Added added = libraryRoots.add(folder).get();
                    if (!added.added()) {
                        return added.path() + " is already in the library: it is inside " + added.insideOf() + ".";
                    }
                    for (Path inner : added.absorbed()) {
                        watcher.unwatch(inner); // the new folder's watch covers it
                        watchedRoots.remove(inner);
                    }
                    log.info("Added library folder {} (replaces {})", added.path(), added.absorbed());
                    startIndexing(); // queued next: watches it and indexes it
                    return "Added " + added.path() + "." + (added.absorbed().isEmpty() ? ""
                            : " It holds %d library folder(s) that were already here; they are now part of it."
                                    .formatted(added.absorbed().size()));
                });
            }

            @Override
            public CompletableFuture<Void> setEnabled(Path root, boolean enabled) {
                return onIndexingThread(() -> {
                    if (!enabled) {
                        watcher.unwatch(root);
                        watchedRoots.remove(root);
                    }
                    libraryRoots.setEnabled(root, enabled).get();
                    log.info("Library folder {} turned {}", root, enabled ? "on" : "off");
                    libraryChanged();
                    if (enabled) {
                        startIndexing(); // catches up on anything that changed while it was off
                    }
                    return null;
                });
            }

            @Override
            public CompletableFuture<Integer> remove(Path root) {
                return onIndexingThread(() -> {
                    watcher.unwatch(root);
                    watchedRoots.remove(root);
                    int forgotten = libraryRoots.remove(root).get();
                    log.info("Removed library folder {}: {} rows forgotten", root, forgotten);
                    libraryChanged();
                    return forgotten;
                });
            }
        };
    }

    /** A step of work that may throw, for {@link #onIndexingThread}. */
    private interface IndexingWork<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> onIndexingThread(IndexingWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e instanceof ExecutionException && e.getCause() != null
                        ? e.getCause() : e);
            }
        }, indexingThread);
    }

    private void startIndexing() {
        indexingThread.execute(() -> {
            try {
                loadTimeline();
                var roots = libraryRoots.enabledRoots();
                if (roots.isEmpty()) {
                    Platform.runLater(() -> status.setText("No library folders yet. Click \"Library folders…\" to add one."));
                    return;
                }
                watchNewRoots(roots); // before the walk, so nothing that changes during it is missed
                // Only touched on this thread (the progress callback runs here too); arrays so the lambda can update them.
                long[] lastLoad = {System.nanoTime()};
                int[] shownIndexed = {0};
                IndexProgress indexed = indexer.index(roots, progress -> {
                    Platform.runLater(() -> status.setText(describe(progress)));
                    // A first index of a big folder takes a while: show what's found so far every few seconds.
                    if (progress.indexed() > shownIndexed[0]
                            && System.nanoTime() - lastLoad[0] >= TIMELINE_RELOAD_NANOS) {
                        libraryChanged();
                        lastLoad[0] = System.nanoTime();
                        shownIndexed[0] = progress.indexed();
                    }
                });
                if (indexed.indexed() > shownIndexed[0] || indexed.moved() > 0 || indexed.removed() > 0) {
                    libraryChanged();
                }
                rescanTimer.execute(this::checkReachable);
                warmThumbnails(describe(indexed));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // app is shutting down
            } catch (SQLException | RuntimeException e) {
                log.error("Indexing failed", e);
                Platform.runLater(() -> status.setText("Indexing failed: " + e.getMessage()));
            }
        });
    }

    /**
     * Reads the Timeline for the current Filters + Sort on the {@code prismo-timeline} thread and hands it to
     * the UI. Called from any thread: after an index run (new files) and when the options change.
     */
    private void loadTimeline() {
        long number = latestTimelineLoad.incrementAndGet();
        timelineLoads.execute(() -> {
            if (number != latestTimelineLoad.get()) {
                return; // a newer load is queued behind this one and will read newer data anyway
            }
            ViewOptions options = viewOptions;
            try {
                long start = System.nanoTime();
                List<TimelineItem> items = database.read(c -> TimelineQuery.load(c, options, ZoneId.systemDefault()));
                log.info("Timeline loaded: {} items in {} ms", items.size(), (System.nanoTime() - start) / 1_000_000);
                Platform.runLater(() -> showTimeline(items, options));
            } catch (SQLException | RuntimeException e) {
                log.warn("Could not load the timeline", e);
                Platform.runLater(() -> status.setText("Could not load the timeline: " + e.getMessage()));
            }
        });
    }

    /** UI thread: shows a loaded Timeline, unless the options changed while it loaded (that load is queued). */
    private void showTimeline(List<TimelineItem> items, ViewOptions options) {
        if (!options.equals(viewOptions)) {
            return;
        }
        if (options.sameOrder(shownOptions)) {
            timeline.setItems(items); // new data (or the File Explorer switch), same view: stay on the same photo
            searchBar.refresh(); // new or changed files may match the search on screen
        } else {
            timeline.showFromTop(items, options.grouped()); // a different filter or sort: start at the top
        }
        shownOptions = options;
        if (options.filter() == ViewOptions.DEFAULT.filter()) {
            timelinePlaceholder.show(true, "No photos or videos yet",
                    "Add the folders that hold your photos and videos. Prismo only reads them: your files are never changed.",
                    "Add library folders…", openLibraryFolders);
        } else {
            timelinePlaceholder.show(false, "Nothing to show", "Nothing here matches \"" + options.filter() + "\".",
                    null, null);
        }
    }

    /** Runs on the indexing thread after an index run: makes every missing thumbnail, newest first. */
    private void warmThumbnails(String indexSummary) throws SQLException, InterruptedException {
        var media = database.read(c -> MediaRefs.newestToThumbnail(c));
        thumbnails.warmUp(media, p -> Platform.runLater(() -> status.setText(indexSummary
                + "  ·  Thumbnails %s %,d / %,d (%,d unsupported, %,d failed%s)".formatted(
                        p.done() ? "ready:" : "…", p.ready(), p.total(), p.unsupported(), p.failed(),
                        p.missing() == 0 ? "" : ", %,d not available".formatted(p.missing())))));
    }

    /**
     * Indexing thread: starts watching library folders not watched yet. A failure leaves the periodic rescan. A network
     * folder is never watched (P7): it is rescanned every {@link NetworkDrives#RESCAN_MINUTES} minutes instead.
     */
    private void watchNewRoots(List<Path> roots) {
        for (Path root : roots) {
            if (!watchedRoots.add(root)) {
                continue;
            }
            if (NetworkDrives.isNetwork(root)) {
                if (networkRoots.add(root)) {
                    log.info("{} is a network folder: not watched, rescanned every {} minutes", root,
                            NetworkDrives.RESCAN_MINUTES);
                }
                continue; // stays in watchedRoots, so it isn't looked at again every run
            }
            if (offlineNow.contains(root)) {
                watchedRoots.remove(root); // offline: the banner says so; watched once it is back
                continue;
            }
            try {
                watcher.watch(root);
            } catch (IOException | RuntimeException e) {
                watchedRoots.remove(root); // tried again at the next rescan
                if (e instanceof NoSuchFileException) {
                    // Not there (unplugged): the offline banner says so; it is watched once it is back.
                    log.info("Not watching {}: it can't be reached", root);
                    rescanTimer.execute(this::checkReachable);
                    continue;
                }
                log.warn("Can't watch {} for changes: {}", root, e.toString());
                Platform.runLater(() -> status.setText("Live updates are off for " + root + " (" + e.getMessage()
                        + "); it is rescanned every " + RESCAN_MINUTES + " minutes instead."));
            }
        }
    }

    /** Indexing thread: applies one batch of watcher changes, then refreshes what's on screen. */
    private void applyChanges(ChangeBatcher.Batch batch) {
        try {
            // A folder turned off or removed while its events waited: they no longer count.
            Set<Path> enabled = new HashSet<>(libraryRoots.enabledRoots());
            batch.stopped().forEach((root, reason) -> {
                watchedRoots.remove(root); // watched again at the next rescan if it's back
                log.warn("Stopped watching {}: {}", root, reason);
                Platform.runLater(() -> status.setText("Not watching " + root + ": " + reason
                        + ". It is rescanned every " + RESCAN_MINUTES + " minutes."));
            });
            boolean changed = false;
            List<Path> rescan = batch.rescan().stream().filter(enabled::contains).toList();
            Map<Path, Set<Path>> paths = new HashMap<>(batch.changed());
            paths.keySet().retainAll(enabled);
            if (!rescan.isEmpty()) {
                // Events were lost (e.g. thousands of files copied at once): only a full look is safe.
                changed = indexer.index(rescan, _ -> { }).changedAnything();
            }
            if (!paths.isEmpty()) {
                IndexProgress result = indexer.update(paths, _ -> { });
                changed |= result.changedAnything();
                if (result.changedAnything()) {
                    Platform.runLater(() -> status.setText(describeUpdate(result)));
                }
            }
            if (changed) {
                libraryChanged(); // also re-runs the search on screen
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // app is shutting down
        } catch (SQLException | RuntimeException e) {
            log.error("Applying file changes failed", e);
            Platform.runLater(() -> status.setText("Updating from file changes failed: " + e.getMessage()));
        }
    }

    private static String describeUpdate(IndexProgress p) {
        return "Updated from file changes: %,d new or changed · %,d moved or renamed · %,d removed"
                .formatted(p.indexed(), p.moved(), p.removed());
    }

    private static String describe(IndexProgress p) {
        String counts = "%,d files · %,d new or changed · %,d unchanged · %,d unreadable"
                .formatted(p.found(), p.indexed(), p.unchanged(), p.failed());
        if (p.moved() > 0 || p.removed() > 0) {
            counts += " · %,d moved · %,d removed".formatted(p.moved(), p.removed());
        }
        return p.done()
                ? "Library up to date (%.1f s): %s".formatted(p.elapsed().toMillis() / 1000.0, counts)
                : "Indexing… " + counts;
    }

    /** Runs on the JavaFX Application Thread after the last window closes. */
    @Override
    public void stop() throws InterruptedException {
        // No new work for the indexing thread: stop the timer, the watcher and its batches first.
        rescanTimer.shutdownNow();
        if (watcher != null) {
            watcher.close();
        }
        if (changes != null) {
            changes.close();
        }
        // Stop the indexer before the database: it may still be queueing writes.
        indexingThread.shutdownNow();
        if (!indexingThread.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("Indexer did not stop within 10 s");
        }
        timelineLoads.shutdownNow();
        if (!timelineLoads.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("Timeline loading did not stop within 5 s");
        }
        if (viewer != null) {
            viewer.dispose(); // saves where a playing video is (queued like the settings below)
            settings.put(VOLUME_SETTING, Double.toString(viewer.videoVolumeProperty().get()));
            settings.put(MUTED_SETTING, Boolean.toString(viewer.videoMutedProperty().get()));
            settings.put(SPEED_SETTING, Double.toString(viewer.videoSpeedProperty().get()));
        }
        if (thumbnailLoader != null) {
            thumbnailLoader.close();
        }
        if (timeline != null) {
            // Queued on the writer thread; database.close() below waits for it.
            settings.put(THUMBNAIL_SIZE_SETTING, Long.toString(Math.round(timeline.thumbnailSizeProperty().get())));
        }
        Window main = timeline == null || timeline.getScene() == null ? null : timeline.getScene().getWindow();
        if (normalPlacement != null && main instanceof Stage stage) {
            settings.put(WINDOW_SETTING, new WindowPlacement(normalPlacement.x(), normalPlacement.y(),
                    normalPlacement.width(), normalPlacement.height(), stage.isMaximized()).format());
        }
        if (thumbnails != null) {
            thumbnails.close();
        }
        if (searcher != null) {
            searcher.close();
        }
        if (database != null) {
            database.close();
        }
        log.info("Prismo stopped");
    }
}
