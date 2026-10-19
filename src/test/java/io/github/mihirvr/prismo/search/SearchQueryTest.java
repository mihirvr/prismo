package io.github.mihirvr.prismo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.search.SearchQuery.MonthOfYear;
import io.github.mihirvr.prismo.search.SearchQuery.Range;
import io.github.mihirvr.prismo.search.SearchQuery.Resolution;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchQueryTest {

    private static final long DAY = 86_400_000L;

    @Test
    void splitsOnAnyWhitespaceAndIgnoresBlankText() {
        assertTrue(SearchQuery.parse("").isEmpty());
        assertTrue(SearchQuery.parse("  \t ").isEmpty());
        assertTrue(SearchQuery.parse(null).isEmpty());
        assertEquals(List.of("goa", "IMG_01", "2024"),
                SearchQuery.parse("  goa \t IMG_01\n2024 ").words().stream().map(SearchQuery.Word::text).toList());
    }

    @Test
    void yearsMonthsAndDaysBecomeDateRanges() {
        assertEquals(range(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)), date("2024"));
        assertEquals(range(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 11, 1)), date("2024-10"));
        assertEquals(range(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1)), date("2024-1"));
        assertEquals(range(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1)), date("2024-02-29"));
        assertEquals(range(LocalDate.of(1969, 12, 31), LocalDate.of(1970, 1, 1)), date("1969-12-31"));
        // Not dates: outside 1900–2099 as a bare year, impossible months and days, other digit counts.
        assertNull(date("1899"));
        assertNull(date("2100"));
        assertNull(date("20240"));
        assertNull(date("2024-13"));
        assertNull(date("2024-02-30"));
        assertNull(date("goa"));
    }

    @Test
    void monthNamesMatchThatMonthOfAnyYear() {
        assertEquals(new MonthOfYear(10), date("october"));
        assertEquals(new MonthOfYear(10), date("OCT"));
        assertEquals(new MonthOfYear(9), date("Sept"));
        assertEquals(new MonthOfYear(5), date("may"));
        assertNull(date("octo"));
    }

    @Test
    void widthByHeightIsAResolution() {
        assertEquals(new Resolution(4000, 3000), SearchQuery.word("4000x3000").size());
        assertEquals(new Resolution(4000, 3000), SearchQuery.word("4000X3000").size());
        assertEquals(new Resolution(1080, 1920), SearchQuery.word("1080×1920").size());
        assertNull(SearchQuery.word("0x5").size());
        assertNull(SearchQuery.word("4000x").size());
        assertNull(SearchQuery.word("x3000").size());
    }

    @Test
    void wordsOfThreeCharactersOrMoreUseTheIndex() {
        assertFalse(SearchQuery.word("ab").usesIndex());
        assertTrue(SearchQuery.word("abc").usesIndex());
        // Counted in characters, not Java chars: two emoji are 4 chars but 2 characters.
        assertFalse(SearchQuery.word("😀😀").usesIndex());
        assertTrue(SearchQuery.word("😀😀😀").usesIndex());
    }

    private static SearchQuery.DateMatch date(String word) {
        return SearchQuery.word(word).date();
    }

    private static Range range(LocalDate from, LocalDate until) {
        return new Range(from.toEpochDay() * DAY, until.toEpochDay() * DAY);
    }
}
