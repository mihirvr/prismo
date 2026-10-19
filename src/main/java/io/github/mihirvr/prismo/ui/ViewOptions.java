package io.github.mihirvr.prismo.ui;

/**
 * The shared Filters + Sort choice, applied the same way to the Timeline and to search results.
 *
 * <p>The SQL pieces come only from the fixed text in {@link MediaFilter} and {@link MediaSort}, never from
 * user input.
 *
 * @param descending true = newest / largest / Z first
 */
public record ViewOptions(MediaFilter filter, MediaSort sort, boolean descending) {

    /** Everything, date taken, newest first: the Timeline as it was before Filters and Sort existed. */
    public static final ViewOptions DEFAULT = new ViewOptions(MediaFilter.ALL, MediaSort.DATE_TAKEN, true);

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
        return new ViewOptions(newFilter, sort, descending);
    }

    /** A new sort starts in its natural direction (see {@link MediaSort#descendingByDefault()}). */
    public ViewOptions withSort(MediaSort newSort) {
        return new ViewOptions(filter, newSort, newSort.descendingByDefault());
    }

    public ViewOptions withDescending(boolean newDescending) {
        return new ViewOptions(filter, sort, newDescending);
    }
}
