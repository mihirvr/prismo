package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class GridViewTest {

    @Test
    void timelineHasDateOrLetterHeadersAndTheOtherViewsNone() {
        for (MediaSort sort : MediaSort.values()) {
            ViewOptions options = ViewOptions.DEFAULT.withSort(sort);
            Headers expected = sort.isDate() ? Headers.DATES : sort == MediaSort.NAME ? Headers.LETTERS : Headers.NONE;
            assertEquals(expected, GridView.TIMELINE.headers(options), sort.name());
            assertEquals(Headers.NONE, GridView.ALL_FILES.headers(options), sort.name());
            assertEquals(Headers.NONE, GridView.FOLDERS.headers(options), "folder headers instead: " + sort.name());
            assertEquals(sort.isDate(), GridView.ALL_FILES.dateOrdered(options), "Go to date: " + sort.name());
            assertFalse(GridView.FOLDERS.dateOrdered(options), "no Go to date in folders: " + sort.name());
        }
    }

    @Test
    void theViewIsKeptAsASettingAndBadValuesGiveTheDefault() {
        for (GridView view : GridView.values()) {
            assertEquals(view, GridView.fromSetting(view.settingValue()));
        }
        assertEquals(GridView.ALL_FILES, GridView.fromSetting(" ALL_FILES "));
        for (String bad : new String[] {null, "", "gone"}) {
            assertEquals(GridView.TIMELINE, GridView.fromSetting(bad), String.valueOf(bad));
        }
        assertEquals("All files", GridView.ALL_FILES.toString(), "what the menu shows");
    }
}
