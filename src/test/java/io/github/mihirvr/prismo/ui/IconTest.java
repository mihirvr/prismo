package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every icon's SVG path is well formed and drawn inside the 24 × 24 grid. JavaFX doesn't complain about a bad
 * path: it draws it cut short or not at all, so a typo would only show as a broken icon somewhere.
 */
class IconTest {

    private static final Pattern TOKEN = Pattern.compile("[MLHVCSQTAZmlhvcsqtaz]|-?(?:\\d+\\.?\\d*|\\.\\d+)");
    /** How many numbers each command takes (it may repeat with more). */
    private static final Map<Character, Integer> ARGUMENTS = Map.of('M', 2, 'L', 2, 'H', 1, 'V', 1, 'C', 6, 'S', 4,
            'Q', 4, 'T', 2, 'A', 7, 'Z', 0);

    @Test
    void everyPathIsWellFormedAndInsideTheGrid() {
        for (Icon icon : Icon.values()) {
            List<String> tokens = tokens(icon);
            assertTrue(Character.isLetter(tokens.getFirst().charAt(0)) && tokens.getFirst().equalsIgnoreCase("M"),
                    icon + " starts with a move");
            int i = 0;
            while (i < tokens.size()) {
                char command = tokens.get(i++).charAt(0);
                assertTrue(Character.isUpperCase(command), icon + ": absolute commands only (" + command + ")");
                int count = ARGUMENTS.get(command);
                List<Double> numbers = new ArrayList<>();
                while (i < tokens.size() && !Character.isLetter(tokens.get(i).charAt(0))) {
                    numbers.add(Double.parseDouble(tokens.get(i++)));
                }
                if (count == 0 ? !numbers.isEmpty() : numbers.isEmpty() || numbers.size() % count != 0) {
                    fail(icon + ": " + command + " has " + numbers.size() + " numbers");
                }
                for (int n = 0; n < numbers.size(); n++) {
                    // An arc's radii, rotation and flags aren't positions; its last two numbers are.
                    boolean position = command != 'A' || n % 7 >= 5;
                    if (position) {
                        double value = numbers.get(n);
                        assertTrue(value >= 0 && value <= Icon.GRID, icon + ": " + value + " is off the grid");
                    }
                }
            }
        }
    }

    @Test
    void eachIconHasItsOwnDrawing() {
        // The favorite's heart is the same drawing as Add to Favorites, filled in: drawing and fill together differ.
        assertEquals(Icon.values().length, java.util.Arrays.stream(Icon.values())
                .map(icon -> icon.path() + icon.filled()).distinct().count());
    }

    private static List<String> tokens(Icon icon) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(icon.path());
        int end = 0;
        while (matcher.find()) {
            String between = icon.path().substring(end, matcher.start());
            assertTrue(between.isBlank() || between.strip().equals(","), icon + ": stray text " + between);
            tokens.add(matcher.group());
            end = matcher.end();
        }
        assertTrue(icon.path().substring(end).isBlank(), icon + ": stray text at the end");
        return tokens;
    }
}
