package io.github.mihirvr.prismo;

import io.github.mihirvr.prismo.platform.AppPaths;
import io.github.mihirvr.prismo.thumbs.ThumbnailCache;
import io.github.mihirvr.prismo.thumbs.ThumbnailGenerator;
import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import io.github.mihirvr.prismo.ui.Appearance;
import io.github.mihirvr.prismo.ui.FileItems;
import io.github.mihirvr.prismo.ui.Menus;
import io.github.mihirvr.prismo.ui.Theme;
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.viewer.FolderListing;
import io.github.mihirvr.prismo.viewer.MediaViewer;
import io.github.mihirvr.prismo.viewer.PlaybackMemory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standalone viewer (1.1.0): what opens when a photo or video is opened with Prismo from File Explorer ("Open
 * with", or Prismo as the default app). No database, no library, no indexing: the file shows at once, then its
 * folder is listed in the background so the arrows, the wheel and the filmstrip step through it. Thumbnails come
 * from the usual {@link ThumbnailService}, with their own cache folder ({@code viewer} in Prismo's cache), so the
 * library's thumbnails are never touched.
 */
public final class StandaloneViewer extends Application {

    private static final Logger log = LoggerFactory.getLogger(StandaloneViewer.class);
    private static final int THUMBNAIL_SIZE = 320;
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private ThumbnailService thumbnails;
    private ThumbnailLoader loader;
    private MediaViewer viewer;

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        Path file = Path.of(args.getFirst()).toAbsolutePath().normalize();
        log.info("Standalone viewer for {}", file);
        AppPaths paths = AppPaths.forCurrentOs();
        thumbnails = new ThumbnailService(new ThumbnailCache(paths.cacheDir().resolve("viewer"), THUMBNAIL_SIZE),
                new ThumbnailGenerator(THUMBNAIL_SIZE));
        loader = new ThumbnailLoader(thumbnails);
        // No database: Show in folder, Open with default app and Copy work; Rename and Move are not offered.
        FileItems fileItems = new FileItems(null, getHostServices()::showDocument, _ -> { });
        viewer = new MediaViewer(loader, PlaybackMemory.NONE, fileItems);
        viewer.setItemActionsShown(false); // Favorite, Hide and Trash belong to the library
        viewer.setOnClose(_ -> stage.close());

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(new StackPane(viewer), Math.min(1280, screen.getWidth() * 0.8),
                Math.min(860, screen.getHeight() * 0.8));
        new Appearance(scene, Theme.DEFAULT);
        stage.setScene(scene);
        stage.setTitle(file.getFileName() + " - Prismo");
        for (int size : ICON_SIZES) {
            stage.getIcons().add(new Image(StandaloneViewer.class.getResource("/ui/icon/prismo-" + size + ".png")
                    .toExternalForm()));
        }
        Menus.closeOnScrollAndBlur(stage);
        stage.show();
        viewer.open(List.of(FolderListing.itemOf(file)), 0);

        // The rest of the folder, after the file is on screen.
        Thread.ofVirtual().name("prismo-viewer-folder").start(() -> {
            try {
                List<TimelineItem> items = FolderListing.list(file.getParent());
                int at = FolderListing.indexOf(items, file);
                Platform.runLater(() -> viewer.extendList(items, at));
            } catch (IOException | RuntimeException e) {
                log.warn("Could not list the folder of {}: {}", file, e.toString());
            }
        });
    }

    @Override
    public void stop() {
        if (viewer != null) {
            viewer.dispose();
        }
        if (loader != null) {
            loader.close();
        }
        if (thumbnails != null) {
            thumbnails.close();
        }
    }
}
