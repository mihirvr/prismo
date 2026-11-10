package io.github.mihirvr.prismo.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.MenuButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Enter works on the control Tab reached (2026-11-10): JavaFX fires a focused button only on Space, and Enter only
 * fires a dialog's default button, so Enter on a button or a drop-down did nothing. On Windows (and most Linux
 * desktops) Enter is expected to work too:
 * <ul>
 *   <li>a button, toggle, check box or radio button: pressed (in a dialog, the focused button, not the default one);</li>
 *   <li>a drop-down (menu button, combo box, choice box) that is closed: opened. Once open, its list has the keys:
 *       Up / Down move, Enter picks, Esc closes.</li>
 * </ul>
 * Text fields, the grid (Enter opens the photo) and everything else keep their own Enter. Only plain Enter, never
 * with Ctrl, Alt or Shift.
 */
public final class EnterKey {

    private static final Object INSTALLED = new Object();

    private EnterKey() {
    }

    /** Adds the rule to a window's scene (once; a second call does nothing). */
    public static void install(Scene scene) {
        if (scene.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) {
            return;
        }
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (handle(scene.getFocusOwner(), event)) {
                event.consume();
            }
        });
    }

    /** Does what Enter should do on {@code focused}; true when it did something (the key is then used up). */
    static boolean handle(Node focused, KeyEvent event) {
        if (event.getCode() != KeyCode.ENTER || event.isShortcutDown() || event.isControlDown() || event.isAltDown()
                || event.isShiftDown() || event.isMetaDown() || focused == null || focused.isDisabled()) {
            return false;
        }
        if (focused instanceof MenuButton menu) {
            if (!menu.isShowing()) {
                menu.show();
            }
            return true;
        }
        if (focused instanceof ButtonBase button) {
            button.fire();
            return true;
        }
        if (focused instanceof ComboBoxBase<?> box && !box.isEditable() && !box.isShowing()) {
            box.show();
            return true;
        }
        if (focused instanceof ChoiceBox<?> box && !box.isShowing()) {
            box.show();
            return true;
        }
        return false;
    }
}
