package io.github.mihirvr.prismo.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.input.MouseEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

/**
 * The first click outside an open menu only closes the menu (1.2.0), as in every other app, in every menu of a window:
 * right-click menus, the viewer's More menu, drop-down buttons and drop-down lists ({@link Menus#isMenu}). Before, on
 * a real Windows install, an outside click left the menu open and reached what was under it: over a video it paused it.
 *
 * <p><b>Why JavaFX's own auto-hide wasn't enough</b> (checked 2026-11-08 with a plain JavaFX program, no Prismo code,
 * on JavaFX 21.0.5, 23.0.2 and 25.0.4, same result): a popup does not auto-hide on a press on its <i>owner node</i>
 * (the node given to {@code show}) or anything inside it, so that a button can toggle its own menu; and when it does
 * auto-hide, it eats only the press, so the release and the click still reach what is under the mouse. The viewer
 * opens its menu from the whole viewer and the People page from the whole grid, so there no click inside ever counted
 * as outside; grid tiles open from the tile, so a click on another tile closed the menu but still went through.
 *
 * <p>Filters on the window itself, so they run before anything in it sees the mouse. Two ways it can go, both covered:
 * <ul>
 *   <li>a menu is still open when the press reaches the window: the guard closes every menu and eats the press, the
 *       release and the click;</li>
 *   <li>JavaFX closed the menu itself on that press (its own auto-hide) and ate only the press: the release and the
 *       click still come, so the guard eats those, once, right after a menu closed.</li>
 * </ul>
 * A press inside a menu never reaches the window (a menu is its own popup window), and a menu closed with Esc or by
 * choosing an entry eats nothing: the next press then reaches the window as usual. The rules are in {@link State},
 * which tests check without JavaFX. Runs on the JavaFX Application Thread.
 */
final class MenuClickGuard {

    private final State state = new State();

    private MenuClickGuard() {
    }

    /** Installs the guard on {@code window} (once per window: {@link Menus#closeOnScrollAndBlur}). */
    static void install(Window window) {
        MenuClickGuard guard = new MenuClickGuard();
        window.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (guard.state.press(Menus.anyOpen(window), System.nanoTime())) {
                Menus.closeAll(window);
                event.consume();
            }
        });
        window.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (guard.state.release(System.nanoTime())) {
                event.consume();
            }
        });
        window.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (guard.state.click(System.nanoTime())) {
                event.consume();
            }
        });
        // Any menu of this window that closes, however it was opened or closed: a hidden popup leaves this list.
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window gone : change.getRemoved()) {
                    if (Menus.isMenu(gone) && Menus.ownedBy((PopupWindow) gone, window)) {
                        guard.state.menuClosed(System.nanoTime());
                    }
                }
            }
        });
    }

    /** The rules, without JavaFX: which mouse events to eat. Times in nanoseconds. */
    static final class State {

        /** A press this soon after a menu closed is the press that closed it (when JavaFX lets it through too). */
        static final long SAME_PRESS_NANOS = 100_000_000L;
        /** The release and click of that press come within this time; after it, nothing is eaten. */
        static final long CLICK_NANOS = 1_500_000_000L;

        private long closedAt = Long.MIN_VALUE / 2;
        private boolean pressSinceClose = true;
        private boolean eating;

        void menuClosed(long now) {
            closedAt = now;
            pressSinceClose = false;
        }

        /** Returns true to close the menus and eat this press. */
        boolean press(boolean menuOpen, long now) {
            boolean closedByThisPress = !pressSinceClose && now - closedAt < SAME_PRESS_NANOS;
            pressSinceClose = true;
            eating = menuOpen || closedByThisPress;
            return eating;
        }

        /** Returns true to eat this release. */
        boolean release(long now) {
            return eating || pressWasEaten(now);
        }

        /** Returns true to eat this click; the click ends what one press started. */
        boolean click(long now) {
            boolean eat = eating || pressWasEaten(now);
            eating = false;
            pressSinceClose = true;
            return eat;
        }

        /** No press came after the menu closed, yet a release does: JavaFX ate the press that closed it. */
        private boolean pressWasEaten(long now) {
            return !pressSinceClose && now - closedAt < CLICK_NANOS;
        }
    }
}
