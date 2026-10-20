package io.github.mihirvr.prismo.ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Timeline grouping ({@link Grouping}): Smart (Today, Yesterday, then one group per month, the default), or one
 * group per day (with Today / Yesterday), week (Monday first), month or year.
 *
 * <p>Works on whole days counted from 1970-01-01 ("epoch days"). A {@code sort_date} is floating
 * wall-clock time stored as if it were UTC, so its day is a plain division, with no time-zone
 * conversion: a photo taken at 23:30 stays on that day wherever the app is opened.
 */
final class DateGroups {

    static final long MILLIS_PER_DAY = 86_400_000L;
    /** Special group numbers far below any real day (a real day before 1970 is negative too). */
    private static final long TODAY = Long.MIN_VALUE;
    private static final long YESTERDAY = Long.MIN_VALUE + 1;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private DateGroups() {
    }

    /** The day a {@code sort_date} falls on. {@code floorDiv}, so photos from before 1970 land on the right day. */
    static long dayOf(long sortDate) {
        return Math.floorDiv(sortDate, MILLIS_PER_DAY);
    }

    /** {@link #groupOf(long, long, Grouping)} with Smart grouping. */
    static long groupOf(long day, long today) {
        return groupOf(day, today, Grouping.SMART);
    }

    /**
     * A number that is the same for every day in one group: two special values for today and yesterday (Smart
     * and Day), otherwise year × 12 + month, the day, the week's Monday or the year. Comparing numbers is much
     * cheaper than building a label per photo.
     */
    static long groupOf(long day, long today, Grouping grouping) {
        boolean recent = grouping == Grouping.SMART || grouping == Grouping.DAY;
        if (recent && day == today) {
            return TODAY;
        }
        if (recent && day == today - 1) {
            return YESTERDAY;
        }
        LocalDate date = LocalDate.ofEpochDay(day);
        return switch (grouping) {
            case SMART, MONTH -> date.getYear() * 12L + date.getMonthValue() - 1;
            case DAY -> day;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
            case YEAR -> date.getYear();
        };
    }

    /** {@link #labelOf(long, Grouping)} with Smart grouping. */
    static String labelOf(long group) {
        return labelOf(group, Grouping.SMART);
    }

    /**
     * The header text for a group from {@link #groupOf}: "Today", "Yesterday", "October 2026", "Tuesday,
     * 20 October 2026", "Week of 19 October 2026" or "2026".
     */
    static String labelOf(long group, Grouping grouping) {
        if (group == TODAY) {
            return "Today";
        }
        if (group == YESTERDAY) {
            return "Yesterday";
        }
        return switch (grouping) {
            case SMART, MONTH -> MONTH.format(YearMonth.of((int) Math.floorDiv(group, 12),
                    Math.floorMod(group, 12) + 1));
            case DAY -> DAY.format(LocalDate.ofEpochDay(group));
            case WEEK -> "Week of " + WEEK.format(LocalDate.ofEpochDay(group));
            case YEAR -> Long.toString(group);
        };
    }
}
