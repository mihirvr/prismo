package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScrubLabelTest {

    private static final TimelineRow THUMBS = new TimelineRow.Thumbs(List.of());

    @Test
    void theFractionOfTheBarPicksARowByHeight() {
        // header A (48), 2 rows (100 each), header B (48), 1 row (100): 396 in all
        List<TimelineRow> rows = List.of(new TimelineRow.Header("A", 8), THUMBS, THUMBS,
                new TimelineRow.Header("B", 3), THUMBS);
        assertEquals(0, ScrubLabel.rowAt(rows, 100, 48, 0));
        assertEquals(1, ScrubLabel.rowAt(rows, 100, 48, 0.3));
        assertEquals(3, ScrubLabel.rowAt(rows, 100, 48, 0.7));
        assertEquals(4, ScrubLabel.rowAt(rows, 100, 48, 1));
        assertEquals("A", ScrubLabel.labelFor(rows, 2, null), "the header above the row");
        assertEquals("B", ScrubLabel.labelFor(rows, 4, null));
    }

    @Test
    void withoutHeadersTheFallbackDecides() {
        List<TimelineRow> rows = List.of(THUMBS, THUMBS);
        assertNull(ScrubLabel.labelFor(rows, 1, null));
        assertEquals("row 1", ScrubLabel.labelFor(rows, 1, row -> "row " + row));
    }

    @Test
    void theTileTooltipIsTheNameThenAShortFolder() {
        String s = File.separator;
        assertEquals("a.jpg\n…" + s + "Goa" + s + "Day 1",
                ThumbnailTile.tooltipText(Path.of("Photos", "2024", "Goa", "Day 1", "a.jpg")));
        assertEquals("a.jpg\nPhotos" + s + "Goa", ThumbnailTile.tooltipText(Path.of("Photos", "Goa", "a.jpg")));
        assertEquals("a.jpg", ThumbnailTile.tooltipText(Path.of("a.jpg")));
    }
}
