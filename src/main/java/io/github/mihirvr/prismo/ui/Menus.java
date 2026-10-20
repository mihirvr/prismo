package io.github.mihirvr.prismo.ui;

import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;

/**
 * Building right-click menu entries the same way everywhere: icon on the left, its keyboard shortcut on the right.
 * JavaFX writes the shortcut: key names ("Page Up", "Delete"), and the standard Unicode arrows for the arrow keys
 * and Enter (← → ↑ ↓ ↵: plain text characters, not emoji).
 */
public final class Menus {

    /** Icons in menus. */
    public static final double ICON_SIZE = 16;

    private Menus() {
    }

    /**
     * An entry. {@code icon} and {@code shortcut} may be null. The shortcut is shown only: these menus are shown
     * with {@code ContextMenu.show}, not attached to a control, so JavaFX doesn't register it as a second key
     * binding; the view's own key handling does the work.
     */
    public static MenuItem item(String text, Icon icon, KeyCombination shortcut, Runnable action) {
        MenuItem item = new MenuItem(text, icon == null ? null : icon.node(ICON_SIZE));
        if (shortcut != null) {
            item.setAccelerator(shortcut);
        }
        item.setOnAction(_ -> action.run());
        return item;
    }
}
