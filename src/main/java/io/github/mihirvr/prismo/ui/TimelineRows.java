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
