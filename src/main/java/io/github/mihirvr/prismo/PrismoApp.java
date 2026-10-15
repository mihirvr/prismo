package io.github.mihirvr.prismo;

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

    /** Runs on the JavaFX Application Thread. */
    @Override
    public void start(Stage stage) {
        log.info("Starting Prismo on Java {} / JavaFX {} ({})",
                System.getProperty("java.version"),
                System.getProperty("javafx.runtime.version"),
                System.getProperty("os.name"));

        StackPane root = new StackPane(new Label("Prismo"));
        stage.setTitle("Prismo");
        stage.setScene(new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT));
        stage.show();

        log.info("Main window shown");
    }

    /** Runs on the JavaFX Application Thread after the last window closes. */
    @Override
    public void stop() {
        log.info("Prismo stopped");
    }
}
