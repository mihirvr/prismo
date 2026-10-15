package io.github.mihirvr.prismo.ui;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * "Smart" Timeline grouping: Today, Yesterday, then one group per month.
 *
 * <p>Works on whole days counted from 1970-01-01 ("epoch days"). A {@code sort_date} is floating
 * wall-clock time stored as if it were UTC, so its day is a plain division, with no time-zone
 * conversion: a photo taken at 23:30 stays on that day wherever the app is opened.
 */
final class DateGroups {

    static final long MILLIS_PER_DAY = 86_400_000L;
    private static final long TODAY = -1;
    private static final long YESTERDAY = -2;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private DateGroups() {
    }

    /** The day a {@code sort_date} falls on. {@code floorDiv}, so photos from before 1970 land on the right day. */
    static long dayOf(long sortDate) {
        return Math.floorDiv(sortDate, MILLIS_PER_DAY);
    }

    /**
     * A number that is the same for every day in one group: two special values for today and yesterday,
     * otherwise year × 12 + month. Comparing numbers is much cheaper than building a label per photo.
     */
    static long groupOf(long day, long today) {
        if (day == today) {
            return TODAY;
        }
        if (day == today - 1) {
            return YESTERDAY;
        }
        LocalDate date = LocalDate.ofEpochDay(day);
        return date.getYear() * 12L + date.getMonthValue() - 1;
    }

    /** The header text for a group from {@link #groupOf}: "Today", "Yesterday" or "October 2026". */
    static String labelOf(long group) {
        if (group == TODAY) {
            return "Today";
        }
        if (group == YESTERDAY) {
            return "Yesterday";
        }
        return MONTH.format(YearMonth.of((int) Math.floorDiv(group, 12), Math.floorMod(group, 12) + 1));
    }
}
