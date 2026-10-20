package io.github.mihirvr.prismo.ui;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.geometry.Dimension2D;
import javafx.geometry.Rectangle2D;

/**
 * Where the main window was: its normal (not maximised) position and size, and whether it was maximised. Saved
 * when Prismo closes, used when it starts.
 *
 * <p>Screens change between runs (a laptop undocked, a second monitor unplugged), so before use the place is
 * fitted to the screens there are now ({@link #fitTo}): it goes on the screen it overlaps most, shrunk to fit and
 * moved fully onto it; if it overlaps none, it is not used (the window opens centred at its default size).
 *
 * <p>Plain Java (screen areas come in as rectangles), so it is unit-tested. Sizes are JavaFX layout units, the
 * same at every display scaling.
 */
public record WindowPlacement(double x, double y, double width, double height, boolean maximized) {

    /** The smallest the window can be (the top bar needs about this); a saved size below it is treated as damaged. */
    public static final double MIN_WIDTH = 760;
    public static final double MIN_HEIGHT = 480;
    /** Room left around a first window's content for the title bar, the frame and a little screen around it. */
    static final double FRAME_ROOM = 64;

    /** "x,y,width,height,maximized", e.g. "120.0,80.0,1200.0,800.0,false". */
    public String format() {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.1f,%b", x, y, width, height, maximized);
    }

    /** The saved text back; empty if missing or damaged. */
    public static Optional<WindowPlacement> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] parts = text.split(",");
        if (parts.length != 5) {
            return Optional.empty();
        }
        try {
            double x = Double.parseDouble(parts[0].strip());
            double y = Double.parseDouble(parts[1].strip());
            double width = Double.parseDouble(parts[2].strip());
            double height = Double.parseDouble(parts[3].strip());
            String max = parts[4].strip();
            if (!max.equals("true") && !max.equals("false")) {
                return Optional.empty();
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !(width >= MIN_WIDTH) || !(height >= MIN_HEIGHT)
                    || !Double.isFinite(width) || !Double.isFinite(height)) {
                return Optional.empty();
            }
            return Optional.of(new WindowPlacement(x, y, width, height, Boolean.parseBoolean(max)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * This place fitted to {@code screens} (each screen's usable area, without the taskbar): on the screen it
     * overlaps most, no bigger than that screen and moved fully onto it. Empty if it overlaps no screen.
     */
    public Optional<WindowPlacement> fitTo(List<Rectangle2D> screens) {
        Rectangle2D best = null;
        double bestArea = 0;
        for (Rectangle2D screen : screens) {
            double area = overlap(screen);
            if (area > bestArea) {
                best = screen;
                bestArea = area;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        double fittedWidth = Math.min(width, best.getWidth());
        double fittedHeight = Math.min(height, best.getHeight());
        double fittedX = clamp(x, best.getMinX(), best.getMaxX() - fittedWidth);
        double fittedY = clamp(y, best.getMinY(), best.getMaxY() - fittedHeight);
        return Optional.of(new WindowPlacement(fittedX, fittedY, fittedWidth, fittedHeight, maximized));
    }

    /**
     * The size of a first window's content (no saved place yet): {@code wanted}, made smaller where {@code screen}
     * (its usable area) can't hold it with the title bar and frame ({@link #FRAME_ROOM}), never below the minimum.
     */
    public static Dimension2D firstSize(double wantedWidth, double wantedHeight, Rectangle2D screen) {
        double width = Math.max(MIN_WIDTH, Math.min(wantedWidth, screen.getWidth() - FRAME_ROOM));
        double height = Math.max(MIN_HEIGHT, Math.min(wantedHeight, screen.getHeight() - FRAME_ROOM));
        return new Dimension2D(width, height);
    }

    private double overlap(Rectangle2D screen) {
        double across = Math.min(x + width, screen.getMaxX()) - Math.max(x, screen.getMinX());
        double down = Math.min(y + height, screen.getMaxY()) - Math.max(y, screen.getMinY());
        return across > 0 && down > 0 ? across * down : 0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
