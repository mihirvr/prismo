package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The People filter (1.2.0) and how it is saved with the Filters + Sort. */
class PeopleFilterTest {

    @Test
    void nothingTickedFiltersNothing() {
        assertFalse(PeopleFilter.NONE.active());
        assertNull(PeopleFilter.NONE.sqlCondition());
        assertNull(PeopleFilter.NONE.label());
        assertNull(ViewOptions.DEFAULT.sqlCondition());
    }

    @Test
    void togetherNeedsEveryoneAnyNeedsOne() {
        PeopleFilter both = PeopleFilter.NONE.withPerson(7, true).withPerson(3, true);
        assertTrue(both.together(), "the default");
        assertEquals("id IN (SELECT media_id FROM media_person WHERE person_id IN (3, 7) GROUP BY media_id"
                + " HAVING count(*) = 2)", both.sqlCondition());
        assertEquals("id IN (SELECT media_id FROM media_person WHERE person_id IN (3, 7))",
                both.withTogether(false).sqlCondition());
        assertEquals("People: 2", both.label());
    }

    @Test
    void noPeopleAndPeopleDontMix() {
        PeopleFilter untagged = PeopleFilter.NONE.withPerson(3, true).withUntagged(true);
        assertEquals(Set.of(), untagged.people());
        assertEquals("id NOT IN (SELECT media_id FROM media_person)", untagged.sqlCondition());
        assertEquals("No people", untagged.label());
        assertFalse(untagged.withPerson(5, true).untagged(), "ticking a person ends No people");
    }

    @Test
    void savedAndReadBack() {
        PeopleFilter any = PeopleFilter.NONE.withPerson(3, true).withPerson(7, true).withTogether(false);
        ViewOptions options = ViewOptions.DEFAULT.withTypes(Set.of("jpg")).withPeople(any);
        assertEquals("ALL,DATE_TAKEN,desc,jpg,p=any:3+7", options.toSetting());
        assertEquals(options, ViewOptions.fromSetting(options.toSetting()));
        ViewOptions untagged = ViewOptions.DEFAULT.withPeople(PeopleFilter.NONE.withUntagged(true));
        assertEquals("ALL,DATE_TAKEN,desc,p=none", untagged.toSetting());
        assertEquals(untagged, ViewOptions.fromSetting(untagged.toSetting()));
        assertEquals(PeopleFilter.NONE, PeopleFilter.fromSetting("all:x"), "unreadable: nothing filtered");
        assertEquals(ViewOptions.DEFAULT, ViewOptions.fromSetting("ALL,DATE_TAKEN,desc"), "1.1.0 settings still read");
    }

    @Test
    void aDeletedPersonDropsOut() {
        PeopleFilter both = PeopleFilter.NONE.withPerson(3, true).withPerson(7, true);
        assertEquals(Set.of(3L), both.keepOnly(Set.of(3L, 9L)).people());
        assertTrue(both.keepOnly(Set.of(3L, 7L)) == both, "unchanged: the same filter");
    }

    @Test
    void theFilterJoinsTheOtherFilters() {
        ViewOptions options = ViewOptions.DEFAULT.withFilter(MediaFilter.VIDEOS)
                .withPeople(PeopleFilter.NONE.withPerson(4, true));
        assertTrue(options.sqlCondition().endsWith(" AND id IN (SELECT media_id FROM media_person WHERE person_id IN (4))"),
                options.sqlCondition());
        assertTrue(options.isFiltered());
        assertTrue(options.filterLabel().endsWith("People: 1"), options.filterLabel());
    }
}
