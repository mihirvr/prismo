package io.github.mihirvr.prismo.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Back / Forward like a web browser, over "places" of any kind ({@code P}; the app's are the tab, the Collections
 * page, folder or album, the search and an open viewer). Pure: no JavaFX, so the rules are unit-tested.
 *
 * <ul>
 *   <li>{@link #visit}: the user went somewhere new. The place left goes on the back list and the forward list is
 *       emptied (as in a browser, a new page drops the pages you could go forward to). The same place again is not
 *       a new step.</li>
 *   <li>{@link #replace}: the place on screen changed in a way that isn't a new step (search text typed letter by
 *       letter).</li>
 *   <li>{@link #back} / {@link #forward}: move one step and say where to; the caller then shows that place. Showing
 *       it must not call {@link #visit} with a different place (the app ignores its own changes while it restores
 *       one, and a visit to the place that is already current does nothing).</li>
 * </ul>
 * The back list keeps at most {@code limit} places; the oldest is dropped.
 *
 * <p>Not thread-safe: the app uses it on the JavaFX Application Thread only.
 *
 * @param <P> a place; compared with {@code equals}
 */
public final class NavigationHistory<P> {

    /** How many places Back can go through. */
    public static final int DEFAULT_LIMIT = 50;

    private final int limit;
    /** Older places, the newest last. */
    private final Deque<P> back = new ArrayDeque<>();
    /** Places Forward can go to, the nearest last. */
    private final Deque<P> forward = new ArrayDeque<>();
    private P current;

    public NavigationHistory(P start) {
        this(start, DEFAULT_LIMIT);
    }

    public NavigationHistory(P start, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        this.current = Objects.requireNonNull(start);
        this.limit = limit;
    }

    /** Where the user is now. */
    public P current() {
        return current;
    }

    /**
     * The user went to {@code place}. Returns false (and changes nothing) if it is the place they are already at.
     */
    public boolean visit(P place) {
        Objects.requireNonNull(place);
        if (place.equals(current)) {
            return false;
        }
        back.addLast(current);
        while (back.size() > limit) {
            back.removeFirst();
        }
        forward.clear();
        current = place;
        return true;
    }

    /** The place on screen is now {@code place}, without a new step: Back still goes to where it went before. */
    public void replace(P place) {
        current = Objects.requireNonNull(place);
    }

    public boolean canGoBack() {
        return !back.isEmpty();
    }

    public boolean canGoForward() {
        return !forward.isEmpty();
    }

    /** One step back: the place to show now, or empty if there is none (then nothing changes). */
    public Optional<P> back() {
        if (back.isEmpty()) {
            return Optional.empty();
        }
        forward.addLast(current);
        current = back.removeLast();
        return Optional.of(current);
    }

    /** One step forward: the place to show now, or empty if there is none (then nothing changes). */
    public Optional<P> forward() {
        if (forward.isEmpty()) {
            return Optional.empty();
        }
        back.addLast(current);
        current = forward.removeLast();
        return Optional.of(current);
    }

    /** Forgets the places Forward could go to (one of them can't be shown again). */
    public void clearForward() {
        forward.clear();
    }

    /** For tests: the back list, oldest first. */
    List<P> backList() {
        return List.copyOf(back);
    }

    /** For tests: the forward list, the furthest first. */
    List<P> forwardList() {
        return List.copyOf(forward);
    }
}
