package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

class ItemActionTest {

    private static TimelineItem item(boolean favorite, boolean hidden, boolean trashed) {
        return new TimelineItem(new MediaRef(1, Path.of("a.jpg"), "jpg", 0), 0, 0, 0, favorite, hidden, trashed);
    }

    /** An item with the File Explorer Hidden mark. */
    private static TimelineItem osHidden(boolean hidden) {
        return item(false, hidden, false).withOsHidden(true);
    }

    @Test
    void whatCanBeDoneDependsOnTheItemsState() {
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.HIDE, ItemAction.TRASH),
                ItemAction.available(item(false, false, false)));
        assertEquals(List.of(ItemAction.UNFAVORITE, ItemAction.UNHIDE, ItemAction.TRASH),
                ItemAction.available(item(true, true, false)));
        assertEquals(List.of(ItemAction.RESTORE, ItemAction.DELETE_PERMANENTLY),
                ItemAction.available(item(true, false, true)), "in Trash: only restore or delete");
    }

    @Test
    void anItemOnlyHiddenInFileExplorerIsBroughtBackByClearingThatMark() {
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.UNHIDE_IN_EXPLORER, ItemAction.TRASH),
                ItemAction.available(osHidden(false)), "Prismo's Unhide wouldn't change anything for it");
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.UNHIDE, ItemAction.TRASH),
                ItemAction.available(osHidden(true)), "both marks: the eye is Prismo's Unhide first");
        assertEquals(ItemAction.UNHIDE_IN_EXPLORER, ItemAction.forKey(KeyCode.H, osHidden(false)));
        assertEquals(ItemAction.UNHIDE, ItemAction.forKey(KeyCode.H, osHidden(true)));
    }

    @Test
    void theEyeShowsHWhateverActionItIs() {
        assertEquals("H", ItemAction.UNHIDE_IN_EXPLORER.shortcut(osHidden(false), false).getDisplayText(),
                "on Hidden, H clears the File Explorer mark: the menu says so");
        assertEquals("Unhide in File Explorer (H)", ItemAction.UNHIDE_IN_EXPLORER.tooltip(osHidden(false), false));
        assertEquals(null, ItemAction.UNHIDE_IN_EXPLORER.shortcut(osHidden(true), false), "there H is Prismo's Unhide");
        assertEquals(null, ItemAction.HIDE_IN_EXPLORER.shortcut(item(false, false, false), false));
        assertEquals(null, ItemAction.UNHIDE_IN_EXPLORER.shortcut(osHidden(false), true), "switch on: H is Hide");
        assertEquals("F", ItemAction.FAVORITE.shortcut(item(false, false, false), false).getDisplayText());
        assertEquals("Move to Trash (Delete)", ItemAction.TRASH.tooltip(item(false, false, false), false));
    }

    @Test
    void withTheSettingsSwitchOnAFileHiddenInExplorerIsLikeAnyOther() {
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.HIDE, ItemAction.TRASH),
                ItemAction.available(osHidden(false), true), "it's on the Timeline: the eye is Prismo's Hide");
        assertEquals(ItemAction.HIDE, ItemAction.forKey(KeyCode.H, osHidden(false), true));
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.HIDE, ItemAction.UNHIDE_IN_EXPLORER, ItemAction.TRASH),
                ItemAction.forMenu(osHidden(false), true, true), "the mark is still there to clear");
        assertTrue(ItemAction.UNHIDE_IN_EXPLORER.keepsItemInView(osHidden(false), true), "it stays where it is");
        assertTrue(ItemAction.HIDE_IN_EXPLORER.keepsItemInView(item(false, false, false), true));
        assertFalse(ItemAction.HIDE.keepsItemInView(item(false, false, false), true), "Prismo's Hide still hides");
        assertEquals(ItemAction.UNHIDE, ItemAction.forKey(KeyCode.H, osHidden(true), true));
        // Off (the default), as before.
        assertEquals(ItemAction.UNHIDE_IN_EXPLORER, ItemAction.forKey(KeyCode.H, osHidden(false), false));
        assertFalse(ItemAction.HIDE_IN_EXPLORER.keepsItemInView(item(false, false, false), false));
    }

    @Test
    void theMenuAddsTheFileExplorerMarkOnlyWhereTheSystemHasOne() {
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.HIDE, ItemAction.HIDE_IN_EXPLORER, ItemAction.TRASH),
                ItemAction.forMenu(item(false, false, false), true));
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.UNHIDE, ItemAction.UNHIDE_IN_EXPLORER, ItemAction.TRASH),
                ItemAction.forMenu(osHidden(true), true));
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.UNHIDE_IN_EXPLORER, ItemAction.TRASH),
                ItemAction.forMenu(osHidden(false), true), "listed once, not twice");
        assertEquals(ItemAction.available(item(false, false, false)), ItemAction.forMenu(item(false, false, false), false),
                "Linux / macOS: no Explorer entries");
        assertEquals(List.of(ItemAction.RESTORE, ItemAction.DELETE_PERMANENTLY),
                ItemAction.forMenu(item(false, false, true), true), "nothing to mark in Trash");
    }

    @Test
    void favoritingKeepsEverythingElseAboutTheItem() {
        TimelineItem video = new TimelineItem(new MediaRef(2, Path.of("b.mp4"), "mp4", 0), 7, 1920, 1080,
                false, false, false, 12.5, 3, true);

        TimelineItem favorite = ItemAction.FAVORITE.applyTo(video);

        assertTrue(favorite.favorite());
        assertEquals(new TimelineItem(video.ref(), 7, 1920, 1080, true, false, false, 12.5, 3, true), favorite,
                "same item: length, watch progress and the Explorer mark kept");
    }

    @Test
    void keysToggleAndDeleteIsTrashThenDeletePermanently() {
        assertEquals(ItemAction.FAVORITE, ItemAction.forKey(KeyCode.F, item(false, false, false)));
        assertEquals(ItemAction.UNFAVORITE, ItemAction.forKey(KeyCode.F, item(true, false, false)));
        assertEquals(ItemAction.HIDE, ItemAction.forKey(KeyCode.H, item(false, false, false)));
        assertEquals(ItemAction.UNHIDE, ItemAction.forKey(KeyCode.H, item(false, true, false)));
        assertEquals(ItemAction.TRASH, ItemAction.forKey(KeyCode.DELETE, item(false, true, false)));
        assertEquals(ItemAction.DELETE_PERMANENTLY, ItemAction.forKey(KeyCode.DELETE, item(false, false, true)));
        assertNull(ItemAction.forKey(KeyCode.F, item(false, false, true)), "nothing to favorite in Trash");
        assertNull(ItemAction.forKey(KeyCode.H, item(false, false, true)));
        assertNull(ItemAction.forKey(KeyCode.R, item(false, false, false)), "R is the viewer's rotate");
        for (ItemAction action : ItemAction.values()) {
            for (TimelineItem item : List.of(item(false, false, false), item(true, true, false), item(true, true, true),
                    osHidden(false), osHidden(true))) {
                for (KeyCode key : List.of(KeyCode.F, KeyCode.H, KeyCode.DELETE)) {
                    if (ItemAction.forKey(key, item) == action) {
                        assertTrue(ItemAction.available(item).contains(action), "a key only does what's available");
                    }
                }
            }
        }
    }

    @Test
    void favoritingAndTheExplorerMarkOnAPrismoHiddenItemKeepItInTheView() {
        TimelineItem plain = item(false, false, false);
        for (ItemAction action : ItemAction.values()) {
            boolean keeps = action == ItemAction.FAVORITE || action == ItemAction.UNFAVORITE;
            assertEquals(keeps, action.keepsItemInView(plain), action.name());
        }
        assertTrue(ItemAction.HIDE_IN_EXPLORER.keepsItemInView(item(false, true, false)),
                "already on Hidden: it stays there");
        assertTrue(ItemAction.UNHIDE_IN_EXPLORER.keepsItemInView(osHidden(true)), "still hidden in Prismo");
        assertFalse(ItemAction.UNHIDE_IN_EXPLORER.keepsItemInView(osHidden(false)), "back on the Timeline");
        assertTrue(ItemAction.HIDE_IN_EXPLORER.applyTo(plain).osHidden());
        assertFalse(ItemAction.UNHIDE_IN_EXPLORER.applyTo(osHidden(true)).osHidden());
        assertTrue(ItemAction.FAVORITE.applyTo(item(false, false, false)).favorite());
        assertFalse(ItemAction.UNFAVORITE.applyTo(item(true, false, false)).favorite());
    }

    @Test
    void tooltipsAndMenusShowTheKey() {
        assertEquals("Add to Favorites (F)", ItemAction.FAVORITE.tooltip());
        assertEquals("Move to Trash (Delete)", ItemAction.TRASH.tooltip());
        assertEquals("Restore from Trash", ItemAction.RESTORE.tooltip());
        assertEquals("H", ItemAction.HIDE.shortcut().getName());
        assertNull(ItemAction.HIDE_IN_EXPLORER.shortcut());
        assertTrue(osHidden(false).hiddenAnyWay());
        assertFalse(item(true, false, false).hiddenAnyWay());
    }

    // --- Selection Mode (P7) ---

    @Test
    void aSelectionGetsTheUndoActionOnlyWhenEveryItemHasTheMark() {
        TimelineItem plain = item(false, false, false);
        TimelineItem favorite = item(true, false, false);
        TimelineItem hidden = item(false, true, false);
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.HIDE, ItemAction.TRASH),
                ItemAction.forSelection(List.of(plain, favorite, hidden), false), "mixed: favorite and hide them all");
        assertEquals(List.of(ItemAction.UNFAVORITE, ItemAction.HIDE, ItemAction.TRASH),
                ItemAction.forSelection(List.of(favorite, item(true, false, false)), false));
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.UNHIDE, ItemAction.TRASH),
                ItemAction.forSelection(List.of(hidden, osHidden(false)), false), "the Hidden page: Unhide");
        assertEquals(List.of(ItemAction.FAVORITE, ItemAction.HIDE, ItemAction.TRASH),
                ItemAction.forSelection(List.of(hidden, osHidden(false)), true),
                "with the switch on, the Explorer mark doesn't hide: not all hidden");
        assertEquals(List.of(ItemAction.RESTORE, ItemAction.DELETE_PERMANENTLY),
                ItemAction.forSelection(List.of(item(false, false, true), item(true, true, true)), false));
        assertEquals(List.of(), ItemAction.forSelection(List.of(), false));
    }

    @Test
    void eachItemOfASelectionGetsItsOwnAction() {
        TimelineItem plain = item(false, false, false);
        TimelineItem favorite = item(true, false, false);
        TimelineItem trashed = item(false, false, true);
        assertEquals(ItemAction.FAVORITE, ItemAction.FAVORITE.forItem(plain, false));
        assertNull(ItemAction.FAVORITE.forItem(favorite, false), "already a favorite");
        assertNull(ItemAction.UNFAVORITE.forItem(plain, false));
        assertEquals(ItemAction.UNFAVORITE, ItemAction.UNFAVORITE.forItem(favorite, false));
        assertEquals(ItemAction.HIDE, ItemAction.HIDE.forItem(plain, false));
        assertNull(ItemAction.HIDE.forItem(item(false, true, false), false), "already hidden");
        assertNull(ItemAction.HIDE.forItem(osHidden(false), false), "already hidden by its Explorer mark");
        assertEquals(ItemAction.HIDE, ItemAction.HIDE.forItem(osHidden(false), true), "unless the switch is on");
        assertEquals(ItemAction.UNHIDE, ItemAction.UNHIDE.forItem(item(false, true, false), false));
        assertEquals(ItemAction.UNHIDE_IN_EXPLORER, ItemAction.UNHIDE.forItem(osHidden(false), false),
                "only hidden in Explorer: that mark is cleared");
        assertNull(ItemAction.UNHIDE.forItem(plain, false));
        assertEquals(ItemAction.HIDE_IN_EXPLORER, ItemAction.HIDE_IN_EXPLORER.forItem(plain, false));
        assertNull(ItemAction.HIDE_IN_EXPLORER.forItem(osHidden(false), false));
        assertEquals(ItemAction.UNHIDE_IN_EXPLORER, ItemAction.UNHIDE_IN_EXPLORER.forItem(osHidden(false), false));
        assertNull(ItemAction.UNHIDE_IN_EXPLORER.forItem(plain, false));
        assertEquals(ItemAction.TRASH, ItemAction.TRASH.forItem(plain, false));
        assertNull(ItemAction.TRASH.forItem(trashed, false));
        for (ItemAction action : List.of(ItemAction.FAVORITE, ItemAction.UNFAVORITE, ItemAction.HIDE,
                ItemAction.UNHIDE, ItemAction.HIDE_IN_EXPLORER, ItemAction.UNHIDE_IN_EXPLORER)) {
            assertNull(action.forItem(item(true, true, true).withOsHidden(true), false), action + " skips Trash");
        }
        assertEquals(ItemAction.RESTORE, ItemAction.RESTORE.forItem(trashed, false));
        assertEquals(ItemAction.DELETE_PERMANENTLY, ItemAction.DELETE_PERMANENTLY.forItem(trashed, false));
        assertNull(ItemAction.RESTORE.forItem(plain, false));
        assertNull(ItemAction.DELETE_PERMANENTLY.forItem(plain, false), "never deletes what isn't in Trash");
    }

    @Test
    void keysWorkOnTheWholeSelection() {
        List<TimelineItem> favorites = List.of(item(true, false, false), item(true, true, false));
        assertEquals(ItemAction.UNFAVORITE, ItemAction.forSelectionKey(KeyCode.F, favorites, false));
        assertEquals(ItemAction.HIDE, ItemAction.forSelectionKey(KeyCode.H, favorites, false));
        assertEquals(ItemAction.TRASH, ItemAction.forSelectionKey(KeyCode.DELETE, favorites, false));
        assertEquals(ItemAction.DELETE_PERMANENTLY,
                ItemAction.forSelectionKey(KeyCode.DELETE, List.of(item(false, false, true)), false));
        assertNull(ItemAction.forSelectionKey(KeyCode.F, List.of(item(false, false, true)), false), "not in Trash");
        assertNull(ItemAction.forSelectionKey(KeyCode.Q, favorites, false));
    }

    @Test
    void theDefaultHandlerDoesASelectionOneByOne() {
        List<String> done = new java.util.ArrayList<>();
        ItemAction.Handler handler = (action, item) -> done.add(action + " " + item.ref().id());
        TimelineItem first = item(false, false, false);
        TimelineItem second = new TimelineItem(new MediaRef(2, Path.of("b.jpg"), "jpg", 0), 0, 0, 0, true, false,
                false);

        assertTrue(handler.performAll(ItemAction.FAVORITE, List.of(first, second)));
        assertEquals(List.of("FAVORITE 1"), done, "the favorite is skipped");
        assertFalse(handler.performAll(ItemAction.RESTORE, List.of(first, second)), "nothing to restore");
    }
}
