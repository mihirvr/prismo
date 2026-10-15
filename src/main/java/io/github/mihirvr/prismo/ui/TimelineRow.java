package io.github.mihirvr.prismo.ui;

import java.util.List;

/** One line of the Timeline's list: a date header, or a row of thumbnails. */
sealed interface TimelineRow {

    /** "October 2026", and how many items the group has. */
    record Header(String label, int count) implements TimelineRow {
    }

    /** Up to one row's worth of items (the last row of a group can be shorter). */
    record Thumbs(List<TimelineItem> items) implements TimelineRow {
    }
}
