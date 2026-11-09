package io.github.mihirvr.prismo.ui;

import java.nio.file.Path;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.paint.Color;

/**
 * Drags files out of Prismo from the viewer or the filmstrip (1.3.0), as the grid does (1.2.0,
 * {@code TimelineView.dragOut}): onto a browser page such as WhatsApp Web, a mail, a chat app or a folder. Only a
 * copy is offered, so dropping on a folder copies the file and never moves it out of the library. Call it from a
 * {@code DRAG_DETECTED} handler, which JavaFX fires only after the mouse moved a little with the button down, so a
 * plain click is never a drag.
 */
public final class DragOut {

    /** The drag picture's longest side. */
    static final double PICTURE_EDGE = 120;

    private DragOut() {
    }

    /** Starts dragging {@code files} from {@code source}; {@code picture} (may be null) is shown small under the mouse. */
    public static void start(Node source, List<Path> files, Image picture) {
        if (files.isEmpty()) {
            return;
        }
        Dragboard board = source.startDragAndDrop(TransferMode.COPY);
        ClipboardContent content = new ClipboardContent();
        content.putFiles(files.stream().map(Path::toFile).toList());
        board.setContent(content);
        Image small = small(picture);
        if (small != null) {
            board.setDragView(small, small.getWidth() / 2, small.getHeight() / 2);
        }
    }

    /** {@code picture} with its longest side at most {@link #PICTURE_EDGE}, or null. */
    private static Image small(Image picture) {
        if (picture == null || picture.getWidth() <= 0 || picture.getHeight() <= 0) {
            return null;
        }
        ImageView view = new ImageView(picture);
        view.setPreserveRatio(true);
        if (picture.getWidth() >= picture.getHeight()) {
            view.setFitWidth(Math.min(PICTURE_EDGE, picture.getWidth()));
        } else {
            view.setFitHeight(Math.min(PICTURE_EDGE, picture.getHeight()));
        }
        view.setSmooth(true);
        SnapshotParameters transparent = new SnapshotParameters();
        transparent.setFill(Color.TRANSPARENT);
        return view.snapshot(transparent, null);
    }
}
