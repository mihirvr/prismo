package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DateGroupsTest {

    @Test
    void dayIsTheWallClockDayWithNoTimeZoneShift() {
        // 23:30 must stay on the 15th: a floating time is stored as if it were UTC, so no zone is applied.
        assertEquals(LocalDate.of(2026, 10, 15).toEpochDay(), DateGroups.dayOf(floating(2026, 10, 15, 23, 30)));
        assertEquals(LocalDate.of(2026, 10, 15).toEpochDay(), DateGroups.dayOf(floating(2026, 10, 15, 0, 0)));
    }

    @Test
    void photosFromBefore1970LandOnTheRightDay() {
        assertEquals(LocalDate.of(1969, 12, 31).toEpochDay(), DateGroups.dayOf(floating(1969, 12, 31, 23, 0)));
    }

    @Test
    void todayYesterdayThenMonths() {
        long today = LocalDate.of(2026, 10, 15).toEpochDay();

        assertEquals("Today", label(LocalDate.of(2026, 10, 15), today));
        assertEquals("Yesterday", label(LocalDate.of(2026, 10, 14), today));
        assertEquals("October 2026", label(LocalDate.of(2026, 10, 13), today));
        assertEquals("October 2026", label(LocalDate.of(2026, 10, 1), today));
        assertEquals("September 2026", label(LocalDate.of(2026, 9, 30), today));
        assertEquals("October 2025", label(LocalDate.of(2025, 10, 15), today), "same month, other year");
        assertEquals("October 2026", label(LocalDate.of(2026, 10, 20), today), "a future date gets its month");
    }

    @Test
    void yesterdayWorksAcrossMonthAndYearEnds() {
        assertEquals("Yesterday", label(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1).toEpochDay()));
        assertEquals("October 2026", label(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 1).toEpochDay()));
        assertEquals("Yesterday", label(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1).toEpochDay()));
    }

    @Test
    void daysInTheSameMonthShareAGroup() {
        long today = LocalDate.of(2026, 10, 15).toEpochDay();
        assertEquals(DateGroups.groupOf(LocalDate.of(2026, 8, 1).toEpochDay(), today),
                DateGroups.groupOf(LocalDate.of(2026, 8, 31).toEpochDay(), today));
    }

    private static String label(LocalDate day, long today) {
        return DateGroups.labelOf(DateGroups.groupOf(day.toEpochDay(), today));
    }

    /** A wall-clock time stored the way the indexer stores date taken: as if it were UTC. */
    static long floating(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
