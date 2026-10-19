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
            for (TimelineItem item : List.of(item(false, false, false), item(true, true, false), item(true, true, true))) {
                for (KeyCode key : List.of(KeyCode.F, KeyCode.H, KeyCode.DELETE)) {
                    if (ItemAction.forKey(key, item) == action) {
                        assertTrue(ItemAction.available(item).contains(action), "a key only does what's available");
                    }
                }
            }
        }
    }

    @Test
    void onlyFavoritingKeepsTheItemInTheView() {
        for (ItemAction action : ItemAction.values()) {
            boolean keeps = action == ItemAction.FAVORITE || action == ItemAction.UNFAVORITE;
            assertEquals(keeps, action.keepsItemInView(), action.name());
        }
        assertTrue(ItemAction.FAVORITE.applyTo(item(false, false, false)).favorite());
        assertFalse(ItemAction.UNFAVORITE.applyTo(item(true, false, false)).favorite());
        assertEquals("Add to Favorites (F)", ItemAction.FAVORITE.tooltip());
        assertEquals("Restore from Trash", ItemAction.RESTORE.tooltip());
    }
}
