package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import org.junit.jupiter.api.Test;

/** Menu shortcuts are plain text or the standard Unicode arrows, never emoji. */
class MenusTest {

    @Test
    void theKeysPrismoUsesShowAsNamesOrStandardArrows() {
        assertEquals("←", new KeyCodeCombination(KeyCode.LEFT).getDisplayText());
        assertEquals("→", new KeyCodeCombination(KeyCode.RIGHT).getDisplayText());
        assertEquals("↑", new KeyCodeCombination(KeyCode.UP).getDisplayText());
        assertEquals("↓", new KeyCodeCombination(KeyCode.DOWN).getDisplayText());
        assertEquals("↵", new KeyCodeCombination(KeyCode.ENTER).getDisplayText());
        assertEquals("Page Up", new KeyCodeCombination(KeyCode.PAGE_UP).getDisplayText());
        assertEquals("Page Down", new KeyCodeCombination(KeyCode.PAGE_DOWN).getDisplayText());
        assertEquals("Delete", new KeyCodeCombination(KeyCode.DELETE).getDisplayText());
        assertEquals("I", Menus.item("Info", Icon.INFO, new KeyCodeCombination(KeyCode.I), () -> { })
                .getAccelerator().getDisplayText());
    }

    @Test
    void noKeyShowsAsAnEmoji() {
        for (KeyCode code : KeyCode.values()) {
            String text;
            try {
                text = new KeyCodeCombination(code).getDisplayText();
            } catch (IllegalArgumentException e) {
                continue; // not a key on its own (UNDEFINED, Shift, Ctrl…)
            }
            // Printable ASCII, the Unicode Arrows block (U+2190–U+21FF) or the euro sign: all plain text.
            assertTrue(text.codePoints().allMatch(c -> c >= ' ' && c < 127 || c >= 0x2190 && c <= 0x21FF
                    || c == 0x20AC), code + ": " + text);
        }
    }
}
