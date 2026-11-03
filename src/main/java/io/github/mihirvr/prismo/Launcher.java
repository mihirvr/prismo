package io.github.mihirvr.prismo;

import io.github.mihirvr.prismo.platform.AppPaths;
import io.github.mihirvr.prismo.video.VideoNatives;
import io.github.mihirvr.prismo.viewer.FolderListing;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
        // FFmpeg's native files are unpacked into Prismo's cache, not into ~/.javacpp in the home folder.
        VideoNatives.useCacheDir(paths.cacheDir().resolve("native"));

        // A photo or video opened from File Explorer ("Open with", or the default app) comes as the first argument:
        // it opens in the standalone viewer, without the library (1.1.0). Anything else starts the app as before.
        if (opensAFile(args)) {
            Application.launch(StandaloneViewer.class, args);
        } else {
            Application.launch(PrismoApp.class, args);
        }
    }

    /** True if the first argument is a photo or video file that exists. */
    static boolean opensAFile(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            return false;
        }
        try {
            Path file = Path.of(args[0]);
            return Files.isRegularFile(file) && FolderListing.isMedia(file);
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
