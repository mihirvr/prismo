package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Go to date and middle-click auto-scroll (1.0.1): where a jump lands, and how fast auto-scroll goes. */
class DateJumpTest {

    /** One item per day given, in that order; sortDate at noon, as the Timeline stores dates. */
    private static List<TimelineItem> itemsOn(String... days) {
        List<TimelineItem> items = new ArrayList<>();
        for (int i = 0; i < days.length; i++) {
            long noon = LocalDate.parse(days[i]).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
            items.add(new TimelineItem(new MediaRef(i + 1, Path.of(i + ".jpg"), "jpg", 0), noon, 1, 1, false, false,
                    false, 0, 0));
        }
        return items;
    }

    private static long day(String date) {
        return LocalDate.parse(date).toEpochDay();
    }

    @Test
    void newestFirstAJumpLandsOnTheDayOrTheNextOlderOne() {
        List<TimelineItem> items = itemsOn("2026-05-10", "2026-05-10", "2026-03-02", "2025-12-25");

        assertEquals(0, TimelineView.jumpIndex(items, day("2026-05-10")), "the first item of that day");
        assertEquals(2, TimelineView.jumpIndex(items, day("2026-04-01")), "an empty day: the next older one");
        assertEquals(0, TimelineView.jumpIndex(items, day("2027-01-01")), "after the newest: the top");
        assertEquals(3, TimelineView.jumpIndex(items, day("2020-01-01")), "before the oldest: the last item");
    }

    @Test
    void oldestFirstAJumpLandsOnTheDayOrTheNextNewerOne() {
        List<TimelineItem> items = itemsOn("2025-12-25", "2026-03-02", "2026-05-10", "2026-05-10");

        assertEquals(2, TimelineView.jumpIndex(items, day("2026-05-10")));
        assertEquals(1, TimelineView.jumpIndex(items, day("2026-01-01")), "an empty day: the next newer one");
        assertEquals(3, TimelineView.jumpIndex(items, day("2027-01-01")), "after the newest: the last item");
    }

    @Test
    void autoScrollWaitsOutTheDeadZoneThenGoesFasterTheFurtherThePointerIs() {
        assertEquals(0, AutoScroll.pixelsPerFrame(0));
        assertEquals(0, AutoScroll.pixelsPerFrame(AutoScroll.DEAD_ZONE), "a still hand doesn't scroll");
        double near = AutoScroll.pixelsPerFrame(AutoScroll.DEAD_ZONE + 20);
        double far = AutoScroll.pixelsPerFrame(AutoScroll.DEAD_ZONE + 200);
        assertTrue(near > 0 && far > near, near + " then " + far);
        assertEquals(-far, AutoScroll.pixelsPerFrame(-(AutoScroll.DEAD_ZONE + 200)), "above the click: up");
        assertTrue(AutoScroll.pixelsPerFrame(100_000) <= 120, "with a limit");
    }
}
