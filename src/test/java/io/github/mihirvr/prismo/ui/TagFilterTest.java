package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The People filter (1.2.0) and the Categories filter (1.3.0), and how they are saved with the Filters + Sort. */
class TagFilterTest {

    @Test
    void nothingTickedFiltersNothing() {
        for (TagKind kind : TagKind.values()) {
            assertFalse(TagFilter.none(kind).active());
            assertNull(TagFilter.none(kind).sqlCondition());
            assertNull(TagFilter.none(kind).label());
        }
        assertNull(ViewOptions.DEFAULT.sqlCondition());
        assertEquals(TagFilter.NO_PEOPLE, ViewOptions.DEFAULT.people());
        assertEquals(TagFilter.NO_CATEGORIES, ViewOptions.DEFAULT.categories());
    }

    @Test
    void togetherNeedsEveryoneAnyNeedsOne() {
        TagFilter both = TagFilter.NO_PEOPLE.withTag(7, true).withTag(3, true);
        assertTrue(both.together(), "the default");
        assertEquals("id IN (SELECT media_id FROM media_person WHERE person_id IN (3, 7) GROUP BY media_id"
                + " HAVING count(*) = 2)", both.sqlCondition());
        assertEquals("id IN (SELECT media_id FROM media_person WHERE person_id IN (3, 7))",
                both.withTogether(false).sqlCondition());
        assertEquals("People: 2", both.label());

        TagFilter categories = TagFilter.NO_CATEGORIES.withTag(2, true).withTag(5, true);
        assertEquals("id IN (SELECT media_id FROM media_category WHERE category_id IN (2, 5) GROUP BY media_id"
                + " HAVING count(*) = 2)", categories.sqlCondition());
        assertEquals("Categories: 2", categories.label());
    }

    @Test
    void untaggedAndTagsDontMix() {
        TagFilter untagged = TagFilter.NO_PEOPLE.withTag(3, true).withUntagged(true);
        assertEquals(Set.of(), untagged.ids());
        assertEquals("id NOT IN (SELECT media_id FROM media_person)", untagged.sqlCondition());
        assertEquals("No people", untagged.label());
        assertFalse(untagged.withTag(5, true).untagged(), "ticking a person ends No people");
        TagFilter noCategory = TagFilter.NO_CATEGORIES.withUntagged(true);
        assertEquals("id NOT IN (SELECT media_id FROM media_category)", noCategory.sqlCondition());
        assertEquals("No category", noCategory.label());
    }

    @Test
    void savedAndReadBack() {
        TagFilter any = TagFilter.NO_PEOPLE.withTag(3, true).withTag(7, true).withTogether(false);
        ViewOptions options = ViewOptions.DEFAULT.withTypes(Set.of("jpg")).withPeople(any);
        assertEquals("ALL,DATE_TAKEN,desc,jpg,p=any:3+7", options.toSetting(), "as 1.2.0 saved it");
        assertEquals(options, ViewOptions.fromSetting(options.toSetting()));
        ViewOptions untagged = ViewOptions.DEFAULT.withPeople(TagFilter.NO_PEOPLE.withUntagged(true));
        assertEquals("ALL,DATE_TAKEN,desc,p=none", untagged.toSetting());
        assertEquals(untagged, ViewOptions.fromSetting(untagged.toSetting()));
        assertEquals(TagFilter.NO_PEOPLE, TagFilter.fromSetting(TagKind.PEOPLE, "all:x"), "unreadable: nothing");
        assertEquals(ViewOptions.DEFAULT, ViewOptions.fromSetting("ALL,DATE_TAKEN,desc"), "1.1.0 settings still read");
    }

    @Test
    void categoriesAreSavedAfterPeopleAndReadBack() {
        TagFilter nature = TagFilter.NO_CATEGORIES.withTag(4, true);
        ViewOptions all = ViewOptions.DEFAULT.withTypes(Set.of("png")).withPeople(TagFilter.NO_PEOPLE.withTag(3, true))
                .withCategories(nature);
        assertEquals("ALL,DATE_TAKEN,desc,png,p=all:3,c=all:4", all.toSetting());
        assertEquals(all, ViewOptions.fromSetting(all.toSetting()));
        ViewOptions onlyCategories = ViewOptions.DEFAULT.withCategories(nature.withTogether(false));
        assertEquals("ALL,DATE_TAKEN,desc,c=any:4", onlyCategories.toSetting());
        assertEquals(onlyCategories, ViewOptions.fromSetting(onlyCategories.toSetting()));
        ViewOptions typesAndCategories = ViewOptions.DEFAULT.withTypes(Set.of("jpg"))
                .withCategories(TagFilter.NO_CATEGORIES.withUntagged(true));
        assertEquals("ALL,DATE_TAKEN,desc,jpg,c=none", typesAndCategories.toSetting());
        assertEquals(typesAndCategories, ViewOptions.fromSetting(typesAndCategories.toSetting()));
        for (String bad : new String[] {"ALL,DATE_TAKEN,desc,c=all:4,p=all:3", "ALL,DATE_TAKEN,desc,p=all:3,p=all:4",
                "ALL,DATE_TAKEN,desc,jpg,c=all:4,extra", "ALL,DATE_TAKEN,desc,x=all:4"}) {
            assertEquals(ViewOptions.DEFAULT, ViewOptions.fromSetting(bad), bad);
        }
    }

    @Test
    void aDeletedTagDropsOut() {
        TagFilter both = TagFilter.NO_CATEGORIES.withTag(3, true).withTag(7, true);
        assertEquals(Set.of(3L), both.keepOnly(Set.of(3L, 9L)).ids());
        assertTrue(both.keepOnly(Set.of(3L, 7L)) == both, "unchanged: the same filter");
    }

    @Test
    void bothFiltersJoinTheOtherFilters() {
        ViewOptions options = ViewOptions.DEFAULT.withFilter(MediaFilter.VIDEOS)
                .withPeople(TagFilter.NO_PEOPLE.withTag(4, true))
                .withCategories(TagFilter.NO_CATEGORIES.withTag(9, true));
        assertTrue(options.sqlCondition().endsWith(" AND id IN (SELECT media_id FROM media_person WHERE person_id IN (4))"
                + " AND id IN (SELECT media_id FROM media_category WHERE category_id IN (9))"), options.sqlCondition());
        assertTrue(options.isFiltered());
        assertTrue(options.filterLabel().endsWith("People: 1, Categories: 1"), options.filterLabel());
        assertTrue(ViewOptions.DEFAULT.withCategories(TagFilter.NO_CATEGORIES.withTag(1, true)).isFiltered());
    }

    @Test
    void aFilterOfTheWrongKindIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ViewOptions.DEFAULT.withPeople(TagFilter.NO_CATEGORIES));
        assertEquals(TagFilter.NO_CATEGORIES.withTag(2, true),
                ViewOptions.DEFAULT.withTags(TagFilter.NO_CATEGORIES.withTag(2, true)).tags(TagKind.CATEGORIES));
    }
}
