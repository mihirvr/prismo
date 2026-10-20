package io.github.mihirvr.prismo;

import io.github.mihirvr.prismo.collections.CollectionsPage;
import io.github.mihirvr.prismo.collections.FileRemover;
import io.github.mihirvr.prismo.collections.ItemActions;
import io.github.mihirvr.prismo.collections.LibraryFoldersDialog;
import io.github.mihirvr.prismo.collections.MediaFlags;
import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.db.LibraryRootStore;
import io.github.mihirvr.prismo.db.SettingsStore;
import io.github.mihirvr.prismo.index.IndexProgress;
import io.github.mihirvr.prismo.index.Indexer;
import io.github.mihirvr.prismo.index.MetadataReader;
import io.github.mihirvr.prismo.platform.AppPaths;
import io.github.mihirvr.prismo.search.SearchBar;
import io.github.mihirvr.prismo.search.Searcher;
import io.github.mihirvr.prismo.thumbs.MediaRefs;
import io.github.mihirvr.prismo.thumbs.ThumbnailCache;
import io.github.mihirvr.prismo.thumbs.ThumbnailGenerator;
import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import io.github.mihirvr.prismo.ui.EmptyState;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineQuery;
import io.github.mihirvr.prismo.ui.TimelineView;
import io.github.mihirvr.prismo.ui.ViewOptions;
import io.github.mihirvr.prismo.ui.ViewOptionsBar;
import io.github.mihirvr.prismo.viewer.MediaViewer;
import io.github.mihirvr.prismo.watch.ChangeBatcher;
import io.github.mihirvr.prismo.watch.FileWatcher;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Application;
import javafx.application.Platform;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The JavaFX application: owns the main window and the app's long-lived services. */
public final class PrismoApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(PrismoApp.class);

    private static final double DEFAULT_WIDTH = 1200;
    private static final double DEFAULT_HEIGHT = 800;
    private static final int GRID_THUMBNAIL_SIZE = 320;
    private static final String THUMBNAIL_SIZE_SETTING = "timeline.thumbnail_size";
    /** While indexing, show newly found files at most this often. */
    private static final long TIMELINE_RELOAD_NANOS = 3_000_000_000L;
    /** Watcher events are handled once a path has been quiet this long, but no later than MAX_WAIT. */
    private static final Duration CHANGES_QUIET = Duration.ofSeconds(1);
    private static final Duration CHANGES_MAX_WAIT = Duration.ofSeconds(5);
    /** The safety net: a full rescan this often catches anything the watcher missed. */
    private static final long RESCAN_MINUTES = 30;

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
    private final ScheduledExecutorService rescanTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("prismo-rescan-timer").daemon(true).factory());
    private double savedThumbnailSize = TimelineView.DEFAULT_THUMBNAIL_SIZE;
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
    private Image logo;
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
        indexer = new Indexer(database, new MetadataReader(ZoneId.systemDefault()));
        thumbnailCache = new ThumbnailCache(paths.cacheDir(), GRID_THUMBNAIL_SIZE);
        thumbnails = new ThumbnailService(thumbnailCache, new ThumbnailGenerator(GRID_THUMBNAIL_SIZE));
        searcher = new Searcher(database);
        // Watcher events → batches (debounced) → applied on the indexing thread, after any run already queued.
        changes = new ChangeBatcher(CHANGES_QUIET, CHANGES_MAX_WAIT,
                batch -> indexingThread.execute(() -> applyChanges(batch)));
        watcher = FileWatcher.forThisComputer(changes);
        try {
            settings.get(THUMBNAIL_SIZE_SETTING).ifPresent(value -> savedThumbnailSize = Double.parseDouble(value));
        } catch (NumberFormatException e) {
            log.warn("Ignoring bad {} setting: {}", THUMBNAIL_SIZE_SETTING, e.getMessage());
        }
    }

    /** Runs on the JavaFX Application Thread. */
    @Override
    public void start(Stage stage) {
        thumbnailLoader = new ThumbnailLoader(thumbnails);
        timeline = new TimelineView(thumbnailLoader);
        timeline.setThumbnailSize(savedThumbnailSize);
        timeline.setPlaceholder(timelinePlaceholder);
        // HostServices opens a file in the system's default app (HEIC, RAW, and videos that can't be played).
        viewer = new MediaViewer(thumbnailLoader, getHostServices()::showDocument);
        timeline.setOnOpen(viewer::open);

        // The Collections tab: its pages load on the Timeline's load thread, so loads never overlap.
        collections = new CollectionsPage(database, thumbnailLoader, timelineLoads);
        collections.grid().thumbnailSizeProperty().bindBidirectional(timeline.thumbnailSizeProperty());
        collections.setOnOpen(viewer::open);
        collections.setVisible(false);

        // Search results get their own grid, so the Timeline keeps its place while searching.
        searchResults = new TimelineView(thumbnailLoader);
        searchResults.thumbnailSizeProperty().bindBidirectional(timeline.thumbnailSizeProperty());
        searchResults.setOnOpen(viewer::open);
        searchResults.setVisible(false);
        searchBar = new SearchBar(searcher, searchResults);
        searchBar.activeProperty().addListener((_, _, _) -> showCurrentView());

        // Favorite / hide / Trash / restore / delete, the same from every grid and the viewer.
        FileRemover remover = FileRemover.forThisComputer();
        ItemActions actions = new ItemActions(new MediaFlags(database), remover, thumbnailCache::fileFor,
                item -> confirmDelete(stage, remover, item), Platform::runLater, status::setText,
                this::libraryChanged);
        timeline.setOnAction(actions);
        searchResults.setOnAction(actions);
        collections.setOnAction(actions);
        viewer.setOnAction(actions);
        searchBar.setOnLeave(() -> currentGrid().requestFocus());
        viewer.setOnClose(item -> {
            TimelineView grid = currentGrid();
            grid.reveal(item.ref().id());
            grid.requestFocus();
        });

        Label title = new Label("Prismo");
        title.getStyleClass().add("app-title");
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
            viewOptions = options;
            searchBar.setOptions(options);
            collections.setOptions(options);
            loadTimeline();
        });
        collections.sortLockedProperty().addListener((_, _, _) -> updateSortLock());
        Slider size = new Slider(TimelineView.MIN_THUMBNAIL_SIZE, TimelineView.MAX_THUMBNAIL_SIZE,
                timeline.thumbnailSizeProperty().get());
        size.valueProperty().bindBidirectional(timeline.thumbnailSizeProperty());
        size.setTooltip(new Tooltip("Thumbnail size (or Ctrl + mouse wheel)"));
        Button libraryFolders = new Button("Library folders…");
        libraryFolders.setTooltip(new Tooltip("Add, turn off or remove the folders Prismo shows"));
        openLibraryFolders = () -> new LibraryFoldersDialog(stage, libraryRoots, libraryOperations()).show();
        libraryFolders.setOnAction(_ -> openLibraryFolders.run());
        HBox topBar = new HBox(title, tabBar, searchBar, spacer, libraryFolders);
        topBar.getStyleClass().add("top-bar");
        Region viewSpacer = new Region();
        HBox.setHgrow(viewSpacer, Priority.ALWAYS);
        viewBar.getChildren().addAll(viewSpacer, new Label("Size"), size);
        status.getStyleClass().add("status-bar");
        status.setMaxWidth(Double.MAX_VALUE);

        BorderPane main = new BorderPane(new StackPane(timeline, collections, searchResults),
                new VBox(topBar, viewBar), null, status, null);
        // The viewer is an overlay over the whole window, hidden until a photo is opened.
        StackPane root = new StackPane(main, viewer);
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(PrismoApp.class.getResource("/ui/prismo.css").toExternalForm());
        stage.setTitle("Prismo");
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
        stage.show();
        timeline.requestFocus();
        log.info("Main window shown");

        // Show what's already indexed straight away, then pick up anything that changed since the last run.
        startIndexing();
        rescanTimer.scheduleWithFixedDelay(this::startIndexing, RESCAN_MINUTES, RESCAN_MINUTES, TimeUnit.MINUTES);
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
     * Asks before "Delete permanently". Cancel is the default button, so Enter never deletes. The text says
     * what will really happen: the Recycle Bin where Java can use it, otherwise a real delete.
     */
    private static boolean confirmDelete(Stage owner, FileRemover remover, TimelineItem item) {
        String name = item.ref().path().getFileName().toString();
        ButtonType delete = new ButtonType(remover.usesRecycleBin() ? "Move to Recycle Bin" : "Delete for good",
                ButtonBar.ButtonData.OK_DONE);
        String text = remover.usesRecycleBin()
                ? "The file is moved from its folder to the Recycle Bin, and Prismo forgets it. You can get it back"
                        + " from the Recycle Bin. On drives that have no Recycle Bin (USB sticks, network drives),"
                        + " Windows may delete it for good."
                : "The file is deleted from disk for good. This can't be undone.";
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text + "\n\n" + item.ref().path(), delete,
                ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setTitle("Delete permanently");
        alert.setHeaderText("Delete \u201c" + name + "\u201d permanently?");
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
        if (options.equals(shownOptions)) {
            timeline.setItems(items); // new data, same view: stay on the same photo
            searchBar.refresh(); // new or changed files may match the search on screen
        } else {
            timeline.showFromTop(items, options.grouped()); // a different filter or sort: start at the top
        }
        shownOptions = options;
        if (options.filter() == ViewOptions.DEFAULT.filter()) {
            timelinePlaceholder.show(logo(), "No photos or videos yet",
                    "Add the folders that hold your photos and videos. Prismo only reads them: your files are never changed.",
                    "Add library folders…", openLibraryFolders);
        } else {
            timelinePlaceholder.show(null, "Nothing to show", "Nothing here matches \"" + options.filter() + "\".",
                    null, null);
        }
    }

    /** The Prismo logo with its name, for light backgrounds. Loaded once, on first use. */
    private Image logo() {
        if (logo == null) {
            logo = new Image(PrismoApp.class.getResource("/ui/logo/prismo-text-light.png").toExternalForm());
        }
        return logo;
    }

    /** Runs on the indexing thread after an index run: makes every missing thumbnail, newest first. */
    private void warmThumbnails(String indexSummary) throws SQLException, InterruptedException {
        var media = database.read(c -> MediaRefs.newestToThumbnail(c));
        thumbnails.warmUp(media, p -> Platform.runLater(() -> status.setText(indexSummary
                + "  ·  Thumbnails %s %,d / %,d (%,d unsupported, %,d failed)".formatted(
                        p.done() ? "ready:" : "…", p.ready(), p.total(), p.unsupported(), p.failed()))));
    }

    /** Indexing thread: starts watching library folders not watched yet. A failure leaves the periodic rescan. */
    private void watchNewRoots(List<Path> roots) {
        for (Path root : roots) {
            if (!watchedRoots.add(root)) {
                continue;
            }
            try {
                watcher.watch(root);
            } catch (IOException | RuntimeException e) {
                watchedRoots.remove(root); // tried again at the next rescan
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
            viewer.dispose();
        }
        if (thumbnailLoader != null) {
            thumbnailLoader.close();
        }
        if (timeline != null) {
            // Queued on the writer thread; database.close() below waits for it.
            settings.put(THUMBNAIL_SIZE_SETTING, Long.toString(Math.round(timeline.thumbnailSizeProperty().get())));
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
