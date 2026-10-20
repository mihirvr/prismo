package io.github.mihirvr.prismo.ui;

import java.util.List;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Keeps a dialog on screen: no bigger than the usable area of the screen its owner is on (without the taskbar),
 * less a margin, and centred on its owner, moved fully onto that screen so its title bar can always be reached.
 * A dialog whose content is taller scrolls inside (its content sits in a ScrollPane).
 *
 * <p>{@link #place} is the plain rule (unit-tested); {@link #fit} applies it to a shown window.
 */
public final class DialogPlacement {

    /** Space kept free around a dialog at the screen's edges. */
    public static final double MARGIN = 24;

    private DialogPlacement() {
    }

    /**
     * Where a {@code width} × {@code height} dialog goes: shrunk to {@code screen} less {@link #MARGIN} on each
     * side, centred on {@code owner}, then moved inside that area.
     */
    public static Rectangle2D place(double width, double height, Rectangle2D owner, Rectangle2D screen) {
        double maxWidth = Math.max(0, screen.getWidth() - 2 * MARGIN);
        double maxHeight = Math.max(0, screen.getHeight() - 2 * MARGIN);
        double w = Math.min(width, maxWidth);
        double h = Math.min(height, maxHeight);
        double x = owner.getMinX() + (owner.getWidth() - w) / 2;
        double y = owner.getMinY() + (owner.getHeight() - h) / 2;
        x = Math.clamp(x, screen.getMinX() + MARGIN, Math.max(screen.getMinX() + MARGIN, screen.getMaxX() - MARGIN - w));
        y = Math.clamp(y, screen.getMinY() + MARGIN, Math.max(screen.getMinY() + MARGIN, screen.getMaxY() - MARGIN - h));
        return new Rectangle2D(x, y, w, h);
    }

    /** Sizes {@code dialog} to its content, then fits and centres it ({@link #place}). Call right after show(). */
    public static void fit(Stage dialog, Window owner) {
        dialog.sizeToScene();
        Rectangle2D ownerBounds = new Rectangle2D(owner.getX(), owner.getY(), Math.max(1, owner.getWidth()),
                Math.max(1, owner.getHeight()));
        List<Screen> screens = Screen.getScreensForRectangle(ownerBounds);
        Rectangle2D screen = (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        Rectangle2D place = place(dialog.getWidth(), dialog.getHeight(), ownerBounds, screen);
        dialog.setWidth(place.getWidth());
        dialog.setHeight(place.getHeight());
        dialog.setX(place.getMinX());
        dialog.setY(place.getMinY());
    }
}
