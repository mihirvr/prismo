package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.ScrollEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

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

    /** A submenu (1.1.0): opens to the side when the mouse rests on it, as in VLC. */
    public static Menu submenu(String text, Icon icon, List<MenuItem> items) {
        Menu menu = new Menu(text, icon == null ? null : icon.node(ICON_SIZE));
        menu.getItems().setAll(items);
        return menu;
    }

    /**
     * Every menu of {@code stage} closes the same way (1.1.0): on a click outside it and on Esc (JavaFX menus do that
     * already), and also when the window scrolls under it or the window loses focus (Alt+Tab, another app). Covers
     * every right-click menu, the viewer's "..." menu and the drop-down buttons (their lists are menus too), since it
     * closes whatever menu is open rather than one in particular. Call once per window.
     */
    public static void closeOnScrollAndBlur(Stage stage) {
        stage.addEventFilter(ScrollEvent.SCROLL, _ -> closeAll(stage));
        stage.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                closeAll(stage);
            }
        });
    }

    /** Closes every menu open over {@code owner} (and over windows it owns). */
    static void closeAll(Window owner) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window instanceof ContextMenu menu && menu.isShowing() && ownedBy(menu, owner)) {
                menu.hide();
            }
        }
    }

    private static boolean ownedBy(PopupWindow popup, Window owner) {
        for (Window up = popup.getOwnerWindow(); up != null;
                up = up instanceof PopupWindow inner ? inner.getOwnerWindow() : null) {
            if (up == owner) {
                return true;
            }
        }
        return false;
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
