package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DragBoxTest {

    private static final double CELL = 100;
    private static final double GAP = 4;

    private static TimelineItem item(long id) {
        return new TimelineItem(new MediaRef(id, Path.of(id + ".jpg"), "jpg", 0), 0, 0, 0, false, false, false);
    }

    private static TimelineRow.Thumbs row(long... ids) {
        List<TimelineItem> items = new ArrayList<>();
        for (long id : ids) {
            items.add(item(id));
        }
        return new TimelineRow.Thumbs(items);
    }

    /** Header, 3 full rows of 3, a header, a short row. */
    private static final List<TimelineRow> ROWS = List.of(
            new TimelineRow.Header("May", 9), row(1, 2, 3), row(4, 5, 6), row(7, 8, 9),
            new TimelineRow.Header("April", 1), row(10));

    private static List<Long> hits(int row1, double y1, int row2, double y2, double x1, double x2) {
        return DragBox.hits(ROWS, new DragBox.Corner(row1, y1), new DragBox.Corner(row2, y2), x1, x2, CELL, GAP);
    }

    @Test
    void aBoxInsideOneRowTouchesItsTiles() {
        assertEquals(List.of(2L, 3L), hits(1, 10, 1, 50, 150, 260));
    }

    @Test
    void rowsBetweenTheCornersAreTouchedWhole() {
        assertEquals(List.of(1L, 4L, 7L), hits(0, 5, 3, 40, -10, 50), "from the header, down the first column");
    }

    @Test
    void eitherDirectionGivesTheSame() {
        assertEquals(hits(1, 20, 3, 20, 50, 160), hits(3, 20, 1, 20, 160, 50));
    }

    @Test
    void theGapsAloneTouchNothing() {
        assertEquals(List.of(), hits(1, 101, 1, 103, 0, 300), "the gap under a row's tiles");
        assertEquals(List.of(), hits(1, 10, 1, 50, 101, 103), "the gap between two columns");
    }

    @Test
    void aShortRowHasNoTileUnderEmptySpace() {
        assertEquals(List.of(), hits(5, 10, 5, 50, 200, 300), "right of the last tile");
        assertEquals(List.of(8L, 9L), hits(3, 50, 5, 50, 150, 300), "the short row's one tile is left of the box");
    }

    @Test
    void pastTheLastRowCountsTheRowsAbove() {
        List<Long> all = hits(3, 50, 99, 10, 0, 1000);
        assertEquals(List.of(7L, 8L, 9L, 10L), all);
    }

    @Test
    void aboveTheFirstRowOnScreen() {
        assertEquals(List.of(1L), hits(1, -40, 1, 20, 0, 10), "a negative height covers the row from its top");
        assertEquals(List.of(), hits(2, -40, 2, -10, 0, 300), "both corners above the row: nothing of it");
    }
}
