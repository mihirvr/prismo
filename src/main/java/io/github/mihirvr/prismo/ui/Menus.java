package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
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

    /**
     * The groups of a menu with a line between them, empty groups left out (1.2.0: flat right-click menus, grouped
     * as Microsoft's menu guidelines say: open, copy, the item's settings, what changes the file, properties).
     */
    @SafeVarargs
    public static List<MenuItem> grouped(List<MenuItem>... groups) {
        List<MenuItem> entries = new ArrayList<>();
        for (List<MenuItem> group : groups) {
            if (group.isEmpty()) {
                continue;
            }
            if (!entries.isEmpty()) {
                entries.add(new SeparatorMenuItem());
            }
            entries.addAll(group);
        }
        return entries;
    }

    /** Marks the entry that a double-click or Enter does too ("Open"): bold, as in File Explorer. */
    public static MenuItem asDefault(MenuItem item) {
        item.getStyleClass().add("menu-default");
        return item;
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
        MenuClickGuard.install(stage); // 1.2.0: an outside click closes the menu and does nothing else
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
            if (isMenu(window) && window.isShowing() && ownedBy((PopupWindow) window, owner)) {
                window.hide();
            }
        }
    }

    /** True while any menu over {@code owner} (or a window it owns) is open. */
    static boolean anyOpen(Window owner) {
        for (Window window : Window.getWindows()) {
            if (isMenu(window) && window.isShowing() && ownedBy((PopupWindow) window, owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A menu in the sense of these rules: a popup that closes itself on an outside click. That is every
     * {@link ContextMenu} (right-click menus, the drop-down buttons' lists) and also a drop-down list's popup
     * (Sort, Layout, Filter: a ComboBox's list is a popup of its own kind, not a ContextMenu; 1.2.0, found in the app:
     * the outside click closed it and still opened the tile under it). Never a tooltip.
     */
    static boolean isMenu(Window window) {
        return window instanceof PopupWindow popup && popup.isAutoHide() && !(popup instanceof Tooltip);
    }

    static boolean ownedBy(PopupWindow popup, Window owner) {
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
