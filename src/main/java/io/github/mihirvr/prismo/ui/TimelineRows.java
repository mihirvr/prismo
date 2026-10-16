package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;

/** Turns the Timeline's items into list rows. Pure functions: no JavaFX, so they're unit-tested directly. */
final class TimelineRows {

    private TimelineRows() {
    }

    /**
     * Splits {@code items} (newest first) into groups, and each group into rows of {@code columns}.
     * Every group starts with a {@link TimelineRow.Header}.
     *
     * @param today the current day ({@link DateGroups#dayOf} units), for "Today" / "Yesterday"
     */
    static List<TimelineRow> build(List<TimelineItem> items, int columns, long today) {
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be at least 1: " + columns);
        }
        List<TimelineRow> rows = new ArrayList<>(items.size() / columns + 16);
        int start = 0;
        while (start < items.size()) {
            long group = DateGroups.groupOf(DateGroups.dayOf(items.get(start).sortDate()), today);
            int end = start + 1;
            while (end < items.size() && DateGroups.groupOf(DateGroups.dayOf(items.get(end).sortDate()), today) == group) {
                end++;
            }
            rows.add(new TimelineRow.Header(DateGroups.labelOf(group), end - start));
            for (int from = start; from < end; from += columns) {
                // A view into the caller's list, not a copy: the caller must not change the list afterwards.
                rows.add(new TimelineRow.Thumbs(items.subList(from, Math.min(from + columns, end))));
            }
            start = end;
        }
        return rows;
    }

    /**
     * Arrow Up / Down on the Timeline: the item in the same column of the next thumbnail row above
     * ({@code direction} −1) or below (+1), skipping headers. A shorter row gives its last item.
     *
     * @return that item's media id, or -1 if {@code id} isn't in the rows or there is no row that way
     */
    static long verticalNeighbour(List<TimelineRow> rows, long id, int direction) {
        int row = rowOf(rows, id);
        if (row < 0) {
            return -1;
        }
        List<TimelineItem> from = ((TimelineRow.Thumbs) rows.get(row)).items();
        int column = 0;
        while (from.get(column).ref().id() != id) {
            column++;
        }
        for (int r = row + direction; r >= 0 && r < rows.size(); r += direction) {
            if (rows.get(r) instanceof TimelineRow.Thumbs(List<TimelineItem> items)) {
                return items.get(Math.min(column, items.size() - 1)).ref().id();
            }
        }
        return -1;
    }

    /** Index of the thumbnail row holding media {@code id}, or -1. */
    static int rowOf(List<TimelineRow> rows, long id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof TimelineRow.Thumbs(List<TimelineItem> items)) {
                for (TimelineItem item : items) {
                    if (item.ref().id() == id) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }
}
