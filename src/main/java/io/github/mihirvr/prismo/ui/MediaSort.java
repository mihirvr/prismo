package io.github.mihirvr.prismo.ui;

/**
 * The shared Sort (PROJECT.md "Sort"): what a view is ordered by. The direction is separate
 * ({@link ViewOptions#descending()}).
 *
 * <p>The three date sorts keep date headers, grouped by that same date. The others show a plain grid.
 */
public enum MediaSort {
    /** {@code sort_date}: date taken, or the modified time when there is none (the Timeline's order). */
    DATE_TAKEN("Date taken", "sort_date", Dates.FLOATING, true, "Newest first", "Oldest first"),
    DATE_MODIFIED("Date modified", "date_modified", Dates.INSTANT, true, "Newest first", "Oldest first"),
    DATE_ADDED("Date added", "date_added", Dates.INSTANT, true, "Newest first", "Oldest first"),
    NAME("Name", "file_name COLLATE NOCASE", Dates.NONE, false, "Z to A", "A to Z"),
    SIZE("Size", "size_bytes", Dates.NONE, true, "Largest first", "Smallest first"),
    /** Pixel count; unknown sizes count as 0. */
    RESOLUTION("Resolution", "coalesce(width * height, 0)", Dates.NONE, true, "Largest first", "Smallest first");

    /** What kind of date this sort orders by, which decides the date headers. */
    enum Dates {
        /** {@code sort_date}: the camera's wall-clock time stored as if it were UTC (see #3); grouped as is. */
        FLOATING,
        /** A real moment (epoch ms); turned into this computer's local wall-clock time before grouping. */
        INSTANT,
        /** Not a date: no headers. */
        NONE
    }

    private final String label;
    private final String expression;
    private final Dates dates;
    private final boolean descendingByDefault;
    private final String descendingLabel;
    private final String ascendingLabel;

    MediaSort(String label, String expression, Dates dates, boolean descendingByDefault, String descendingLabel,
            String ascendingLabel) {
        this.label = label;
        this.expression = expression;
        this.dates = dates;
        this.descendingByDefault = descendingByDefault;
        this.descendingLabel = descendingLabel;
        this.ascendingLabel = ascendingLabel;
    }

    /** The SQL expression to order by (fixed text, never user input). */
    String expression() {
        return expression;
    }

    Dates dates() {
        return dates;
    }

    /** True for the date sorts, which show date headers. */
    public boolean isDate() {
        return dates != Dates.NONE;
    }

    /** The direction a sort starts in when picked: newest / largest first, but names A to Z. */
    public boolean descendingByDefault() {
        return descendingByDefault;
    }

    /** "Newest first", "A to Z"…: what the direction button says. */
    public String directionLabel(boolean descending) {
        return descending ? descendingLabel : ascendingLabel;
    }

    @Override
    public String toString() {
        return label; // what the sort menu shows
    }
}
