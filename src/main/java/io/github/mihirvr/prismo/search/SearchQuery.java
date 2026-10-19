package io.github.mihirvr.prismo.search;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What was typed in the search box, split into words. Pure: no database, no JavaFX.
 *
 * <p>Every word must match (AND). A word matches by its text: any part of the file name, the folder path or
 * the camera make/model. A word that looks like a date or a resolution can also match that field instead
 * (OR), so {@code 2024} finds photos taken in 2024 <i>and</i> files with "2024" in their name:
 * <ul>
 *   <li>{@code 2024} (a year from 1900 to 2099), {@code 2024-10}, {@code 2024-10-05}: taken in that year /
 *       month / day;</li>
 *   <li>{@code october}, {@code oct}: taken in that month of any year, so {@code oct 2024} = October 2024
 *       (English month names only);</li>
 *   <li>{@code 4000x3000} (or {@code 4000×3000}): exactly that width and height.</li>
 * </ul>
 * "Taken" means {@code media.sort_date}: the date taken, or the modified time when there is none.
 *
 * @param words in the order typed; empty for a blank search
 */
public record SearchQuery(List<Word> words) {

    /** Shortest word the trigram index can find: it indexes 3-character sequences. */
    static final int MIN_INDEXED_LENGTH = 3;

    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d\\d");
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})-(\\d{1,2})");
    private static final Pattern YEAR_MONTH_DAY = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern RESOLUTION = Pattern.compile("(\\d{1,5})[x×](\\d{1,5})", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("jan", 1),
            Map.entry("february", 2), Map.entry("feb", 2),
            Map.entry("march", 3), Map.entry("mar", 3),
            Map.entry("april", 4), Map.entry("apr", 4),
            Map.entry("may", 5),
            Map.entry("june", 6), Map.entry("jun", 6),
            Map.entry("july", 7), Map.entry("jul", 7),
            Map.entry("august", 8), Map.entry("aug", 8),
            Map.entry("september", 9), Map.entry("sep", 9), Map.entry("sept", 9),
            Map.entry("october", 10), Map.entry("oct", 10),
            Map.entry("november", 11), Map.entry("nov", 11),
            Map.entry("december", 12), Map.entry("dec", 12));

    public SearchQuery {
        words = List.copyOf(words);
    }

    /** Splits on whitespace; a blank text gives an empty query. */
    public static SearchQuery parse(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return new SearchQuery(List.of());
        }
        return new SearchQuery(Arrays.stream(trimmed.split("\\s+")).map(SearchQuery::word).toList());
    }

    public boolean isEmpty() {
        return words.isEmpty();
    }

    static Word word(String text) {
        return new Word(text, dateOf(text), resolutionOf(text));
    }

    private static DateMatch dateOf(String text) {
        Integer month = MONTHS.get(text.toLowerCase(Locale.ROOT));
        if (month != null) {
            return new MonthOfYear(month);
        }
        try {
            if (YEAR.matcher(text).matches()) {
                LocalDate first = LocalDate.of(Integer.parseInt(text), 1, 1);
                return Range.of(first, first.plusYears(1));
            }
            Matcher m = YEAR_MONTH.matcher(text);
            if (m.matches()) {
                LocalDate first = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1);
                return Range.of(first, first.plusMonths(1));
            }
            m = YEAR_MONTH_DAY.matcher(text);
            if (m.matches()) {
                LocalDate day = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
                return Range.of(day, day.plusDays(1));
            }
        } catch (DateTimeException e) {
            // "2024-13" or "2024-02-30": not a date, just text
        }
        return null;
    }

    private static Resolution resolutionOf(String text) {
        Matcher m = RESOLUTION.matcher(text);
        if (!m.matches()) {
            return null;
        }
        int width = Integer.parseInt(m.group(1));
        int height = Integer.parseInt(m.group(2));
        return width > 0 && height > 0 ? new Resolution(width, height) : null;
    }

    /**
     * One word of the search.
     *
     * @param text as typed (matching ignores case anyway)
     * @param date the date it also stands for, or null
     * @param size the resolution it also stands for, or null
     */
    public record Word(String text, DateMatch date, Resolution size) {

        /** True if the trigram index can find it; shorter words need a LIKE scan. Counts characters, not chars. */
        public boolean usesIndex() {
            return text.codePointCount(0, text.length()) >= MIN_INDEXED_LENGTH;
        }
    }

    /** A date a word stands for. */
    public sealed interface DateMatch permits Range, MonthOfYear {
    }

    /**
     * Taken from {@code fromMillis} (inclusive) to {@code untilMillis} (exclusive), in the same "floating"
     * time as {@code media.sort_date}: the camera's wall-clock reading stored as if it were UTC.
     */
    public record Range(long fromMillis, long untilMillis) implements DateMatch {

        static Range of(LocalDate from, LocalDate until) {
            return new Range(from.toEpochDay() * MILLIS_PER_DAY, until.toEpochDay() * MILLIS_PER_DAY);
        }
    }

    /** Taken in this month (1–12) of any year. */
    public record MonthOfYear(int month) implements DateMatch {
    }

    /** Exactly this width and height, as indexed (already turned for EXIF rotation). */
    public record Resolution(int width, int height) {
    }
}
