package io.github.mihirvr.prismo;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.platform.AppPaths;
import java.io.IOException;
import java.sql.SQLException;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The JavaFX application: owns the main window. */
public final class PrismoApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(PrismoApp.class);

    private static final double DEFAULT_WIDTH = 1200;
    private static final double DEFAULT_HEIGHT = 800;

    private Database database;

    /** Runs on the JavaFX-Launcher thread, before any window exists, so slow setup here never freezes the UI. */
    @Override
    public void init() throws SQLException, IOException {
        log.info("Starting Prismo on Java {} / JavaFX {} ({})",
                System.getProperty("java.version"),
                System.getProperty("javafx.runtime.version"),
                System.getProperty("os.name"));

        database = Database.open(AppPaths.forCurrentOs().databaseFile());
    }

    /** Runs on the JavaFX Application Thread. */
    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane(new Label("Prismo"));
        stage.setTitle("Prismo");
        stage.setScene(new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT));
        stage.show();

        log.info("Main window shown");
    }

    /** Runs on the JavaFX Application Thread after the last window closes. */
    @Override
    public void stop() {
        if (database != null) {
            database.close();
        }
        log.info("Prismo stopped");
    }
}
