package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Back / Forward like a web browser (P8), with plain strings as places. */
class NavigationHistoryTest {

    @Test
    void backAndForwardWalkThroughThePlacesVisited() {
        NavigationHistory<String> history = new NavigationHistory<>("Timeline");
        history.visit("Favorites");
        history.visit("Trash");

        assertEquals(Optional.of("Favorites"), history.back());
        assertEquals(Optional.of("Timeline"), history.back());
        assertEquals(Optional.empty(), history.back(), "nothing before the first place");
        assertEquals("Timeline", history.current(), "an empty step changes nothing");
        assertEquals(Optional.of("Favorites"), history.forward());
        assertEquals(Optional.of("Trash"), history.forward());
        assertEquals(Optional.empty(), history.forward(), "nothing after the last place");
        assertEquals("Trash", history.current());
    }

    @Test
    void goingSomewhereNewDropsWhatForwardCouldReach() {
        NavigationHistory<String> history = new NavigationHistory<>("A");
        history.visit("B");
        history.visit("C");
        history.back(); // at B, C ahead

        assertTrue(history.canGoForward());
        history.visit("D");

        assertFalse(history.canGoForward(), "a new place, like a new page in a browser");
        assertEquals(List.of("A", "B"), history.backList());
        assertEquals(Optional.of("B"), history.back());
    }

    @Test
    void theSamePlaceAgainIsNotANewStep() {
        NavigationHistory<String> history = new NavigationHistory<>("A");
        history.visit("B");
        history.back(); // at A, B ahead

        assertFalse(history.visit("A"), "showing a place again (restoring) records nothing");
        assertTrue(history.canGoForward(), "and keeps Forward");
        assertEquals(List.of(), history.backList());
    }

    @Test
    void replaceChangesThePlaceWithoutAStep() {
        NavigationHistory<String> history = new NavigationHistory<>("Timeline");
        history.visit("search h");
        history.replace("search ho");
        history.replace("search holiday");

        assertEquals("search holiday", history.current());
        assertEquals(Optional.of("Timeline"), history.back(), "one step back, however many letters were typed");
        assertEquals(Optional.of("search holiday"), history.forward());
    }

    @Test
    void theBackListIsCappedOldestFirstOut() {
        NavigationHistory<Integer> history = new NavigationHistory<>(0, 3);
        for (int i = 1; i <= 10; i++) {
            history.visit(i);
        }

        assertEquals(List.of(7, 8, 9), history.backList());
        int steps = 0;
        while (history.back().isPresent()) {
            steps++;
        }
        assertEquals(3, steps);
        assertEquals(7, history.current());
        assertEquals(List.of(10, 9, 8), history.forwardList(), "furthest first");
    }

    @Test
    void theDefaultCapIsFifty() {
        NavigationHistory<Integer> history = new NavigationHistory<>(0);
        for (int i = 1; i <= 120; i++) {
            history.visit(i);
        }

        assertEquals(NavigationHistory.DEFAULT_LIMIT, history.backList().size());
        assertEquals(70, history.backList().getFirst());
    }

    @Test
    void clearForwardForgetsOnlyWhatIsAhead() {
        NavigationHistory<String> history = new NavigationHistory<>("A");
        history.visit("B");
        history.back();

        history.clearForward();

        assertFalse(history.canGoForward());
        assertEquals("A", history.current());
        assertFalse(history.canGoBack());
    }

    @Test
    void refusesNullPlacesAndANoRoomLimit() {
        assertThrows(NullPointerException.class, () -> new NavigationHistory<String>(null));
        assertThrows(IllegalArgumentException.class, () -> new NavigationHistory<>("A", 0));
        NavigationHistory<String> history = new NavigationHistory<>("A");
        assertThrows(NullPointerException.class, () -> history.visit(null));
        assertThrows(NullPointerException.class, () -> history.replace(null));
    }
}
