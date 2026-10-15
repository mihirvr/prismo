package io.github.mihirvr.prismo;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.db.LibraryRootStore;
import io.github.mihirvr.prismo.index.IndexProgress;
import io.github.mihirvr.prismo.index.Indexer;
import io.github.mihirvr.prismo.index.MetadataReader;
import io.github.mihirvr.prismo.platform.AppPaths;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

    private Database database;
    private LibraryRootStore libraryRoots;
    private Indexer indexer;
    /** One indexing run at a time; extra requests queue up behind it. */
    private final ExecutorService indexingThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("prismo-indexer").daemon(true).factory());

    private final Label status = new Label("Starting…");

    /** Runs on the JavaFX-Launcher thread, before any window exists, so slow setup here never freezes the UI. */
    @Override
    public void init() throws SQLException, IOException {
        log.info("Starting Prismo on Java {} / JavaFX {} ({})",
                System.getProperty("java.version"),
                System.getProperty("javafx.runtime.version"),
                System.getProperty("os.name"));

        database = Database.open(AppPaths.forCurrentOs().databaseFile());
        libraryRoots = new LibraryRootStore(database);
        indexer = new Indexer(database, new MetadataReader(ZoneId.systemDefault()));
    }

    /** Runs on the JavaFX Application Thread. */
    @Override
    public void start(Stage stage) {
        // Temporary screen until the Timeline (#5): add a folder and watch it index.
        Button addFolder = new Button("Add folder…");
        addFolder.setOnAction(event -> chooseFolder(stage));
        VBox root = new VBox(12, new Label("Prismo"), addFolder, status);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));

        stage.setTitle("Prismo");
        stage.setScene(new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT));
        stage.show();
        log.info("Main window shown");

        // Pick up anything that changed in the library since the last run.
        startIndexing();
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
                var roots = libraryRoots.enabledRoots();
                if (roots.isEmpty()) {
                    Platform.runLater(() -> status.setText("No library folders yet. Click \"Add folder…\"."));
                    return;
                }
                indexer.index(roots, progress -> Platform.runLater(() -> status.setText(describe(progress))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // app is shutting down
            } catch (SQLException | RuntimeException e) {
                log.error("Indexing failed", e);
                Platform.runLater(() -> status.setText("Indexing failed: " + e.getMessage()));
            }
        });
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
        if (database != null) {
            database.close();
        }
        log.info("Prismo stopped");
    }
}
