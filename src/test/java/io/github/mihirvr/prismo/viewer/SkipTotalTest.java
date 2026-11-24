package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SkipTotalTest {

    private static final long S = 1_000_000;          // one second in micros
    private static final long MS = 1_000_000;         // one millisecond in nanos

    @Test
    void quickSkipsAddUp() {
        SkipTotal total = new SkipTotal();
        assertEquals(5 * S, total.add(5 * S, 0));
        assertEquals(10 * S, total.add(5 * S, 300 * MS));
        assertEquals(15 * S, total.add(5 * S, 600 * MS));
    }

    @Test
    void backwardsTooAndMixedSizes() {
        SkipTotal total = new SkipTotal();
        assertEquals(-5 * S, total.add(-5 * S, 0));
        assertEquals(-15 * S, total.add(-10 * S, 500 * MS));
    }

    @Test
    void aPauseStartsAgain() {
        SkipTotal total = new SkipTotal();
        total.add(5 * S, 0);
        assertEquals(5 * S, total.add(5 * S, SkipTotal.PAUSE_NANOS + 1));
    }

    @Test
    void theOtherDirectionStartsAgain() {
        SkipTotal total = new SkipTotal();
        total.add(5 * S, 0);
        total.add(5 * S, 100 * MS);
        assertEquals(-5 * S, total.add(-5 * S, 200 * MS));
    }

    @Test
    void resetForgets() {
        SkipTotal total = new SkipTotal();
        total.add(5 * S, 0);
        total.reset();
        assertEquals(5 * S, total.add(5 * S, 100 * MS));
    }

    @Test
    void text() {
        assertEquals("+5 s", SkipTotal.text(5 * S));
        assertEquals("−15 s", SkipTotal.text(-15 * S));
        assertEquals("+1 min", SkipTotal.text(60 * S));
        assertEquals("+1 min 30 s", SkipTotal.text(90 * S));
        assertEquals("−2 min", SkipTotal.text(-120 * S));
    }
}
