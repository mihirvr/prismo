package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 1.4.2, Duplicates: a file's copies stay together under one date, with or without a header per file. */
class CopyRowsTest {

    private static final long TODAY = LocalDate.of(2026, 1, 1).toEpochDay();

    private static TimelineItem item(long id, LocalDate day) {
        return new TimelineItem(new MediaRef(id, Path.of("p" + id + ".jpg"), "jpg", 0), day.toEpochDay() * 86_400_000L,
                0, 0, false, false, false);
    }

    /**
     * Newest first, as a date sort gives them: A's copies are on 7 May and 1 April (a WhatsApp copy keeps its own
     * date), B's both on 7 May, C's on 1 April.
     */
    private static final List<TimelineItem> ITEMS = List.of(
            item(1, LocalDate.of(2024, 5, 7)), item(2, LocalDate.of(2024, 5, 7)), item(3, LocalDate.of(2024, 5, 7)),
            item(4, LocalDate.of(2022, 4, 1)), item(5, LocalDate.of(2022, 4, 1)), item(6, LocalDate.of(2022, 4, 1)));
    private static final Map<Long, String> FILE = Map.of(1L, "A", 2L, "B", 3L, "B", 4L, "A", 5L, "C", 6L, "C");

    private static List<FolderSections.Section> files() {
        return TimelineRows.files(ITEMS, item -> FILE.get(item.ref().id()), copies -> "file of " + copies.size());
    }

    private static List<String> describe(List<TimelineRow> rows) {
        List<String> out = new ArrayList<>();
        for (TimelineRow row : rows) {
            out.add(switch (row) {
                case TimelineRow.Header(String label, int count) -> "H " + label + " " + count;
                case TimelineRow.SubHeader(String label, int count) -> "  S " + label + " " + count;
                case TimelineRow.Thumbs(List<TimelineItem> items) ->
                        "    " + items.stream().map(i -> String.valueOf(i.ref().id())).toList();
            });
        }
        return out;
    }

    @Test
    void aFilesCopiesAreNeverSplit() {
        List<FolderSections.Section> files = files();
        assertEquals(3, files.size());
        assertEquals(List.of(1L, 4L), files.get(0).items().stream().map(i -> i.ref().id()).toList(),
                "A's April copy moves up to its first copy");
        assertEquals("file of 2", files.get(0).label());
    }

    @Test
    void datesAndFiles() {
        assertEquals(List.of(
                "H Tuesday, 7 May 2024 4",
                "  S file of 2 2", "    [1, 4]",
                "  S file of 2 2", "    [2, 3]",
                "H Friday, 1 April 2022 2",
                "  S file of 2 2", "    [5, 6]"),
                describe(TimelineRows.byCopies(files(), 6, TODAY, Grouping.DAY, CopyLayout.DATES_AND_FILES)));
    }

    @Test
    void datesOnlyKeepsEachFileTogether() {
        assertEquals(List.of(
                "H Tuesday, 7 May 2024 4", "    [1, 4, 2]", "    [3]",
                "H Friday, 1 April 2022 2", "    [5, 6]"),
                describe(TimelineRows.byCopies(files(), 3, TODAY, Grouping.DAY, CopyLayout.DATES)));
    }

    @Test
    void filesOnly() {
        assertEquals(List.of(
                "H file of 2 2", "    [1, 4]", "H file of 2 2", "    [2, 3]", "H file of 2 2", "    [5, 6]"),
                describe(TimelineRows.byCopies(files(), 6, TODAY, Grouping.DAY, CopyLayout.FILES)));
    }

    @Test
    void savedChoiceReadsBack() {
        assertEquals(CopyLayout.DATES, CopyLayout.fromSetting(CopyLayout.DATES.settingValue()));
        assertEquals(CopyLayout.DATES_AND_FILES, CopyLayout.fromSetting("something else"));
    }
}
