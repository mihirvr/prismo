package io.github.mihirvr.prismo.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**
 * Prismo's icons: simple line drawings on a 24 × 24 grid, drawn as SVG paths (our own drawings, no icon font,
 * no emoji), so they look the same on every computer and stay sharp at any size and display scaling.
 *
 * <p>The colour and line width come from the stylesheet ({@code .icon-path}, {@code .icon-filled}), so an icon
 * follows the theme and the place it's in (a dark viewer bar, a selected list row).
 */
public enum Icon {
    BACK("M19 12H5M11 18L5 12L11 6"),
    CHEVRON_LEFT("M15 18L9 12L15 6"),
    CHEVRON_RIGHT("M9 18L15 12L9 6"),
    CLOSE("M18 6L6 18M6 6L18 18"),
    SEARCH("M10.5 4A6.5 6.5 0 1 1 10.5 17A6.5 6.5 0 1 1 10.5 4ZM15.3 15.3L20 20"),
    FOLDER("M3.5 7.5A2 2 0 0 1 5.5 5.5H9.3L11.3 7.5H18.5A2 2 0 0 1 20.5 9.5V16.5A2 2 0 0 1 18.5 18.5H5.5"
            + "A2 2 0 0 1 3.5 16.5Z"),
    /** Two sliders: the Settings button. */
    SETTINGS("M4 8H6.5M11.5 8H20M4 16H12.5M17.5 16H20M9 5.5A2.5 2.5 0 1 1 9 10.5A2.5 2.5 0 1 1 9 5.5Z"
            + "M15 13.5A2.5 2.5 0 1 1 15 18.5A2.5 2.5 0 1 1 15 13.5Z"),
    HEART(Shapes.HEART),
    HEART_FILLED(Shapes.HEART, true),
    EYE(Shapes.EYE),
    EYE_OFF(Shapes.EYE + "M4 4L20 20"),
    TRASH("M4 7H20M9.5 7V4.5H14.5V7M6 7L7 19.5H17L18 7M10 11V16M14 11V16"),
    /** An arrow turning back: Restore from Trash. */
    RESTORE("M9 14L4 9L9 4M4 9H14.5A5.5 5.5 0 0 1 14.5 20H11"),
    ROTATE("M20 12A8 8 0 1 1 12 4C14.3 4 16.4 5 17.9 6.5L20 8.5M20 4V8.5H15.5"),
    FULL_SCREEN("M4 9V4H9M15 4H20V9M20 15V20H15M9 20H4V15"),
    EXIT_FULL_SCREEN("M9 4V9H4M15 4V9H20M20 15H15V20M4 15H9V20"),
    PLAY("M8 5.5V18.5L18.5 12Z", true),
    PAUSE("M7 5.5H10V18.5H7ZM14 5.5H17V18.5H14Z", true),
    VOLUME(Shapes.SPEAKER + "M15.5 9.2A4 4 0 0 1 15.5 14.8M18.2 6.6A7.7 7.7 0 0 1 18.2 17.4"),
    MUTED(Shapes.SPEAKER + "M16 9.5L21 14.5M21 9.5L16 14.5"),
    /** A play mark in a frame: the slideshow. */
    SLIDESHOW("M3.5 6.5A2 2 0 0 1 5.5 4.5H18.5A2 2 0 0 1 20.5 6.5V17.5A2 2 0 0 1 18.5 19.5H5.5A2 2 0 0 1 3.5 17.5Z"
            + "M10 9V15L15 12Z"),
    /** A photo in a frame: Open. */
    IMAGE("M3.5 6.5A2 2 0 0 1 5.5 4.5H18.5A2 2 0 0 1 20.5 6.5V17.5A2 2 0 0 1 18.5 19.5H5.5A2 2 0 0 1 3.5 17.5Z"
            + "M3.5 16L8 11.5L12 15.5L14.5 13L20.5 19M15 8.5A1.5 1.5 0 1 1 15 11.5A1.5 1.5 0 1 1 15 8.5Z"),
    /** Two photos, one behind the other: Albums. */
    ALBUMS("M7.5 3.5H19A1.5 1.5 0 0 1 20.5 5V16.5M3.5 8.5A1.5 1.5 0 0 1 5 7H15.5A1.5 1.5 0 0 1 17 8.5V19"
            + "A1.5 1.5 0 0 1 15.5 20.5H5A1.5 1.5 0 0 1 3.5 19ZM3.5 17L7.5 13L11 16.5L13 14.5L17 18.5"),
    /** A calendar page: Go to date (1.0.1). */
    CALENDAR("M4.5 7A1.5 1.5 0 0 1 6 5.5H18A1.5 1.5 0 0 1 19.5 7V18.5A1.5 1.5 0 0 1 18 20H6A1.5 1.5 0 0 1 4.5 18.5Z"
            + "M4.5 10H19.5M8.5 3.5V7M15.5 3.5V7"),
    CLOCK("M12 3.5A8.5 8.5 0 1 1 12 20.5A8.5 8.5 0 1 1 12 3.5ZM12 7.5V12L15 14"),
    /** A warning triangle: a file that can't be read. */
    WARNING("M12 4L21 19.5H3ZM12 10V14M12 17V17.01"),
    PLUS("M12 5V19M5 12H19"),
    /** Two chevrons pointing back: skip back 10 s. */
    SKIP_BACK("M11.5 7L6.5 12L11.5 17M18 7L13 12L18 17"),
    SKIP_FORWARD("M12.5 7L17.5 12L12.5 17M6 7L11 12L6 17"),
    /** A bar and a triangle pointing at it: the previous photo or video (not a skip within the video). */
    PREVIOUS_MEDIA("M6 5.5H8.5V18.5H6ZM18.5 5.5V18.5L10 12Z", true),
    NEXT_MEDIA("M15.5 5.5H18V18.5H15.5ZM5.5 5.5V18.5L14 12Z", true),
    /** A caption box with two lines of text: subtitles. */
    SUBTITLES("M3.5 6.5A2 2 0 0 1 5.5 4.5H18.5A2 2 0 0 1 20.5 6.5V17.5A2 2 0 0 1 18.5 19.5H5.5A2 2 0 0 1 3.5 17.5Z"
            + "M7 11H10M12.5 11H17M7 15H13.5M16 15H17"),
    /** Bars of a sound wave: the sound track. */
    SOUND_TRACK("M4 10.5V13.5M8 7.5V16.5M12 4.5V19.5M16 8.5V15.5M20 11V13"),
    /** Two arrows chasing each other: play again from the start at the end. */
    LOOP("M17 3.5L20 6.5L17 9.5M4 11.5V10A3.5 3.5 0 0 1 7.5 6.5H20M7 20.5L4 17.5L7 14.5M20 12.5V14A3.5 3.5 0 0 1"
            + " 16.5 17.5H4"),
    /** "i" in a circle: the Info panel. */
    INFO("M12 3.5A8.5 8.5 0 1 1 12 20.5A8.5 8.5 0 1 1 12 3.5ZM12 11V16.5M12 7.8V7.81"),
    /** An arrow out of a box: open in another app. */
    OPEN_EXTERNAL("M14 4H20V10M20 4L11.5 12.5M18 14V18.5A1.5 1.5 0 0 1 16.5 20H5.5A1.5 1.5 0 0 1 4 18.5V7.5"
            + "A1.5 1.5 0 0 1 5.5 6H10"),
    /** Three dots in a row: More (the file items). */
    MORE("M6 10.4A1.6 1.6 0 1 1 6 13.6A1.6 1.6 0 1 1 6 10.4ZM12 10.4A1.6 1.6 0 1 1 12 13.6A1.6 1.6 0 1 1 12 10.4Z"
            + "M18 10.4A1.6 1.6 0 1 1 18 13.6A1.6 1.6 0 1 1 18 10.4Z", true),
    /** A magnifier with a plus / minus: zoom in / out. */
    ZOOM_IN("M10.5 4A6.5 6.5 0 1 1 10.5 17A6.5 6.5 0 1 1 10.5 4ZM15.3 15.3L20 20M10.5 7.5V13.5M7.5 10.5H13.5"),
    ZOOM_OUT("M10.5 4A6.5 6.5 0 1 1 10.5 17A6.5 6.5 0 1 1 10.5 4ZM15.3 15.3L20 20M7.5 10.5H13.5"),
    /** Two overlapping sheets: Copy. */
    COPY("M9 9.5A1.5 1.5 0 0 1 10.5 8H18.5A1.5 1.5 0 0 1 20 9.5V18.5A1.5 1.5 0 0 1 18.5 20H10.5A1.5 1.5 0 0 1 9 18.5Z"
            + "M15 8V5.5A1.5 1.5 0 0 0 13.5 4H5.5A1.5 1.5 0 0 0 4 5.5V13.5A1.5 1.5 0 0 0 5.5 15H9"),
    /** A tick: a selected tile, Select all. */
    CHECK("M5.5 12.5L10 17L18.5 7.5"),
    /** A pencil: Rename. */
    RENAME("M4.5 19.5L5.3 15.7L15.6 5.4A2 2 0 0 1 18.6 8.4L8.3 18.7ZM13.8 7.2L16.8 10.2"),
    /** A keyboard: Settings › Keyboard shortcuts. */
    KEYBOARD("M3.5 7.5A2 2 0 0 1 5.5 5.5H18.5A2 2 0 0 1 20.5 7.5V16.5A2 2 0 0 1 18.5 18.5H5.5A2 2 0 0 1 3.5 16.5Z"
            + "M7 9.5H7.01M10.3 9.5H10.31M13.7 9.5H13.71M17 9.5H17.01M7 12.5H7.01M17 12.5H17.01M9.5 15.2H14.5"),
    /** A plug pulled out, crossed: a library folder that can't be reached right now. */
    OFFLINE("M8.5 3.5V7.5M15.5 3.5V7.5M6 7.5H18V10.5A6 6 0 0 1 6 10.5ZM12 16.5V20.5M4 4L20 20"),
    /** A folder with an arrow into it: Move to folder. */
    MOVE("M3.5 7.5A2 2 0 0 1 5.5 5.5H9.3L11.3 7.5H18.5A2 2 0 0 1 20.5 9.5V16.5A2 2 0 0 1 18.5 18.5H5.5"
            + "A2 2 0 0 1 3.5 16.5ZM8.5 13H15M12.5 10.5L15 13L12.5 15.5");

    /** The drawing grid's side: every path above is drawn inside 0..24. */
    public static final double GRID = 24;

    private final String path;
    private final boolean filled;

    Icon(String path) {
        this(path, false);
    }

    Icon(String path, boolean filled) {
        this.path = path;
        this.filled = filled;
    }

    /** The SVG path data, on the 24 × 24 grid. */
    public String path() {
        return path;
    }

    /** True for a solid shape (play, pause, a favorite's heart); otherwise a line drawing. */
    public boolean filled() {
        return filled;
    }

    /**
     * A new node showing this icon, {@code size} layout units square. The whole grid counts towards its size
     * (an invisible square), so icons line up the same whatever their shape.
     */
    public Node node(double size) {
        SVGPath shape = new SVGPath();
        shape.setContent(path);
        shape.getStyleClass().add("icon-path");
        if (filled) {
            shape.getStyleClass().add("icon-filled");
        }
        Rectangle grid = new Rectangle(GRID, GRID);
        grid.setFill(null);
        grid.setMouseTransparent(true);
        Group drawing = new Group(grid, shape);
        drawing.getTransforms().add(new Scale(size / GRID, size / GRID));
        Group icon = new Group(drawing); // its layout size includes the scale above
        icon.getStyleClass().addAll("icon", "icon-" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        return icon;
    }

    /** Shared pieces of the drawings above (an enum can't use its own constants in its constants). */
    private static final class Shapes {
        static final String HEART = "M12 19.5C12 19.5 3.5 14.6 3.5 9.1C3.5 6.6 5.4 4.75 7.8 4.75C9.6 4.75 11.1 5.8 12 7.3"
                + "C12.9 5.8 14.4 4.75 16.2 4.75C18.6 4.75 20.5 6.6 20.5 9.1C20.5 14.6 12 19.5 12 19.5Z";
        static final String EYE = "M2.5 12C4.6 7.8 8 5.5 12 5.5C16 5.5 19.4 7.8 21.5 12C19.4 16.2 16 18.5 12 18.5"
                + "C8 18.5 4.6 16.2 2.5 12ZM12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z";
        static final String SPEAKER = "M4 9.5H7.5L12 5.5V18.5L7.5 14.5H4Z";
    }
}
