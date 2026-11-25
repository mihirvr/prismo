package io.github.mihirvr.prismo.ui;

import java.util.List;

/** One line of the Timeline's list: a date header, or a row of thumbnails. */
sealed interface TimelineRow {

    /** "October 2026", and how many items the group has. */
    record Header(String label, int count) implements TimelineRow {
    }

    /**
     * 1.4.2, Duplicates: a smaller header under a date header, one per file ("IMG_1234.jpg · 4.2 MB each"), its copies
     * under it.
     */
    record SubHeader(String label, int count) implements TimelineRow {
    }

    /** A header of either level. */
    static boolean isHeading(TimelineRow row) {
        return row instanceof Header || row instanceof SubHeader;
    }

    /** Up to one row's worth of items (the last row of a group can be shorter). */
    record Thumbs(List<TimelineItem> items) implements TimelineRow {
    }
}
