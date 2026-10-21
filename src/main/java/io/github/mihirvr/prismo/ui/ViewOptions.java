package io.github.mihirvr.prismo.ui;

/**
 * The shared Filters + Sort choice, applied the same way to the Timeline, search results and the Collections
 * pages, plus the Settings switch "Show files hidden in File Explorer" (P6), which every view applies the same way
 * too: carried here, a change reloads every view like a new filter does.
 *
 * <p>The SQL pieces come only from the fixed text in {@link MediaFilter} and {@link MediaSort}, never from
 * user input.
 *
 * @param descending    true = newest / largest / Z first
 * @param osHiddenShown true = files with the operating system's Hidden mark show like any other file; false (the
 *                      default) = only on Collections › Hidden ({@link MediaScope#sqlCondition})
 */
public record ViewOptions(MediaFilter filter, MediaSort sort, boolean descending, boolean osHiddenShown) {

    /** Everything, date taken, newest first: the Timeline as it was before Filters and Sort existed. */
    public static final ViewOptions DEFAULT = new ViewOptions(MediaFilter.ALL, MediaSort.DATE_TAKEN, true, false);

    /** With the File Explorer switch off (the default). */
    public ViewOptions(MediaFilter filter, MediaSort sort, boolean descending) {
        this(filter, sort, descending, false);
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    /** True when the view shows date headers (a date sort). */
    public boolean grouped() {
        return sort.isDate();
    }

    /** The filter's SQL condition, or null when everything is shown. */
    public String sqlCondition() {
        return filter.condition();
    }

    /**
     * The ORDER BY list: the sort, then {@code id} in the same direction, so equal values always come out in
     * the same order.
     *
     * <p>A date-taken sort is written {@code +sort_date}. The unary {@code +} gives the same value but isn't
     * the bare column, so SQLite can't use {@code idx_media_sort_date} for the order: it reads the table
     * straight through and sorts the result, instead of walking the index and fetching every row one at a
     * time in date order. Measured in #7 (synthetic, 100,000 rows): the whole Timeline 250–262 ms against
     * 718–739 ms for the walk; a Videos filter 48 against 520 ms; a 2-letter search 35 against 639 ms. Only
     * {@code sort_date} has an index, so the other sorts read the table either way.
     */
    public String sqlOrderBy() {
        String direction = descending ? " DESC" : " ASC";
        String prefix = sort == MediaSort.DATE_TAKEN ? "+" : "";
        return prefix + sort.expression() + direction + ", id" + direction;
    }

    public ViewOptions withFilter(MediaFilter newFilter) {
        return new ViewOptions(newFilter, sort, descending, osHiddenShown);
    }

    /** A new sort starts in its natural direction (see {@link MediaSort#descendingByDefault()}). */
    public ViewOptions withSort(MediaSort newSort) {
        return new ViewOptions(filter, newSort, newSort.descendingByDefault(), osHiddenShown);
    }

    public ViewOptions withDescending(boolean newDescending) {
        return new ViewOptions(filter, sort, newDescending, osHiddenShown);
    }

    public ViewOptions withOsHiddenShown(boolean shown) {
        return new ViewOptions(filter, sort, descending, shown);
    }

    /**
     * The Filters + Sort as a setting, kept across restarts (P7): {@code "PHOTOS,NAME,asc"}. The File Explorer switch
     * has its own setting, so it isn't part of this.
     */
    public String toSetting() {
        return filter.name() + "," + sort.name() + "," + (descending ? "desc" : "asc");
    }

    /** The Filters + Sort saved by {@link #toSetting}; missing or unreadable (an older or edited value): the default. */
    public static ViewOptions fromSetting(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String[] parts = value.split(",");
        if (parts.length != 3 || !(parts[2].equals("desc") || parts[2].equals("asc"))) {
            return DEFAULT;
        }
        try {
            return new ViewOptions(MediaFilter.valueOf(parts[0]), MediaSort.valueOf(parts[1]), parts[2].equals("desc"));
        } catch (IllegalArgumentException e) {
            return DEFAULT; // a filter or sort that no longer exists
        }
    }

    /**
     * True if both show the same list in the same order, apart from the File Explorer switch: a view then stays
     * where it is on a reload (only which items are in it changed), instead of starting from the top.
     */
    public boolean sameOrder(ViewOptions other) {
        return other != null && withOsHiddenShown(false).equals(other.withOsHiddenShown(false));
    }
}
