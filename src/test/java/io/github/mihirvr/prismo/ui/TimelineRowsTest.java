package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineRowsTest {

    private static final long TODAY = LocalDate.of(2026, 10, 15).toEpochDay();
    private long nextId = 1;

    @Test
    void eachGroupGetsAHeaderThenRowsOfColumns() {
        List<TimelineItem> items = new ArrayList<>();
        items.addAll(on(2026, 10, 15, 2));
        items.addAll(on(2026, 10, 14, 1));
        items.addAll(on(2026, 9, 20, 3));
        items.addAll(on(2026, 9, 2, 2));

        List<TimelineRow> rows = TimelineRows.build(items, 2, TODAY);

        assertEquals(List.of("H Today 2", "T 2", "H Yesterday 1", "T 1", "H September 2026 5", "T 2", "T 2", "T 1"),
                describe(rows));
    }

    @Test
    void rowsKeepTheItemsInOrder() {
        List<TimelineItem> items = on(2026, 9, 1, 5);

        List<TimelineRow> rows = TimelineRows.build(items, 3, TODAY);

        List<TimelineItem> flattened = rows.stream()
                .filter(TimelineRow.Thumbs.class::isInstance)
                .flatMap(row -> ((TimelineRow.Thumbs) row).items().stream())
                .toList();
        assertEquals(items, flattened);
    }

    @Test
    void moreColumnsMeansFewerRows() {
        List<TimelineItem> items = on(2026, 9, 1, 10);

        assertEquals(1 + 10, TimelineRows.build(items, 1, TODAY).size());
        assertEquals(1 + 4, TimelineRows.build(items, 3, TODAY).size());
        assertEquals(1 + 1, TimelineRows.build(items, 10, TODAY).size());
    }

    @Test
    void emptyLibraryHasNoRows() {
        assertTrue(TimelineRows.build(List.of(), 4, TODAY).isEmpty());
    }

    @Test
    void zeroColumnsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TimelineRows.build(List.of(), 0, TODAY));
    }

    @Test
    void rowOfFindsTheRowHoldingAnItem() {
        List<TimelineItem> items = new ArrayList<>(on(2026, 10, 15, 3)); // ids 1-3
        items.addAll(on(2026, 8, 1, 3)); // ids 4-6
        List<TimelineRow> rows = TimelineRows.build(items, 2, TODAY);
        // H Today, [1 2], [3], H August, [4 5], [6]

        assertEquals(1, TimelineRows.rowOf(rows, 2));
        assertEquals(2, TimelineRows.rowOf(rows, 3));
        assertEquals(5, TimelineRows.rowOf(rows, 6));
        assertEquals(-1, TimelineRows.rowOf(rows, 99));
    }

    private List<TimelineItem> on(int year, int month, int day, int count) {
        List<TimelineItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = nextId++;
            // Same day, slightly earlier each time: newest first, like the query returns them.
            long sortDate = DateGroupsTest.floating(year, month, day, 12, 0) - i * 60_000L;
            items.add(new TimelineItem(new MediaRef(id, Path.of(id + ".jpg"), "jpg", 0), sortDate));
        }
        return items;
    }

    private static List<String> describe(List<TimelineRow> rows) {
        return rows.stream().map(row -> switch (row) {
            case TimelineRow.Header(String label, int count) -> "H " + label + " " + count;
            case TimelineRow.Thumbs(List<TimelineItem> items) -> "T " + items.size();
        }).toList();
    }
}
