package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The rules of {@link MenuClickGuard} (1.2.0), without JavaFX. Times in nanoseconds. */
class MenuClickGuardTest {

    private static final long MS = 1_000_000L;

    @Test
    void aPressWhileAMenuIsOpenOnlyClosesIt() {
        MenuClickGuard.State state = new MenuClickGuard.State();
        assertTrue(state.press(true, 0), "close the menu, eat the press");
        state.menuClosed(1 * MS);
        assertTrue(state.release(80 * MS));
        assertTrue(state.click(81 * MS), "the click must not reach the video (play / pause)");
        assertFalse(state.press(false, 2_000 * MS), "the next click works as usual");
        assertFalse(state.release(2_080 * MS));
        assertFalse(state.click(2_081 * MS));
    }

    @Test
    void whenJavaFxAteThePressTheReleaseAndClickAreEatenToo() {
        MenuClickGuard.State state = new MenuClickGuard.State();
        state.menuClosed(0); // the menu hid itself on the press, which never reached the view
        assertTrue(state.release(90 * MS));
        assertTrue(state.click(91 * MS));
        assertFalse(state.click(500 * MS), "only once");
    }

    @Test
    void whenJavaFxLetThePressThroughAfterClosingItIsStillEaten() {
        MenuClickGuard.State state = new MenuClickGuard.State();
        state.menuClosed(0);
        assertTrue(state.press(false, 5 * MS), "the press that closed the menu");
        assertTrue(state.release(90 * MS));
        assertTrue(state.click(91 * MS));
    }

    @Test
    void aMenuClosedByEscOrAnEntryEatsNothing() {
        MenuClickGuard.State state = new MenuClickGuard.State();
        state.menuClosed(0); // Esc, or an entry chosen inside the menu
        assertFalse(state.press(false, 400 * MS), "a new press: the user clicks on purpose");
        assertFalse(state.release(480 * MS));
        assertFalse(state.click(481 * MS));
    }

    @Test
    void nothingIsEatenWithoutAMenu() {
        MenuClickGuard.State state = new MenuClickGuard.State();
        assertFalse(state.press(false, 0));
        assertFalse(state.release(50 * MS));
        assertFalse(state.click(51 * MS));
    }

    @Test
    void aStrayReleaseLongAfterAMenuClosedIsNotEaten() {
        MenuClickGuard.State state = new MenuClickGuard.State();
        state.menuClosed(0);
        assertFalse(state.release(3_000 * MS));
    }
}
