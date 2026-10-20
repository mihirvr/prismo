package io.github.mihirvr.prismo.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShortcutsTest {

    @Test
    void everySectionHasLinesAndADifferentTitle() {
        Set<String> titles = new HashSet<>();
        for (Shortcuts.Section section : Shortcuts.SECTIONS) {
            assertTrue(titles.add(section.title()), "twice: " + section.title());
            assertFalse(section.shortcuts().isEmpty(), section.title());
            for (Shortcuts.Shortcut shortcut : section.shortcuts()) {
                assertFalse(shortcut.keys().isBlank() || shortcut.does().isBlank(), shortcut.toString());
            }
        }
        assertEquals(List.of("Everywhere", "Photo grid", "Selecting", "Viewer", "Photos", "Videos"),
                Shortcuts.SECTIONS.stream().map(Shortcuts.Section::title).toList());
    }

    /** Plain ASCII only: no arrows (one font draws some in colour), never an emoji. */
    @Test
    void keysAreWrittenInPlainText() {
        for (Shortcuts.Section section : Shortcuts.SECTIONS) {
            for (Shortcuts.Shortcut shortcut : section.shortcuts()) {
                (shortcut.keys() + shortcut.does()).codePoints().forEach(c -> assertTrue(c < 0x80,
                        "not plain text: " + Character.getName(c) + " in " + shortcut));
            }
        }
    }

    @Test
    void theGuideNamesTheKeysTheAppHandles() {
        List<String> keys = Shortcuts.SECTIONS.stream().flatMap(s -> s.shortcuts().stream())
                .map(Shortcuts.Shortcut::keys).toList();
        for (String key : List.of("Ctrl+F", "Ctrl+A", "F2", "J / L", "[ / ]", "Z / X", "Page Up / Page Down", "F11")) {
            assertTrue(keys.contains(key), key);
        }
    }
}
