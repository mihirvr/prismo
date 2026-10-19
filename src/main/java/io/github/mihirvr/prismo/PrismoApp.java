package io.github.mihirvr.prismo;

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
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineQuery;
import io.github.mihirvr.prismo.ui.TimelineView;
import io.github.mihirvr.prismo.ui.ViewOptions;
import io.github.mihirvr.prismo.ui.ViewOptionsBar;
import io.github.mihirvr.prismo.viewer.MediaViewer;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
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

    private Database database;
    private SettingsStore settings;
    private LibraryRootStore libraryRoots;
    private Indexer indexer;
    private ThumbnailService thumbnails;
    private Searcher searcher;
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
    private final Label timelinePlaceholder = new Label();
    private ThumbnailLoader thumbnailLoader;
    private TimelineView timeline;
    private TimelineView searchResults;
    private SearchBar searchBar;
    private MediaViewer viewer;

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
        thumbnails = new ThumbnailService(new ThumbnailCache(paths.cacheDir(), GRID_THUMBNAIL_SIZE),
                new ThumbnailGenerator(GRID_THUMBNAIL_SIZE));
        searcher = new Searcher(database);
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
        // HostServices opens a file in the system's default app (MOV, MKV… aren't played in the app yet).
        viewer = new MediaViewer(thumbnailLoader, getHostServices()::showDocument);
        timeline.setOnOpen(viewer::open);

        // Search results get their own grid, so the Timeline keeps its place while searching.
        searchResults = new TimelineView(thumbnailLoader);
        searchResults.thumbnailSizeProperty().bindBidirectional(timeline.thumbnailSizeProperty());
        searchResults.setOnOpen(viewer::open);
        searchResults.setVisible(false);
        searchBar = new SearchBar(searcher, searchResults);
        searchBar.activeProperty().addListener((_, _, searching) -> {
            searchResults.setVisible(searching);
            timeline.setVisible(!searching);
        });
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
        ViewOptionsBar viewBar = new ViewOptionsBar();
        viewBar.optionsProperty().addListener((_, _, options) -> {
            viewOptions = options;
            searchBar.setOptions(options);
            loadTimeline();
        });
        Slider size = new Slider(TimelineView.MIN_THUMBNAIL_SIZE, TimelineView.MAX_THUMBNAIL_SIZE,
                timeline.thumbnailSizeProperty().get());
        size.valueProperty().bindBidirectional(timeline.thumbnailSizeProperty());
        size.setTooltip(new Tooltip("Thumbnail size (or Ctrl + mouse wheel)"));
        Button addFolder = new Button("Add folder…");
        addFolder.setOnAction(event -> chooseFolder(stage));
        HBox topBar = new HBox(title, searchBar, spacer, addFolder);
        topBar.getStyleClass().add("top-bar");
        Region viewSpacer = new Region();
        HBox.setHgrow(viewSpacer, Priority.ALWAYS);
        viewBar.getChildren().addAll(viewSpacer, new Label("Size"), size);
        status.getStyleClass().add("status-bar");
        status.setMaxWidth(Double.MAX_VALUE);

        BorderPane main = new BorderPane(new StackPane(timeline, searchResults), new VBox(topBar, viewBar), null,
                status, null);
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
        stage.show();
        timeline.requestFocus();
        log.info("Main window shown");

        // Show what's already indexed straight away, then pick up anything that changed since the last run.
        startIndexing();
    }

    /** The grid on screen: the search results while searching, otherwise the Timeline. */
    private TimelineView currentGrid() {
        return searchBar.isActive() ? searchResults : timeline;
    }

    private void chooseFolder(Stage owner) {
        File folder = new DirectoryChooser().showDialog(owner);
        if (folder == null) {
            return;
        }
        try {
            libraryRoots.add(folder.toPath()).whenComplete((added, error) -> {
                if (error != null) {
                    log.warn("Could not add library folder {}", folder, error);
                    Platform.runLater(() -> status.setText("Could not add " + folder + ": " + error.getMessage()));
                } else {
                    log.info("Added library folder {}", added);
                    startIndexing();
                }
            });
        } catch (IOException e) {
            status.setText("Can't open " + folder + ": " + e.getMessage());
        }
    }

    private void startIndexing() {
        indexingThread.execute(() -> {
            try {
                loadTimeline();
                var roots = libraryRoots.enabledRoots();
                if (roots.isEmpty()) {
                    Platform.runLater(() -> status.setText("No library folders yet. Click \"Add folder…\"."));
                    return;
                }
                // Only touched on this thread (the progress callback runs here too); arrays so the lambda can update them.
                long[] lastLoad = {System.nanoTime()};
                int[] shownIndexed = {0};
                IndexProgress indexed = indexer.index(roots, progress -> {
                    Platform.runLater(() -> status.setText(describe(progress)));
                    // A first index of a big folder takes a while: show what's found so far every few seconds.
                    if (progress.indexed() > shownIndexed[0]
                            && System.nanoTime() - lastLoad[0] >= TIMELINE_RELOAD_NANOS) {
                        loadTimeline();
                        lastLoad[0] = System.nanoTime();
                        shownIndexed[0] = progress.indexed();
                    }
                });
                if (indexed.indexed() > shownIndexed[0]) {
                    loadTimeline();
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
        timelinePlaceholder.setText(options.filter() == ViewOptions.DEFAULT.filter()
                ? "No photos yet. Click \"Add folder…\" to choose a folder to show."
                : "Nothing to show for \"" + options.filter() + "\".");
    }

    /** Runs on the indexing thread after an index run: makes every missing thumbnail, newest first. */
    private void warmThumbnails(String indexSummary) throws SQLException, InterruptedException {
        var images = database.read(c -> MediaRefs.newestImages(c, Integer.MAX_VALUE));
        thumbnails.warmUp(images, p -> Platform.runLater(() -> status.setText(indexSummary
                + "  ·  Thumbnails %s %,d / %,d (%,d unsupported, %,d failed)".formatted(
                        p.done() ? "ready:" : "…", p.ready(), p.total(), p.unsupported(), p.failed()))));
    }

    private static String describe(IndexProgress p) {
        String counts = "%,d files · %,d new or changed · %,d unchanged · %,d unreadable"
                .formatted(p.found(), p.indexed(), p.unchanged(), p.failed());
        return p.done()
                ? "Library up to date (%.1f s): %s".formatted(p.elapsed().toMillis() / 1000.0, counts)
                : "Indexing… " + counts;
    }

    /** Runs on the JavaFX Application Thread after the last window closes. */
    @Override
    public void stop() throws InterruptedException {
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
