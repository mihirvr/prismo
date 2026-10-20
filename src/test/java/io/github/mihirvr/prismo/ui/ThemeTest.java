package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ThemeTest {

    /** A named colour where it is defined ({@code -prismo-bg: #fff;}). */
    private static final Pattern DEFINED = Pattern.compile("(-prismo-[a-z0-9-]+)\\s*:");
    private static final Pattern NAME = Pattern.compile("-prismo-[a-z0-9-]+");

    @Test
    void theSavedValueReadsBackAndAnythingElseFollowsTheSystem() {
        for (Theme theme : Theme.values()) {
            assertEquals(theme, Theme.fromSetting(theme.settingValue()));
        }
        assertEquals(Theme.DARK, Theme.fromSetting(" Dark "), "spaces and case don't matter");
        assertEquals(Theme.SYSTEM, Theme.fromSetting(null));
        assertEquals(Theme.SYSTEM, Theme.fromSetting(""));
        assertEquals(Theme.SYSTEM, Theme.fromSetting("purple"));
        assertEquals(List.of("light", "dark", "amoled", "system"),
                List.of(Theme.LIGHT.settingValue(), Theme.DARK.settingValue(), Theme.AMOLED.settingValue(),
                        Theme.SYSTEM.settingValue()), "stored in the settings table: never rename");
    }

    @Test
    void followSystemBecomesLightOrDarkAndTheOthersStayThemselves() {
        assertEquals(Theme.DARK, Theme.SYSTEM.resolve(true));
        assertEquals(Theme.LIGHT, Theme.SYSTEM.resolve(false));
        for (Theme theme : List.of(Theme.LIGHT, Theme.DARK, Theme.AMOLED)) {
            assertEquals(theme, theme.resolve(true));
            assertEquals(theme, theme.resolve(false));
        }
        assertTrue(Theme.DARK.isDark() && Theme.AMOLED.isDark());
        assertFalse(Theme.LIGHT.isDark());
    }

    @Test
    void eachLookIsTheBaseStylesheetPlusItsOwnOnTop() {
        assertEquals(List.of(Theme.BASE_STYLESHEET), Theme.LIGHT.stylesheets());
        assertEquals(List.of(Theme.BASE_STYLESHEET, Theme.DARK_STYLESHEET), Theme.DARK.stylesheets());
        assertEquals(List.of(Theme.BASE_STYLESHEET, Theme.DARK_STYLESHEET, Theme.AMOLED_STYLESHEET),
                Theme.AMOLED.stylesheets(), "AMOLED is Dark with black backgrounds");
        assertThrows(IllegalStateException.class, Theme.SYSTEM::stylesheets);
    }

    /**
     * JavaFX doesn't fail on an unknown looked-up colour: it logs a warning and draws nothing. So every
     * {@code -prismo-*} name a rule uses must be defined in prismo.css, and the dark looks may only redefine
     * names that exist there (a typo would silently do nothing).
     */
    @Test
    void everyNamedColourUsedIsDefinedInTheBaseStylesheet() throws IOException {
        String base = read(Theme.BASE_STYLESHEET);
        Set<String> defined = all(DEFINED, base, 1);
        assertTrue(defined.size() > 20, "found the definitions: " + defined);
        for (String sheet : List.of(Theme.BASE_STYLESHEET, Theme.DARK_STYLESHEET, Theme.AMOLED_STYLESHEET)) {
            String css = read(sheet);
            Set<String> used = all(NAME, css, 0);
            used.removeAll(defined);
            assertEquals(Set.of(), used, sheet + " uses names prismo.css doesn't define");
            Set<String> redefined = all(DEFINED, css, 1);
            redefined.removeAll(defined);
            assertEquals(Set.of(), redefined, sheet + " redefines names prismo.css doesn't have");
        }
        // The dark look must set every colour the light one does, or a light colour would show through.
        assertEquals(defined, all(DEFINED, read(Theme.DARK_STYLESHEET), 1));
    }

    private static Set<String> all(Pattern pattern, String text, int group) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(text.replaceAll("(?s)/\\*.*?\\*/", "")); // not in comments
        while (matcher.find()) {
            found.add(matcher.group(group));
        }
        return found;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = Theme.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is packaged");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
