package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewOptionsTest {

    @Test
    void aNewSortStartsInItsNaturalDirection() {
        ViewOptions byName = ViewOptions.DEFAULT.withSort(MediaSort.NAME);
        assertFalse(byName.descending(), "names start A to Z");
        assertEquals("A to Z", byName.sort().directionLabel(byName.descending()));

        ViewOptions bySize = byName.withSort(MediaSort.SIZE);
        assertTrue(bySize.descending(), "sizes start largest first");

        ViewOptions oldestFirst = ViewOptions.DEFAULT.withDescending(false);
        assertEquals("Oldest first", oldestFirst.sort().directionLabel(false));
        assertTrue(oldestFirst.withSort(MediaSort.DATE_ADDED).descending(), "a new date sort starts newest first");
    }

    @Test
    void theFilterIsKeptAcrossSortChanges() {
        ViewOptions videos = ViewOptions.DEFAULT.withFilter(MediaFilter.VIDEOS);
        assertEquals(MediaFilter.VIDEOS, videos.withSort(MediaSort.NAME).withDescending(true).filter());
        assertFalse(videos.isDefault());
        assertTrue(videos.withFilter(MediaFilter.ALL).isDefault());
    }

    @Test
    void theFileExplorerSwitchIsKeptAcrossChangesAndDoesNotChangeTheOrder() {
        assertFalse(ViewOptions.DEFAULT.osHiddenShown(), "off by default");
        ViewOptions on = ViewOptions.DEFAULT.withOsHiddenShown(true);
        assertTrue(on.withFilter(MediaFilter.VIDEOS).withSort(MediaSort.NAME).withDescending(true).osHiddenShown());
        assertFalse(on.isDefault());
        assertTrue(on.sameOrder(ViewOptions.DEFAULT), "same list order: a view stays where it is");
        assertFalse(on.sameOrder(ViewOptions.DEFAULT.withSort(MediaSort.NAME)));
        assertFalse(on.sameOrder(null));
        assertEquals(on.sqlOrderBy(), ViewOptions.DEFAULT.sqlOrderBy());
    }

    @Test
    void orderByAddsTheIdTieBreakInTheSameDirection() {
        assertEquals("+sort_date DESC, id DESC", ViewOptions.DEFAULT.sqlOrderBy());
        assertEquals("+sort_date ASC, id ASC", ViewOptions.DEFAULT.withDescending(false).sqlOrderBy());
        assertEquals("file_name COLLATE NOCASE ASC, id ASC", ViewOptions.DEFAULT.withSort(MediaSort.NAME).sqlOrderBy());
        assertNull(ViewOptions.DEFAULT.sqlCondition());
    }

    @Test
    void onlyDateSortsAreGrouped() {
        for (MediaSort sort : MediaSort.values()) {
            boolean date = sort == MediaSort.DATE_TAKEN || sort == MediaSort.DATE_MODIFIED || sort == MediaSort.DATE_ADDED;
            assertEquals(date, ViewOptions.DEFAULT.withSort(sort).grouped(), sort.name());
        }
    }
}
