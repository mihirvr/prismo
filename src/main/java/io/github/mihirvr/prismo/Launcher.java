package io.github.mihirvr.prismo;

import io.github.mihirvr.prismo.platform.AppPaths;
import javafx.application.Application;

/**
 * Entry point.
 *
 * <p>Kept separate from {@link PrismoApp} on purpose: when the main class itself extends
 * {@code Application}, the Java launcher insists JavaFX is loaded as named modules and fails with
 * "JavaFX runtime components are missing" otherwise (e.g. when an IDE runs the app with JavaFX on the
 * classpath). A plain main class skips that check.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        AppPaths paths = AppPaths.forCurrentOs();
        // logback.xml reads this. Logback configures itself when the first logger is created, so this
        // must run before any LoggerFactory call — which is why this class has no logger field.
        System.setProperty("prismo.logDir", paths.logDir().toString());

        Application.launch(PrismoApp.class, args);
    }
}
