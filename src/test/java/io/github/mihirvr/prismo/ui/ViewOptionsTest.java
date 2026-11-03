package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
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

    @Test
    void filtersAndSortAreKeptAsASettingAndBadValuesGiveTheDefault() {
        ViewOptions chosen = new ViewOptions(MediaFilter.PHOTOS, MediaSort.NAME, false, true);

        assertEquals("PHOTOS,NAME,asc", chosen.toSetting(), "the File Explorer switch has its own setting");
        assertEquals(chosen.withOsHiddenShown(false), ViewOptions.fromSetting(chosen.toSetting()));
        assertEquals(new ViewOptions(MediaFilter.RAW, MediaSort.SIZE, true),
                ViewOptions.fromSetting("RAW,SIZE,desc"));
        for (String bad : new String[] {null, "", "PHOTOS", "PHOTOS,NAME", "PHOTOS,NAME,up", "GONE,NAME,asc",
                "PHOTOS,GONE,desc", "PHOTOS,NAME,asc,jpg,extra"}) {
            assertEquals(ViewOptions.DEFAULT, ViewOptions.fromSetting(bad), String.valueOf(bad));
        }
    }

    @Test
    void typesAreKeptNarrowTheSqlAndOldTypeFiltersComeBackAsTypes() {
        ViewOptions chosen = ViewOptions.DEFAULT.withTypes(Set.of("png", "jpeg", "gone"));
        assertEquals(Set.of("jpg", "png"), chosen.types(), "jpeg counts as JPG; unknown types are dropped");
        assertEquals("ALL,DATE_TAKEN,desc,jpg+png", chosen.toSetting());
        assertEquals(chosen, ViewOptions.fromSetting(chosen.toSetting()));
        assertEquals("extension IN ('jpg', 'jpeg', 'png')", chosen.sqlCondition());
        assertEquals("media_type = 'photo' AND extension IN ('jpg', 'jpeg', 'png')",
                chosen.withFilter(MediaFilter.PHOTOS).sqlCondition());
        assertEquals("JPG, PNG", chosen.filterLabel());
        assertEquals("Photos, JPG, PNG", chosen.withFilter(MediaFilter.PHOTOS).filterLabel());
        assertTrue(chosen.isFiltered());
        assertFalse(ViewOptions.DEFAULT.isFiltered());
        assertEquals("All media", ViewOptions.DEFAULT.filterLabel());

        assertEquals(ViewOptions.DEFAULT.withSort(MediaSort.NAME).withTypes(Set.of("heic")),
                ViewOptions.fromSetting("HEIC,NAME,asc"), "the old HEIC filter, saved by 1.0.x");
        assertEquals(Set.of("jpg"), ViewOptions.fromSetting("JPEG,DATE_TAKEN,desc").types());
    }
}
