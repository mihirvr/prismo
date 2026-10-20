package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WatchProgressTest {

    @Test
    void aVideoResumesWhereItWasLeft() {
        assertEquals(42, WatchProgress.resumeAt(42, 600));
        assertEquals(42, WatchProgress.resumeAt(42, 0), "length unknown (MKV before the first play): still resumes");
    }

    @Test
    void theFirstFewSecondsAndTheEndStartFromTheBeginning() {
        assertEquals(0, WatchProgress.resumeAt(0, 600));
        assertEquals(0, WatchProgress.resumeAt(4.9, 600), "under 5 s isn't worth resuming");
        assertEquals(5, WatchProgress.resumeAt(5, 600));
        assertEquals(569.9, WatchProgress.resumeAt(569.9, 600), 1e-9, "just under 95%");
        assertEquals(0, WatchProgress.resumeAt(570, 600), "95%: watched, so from the start");
        assertEquals(0, WatchProgress.resumeAt(600, 600));
    }

    @Test
    void watchedMeansNinetyFivePercentOfAKnownLength() {
        assertTrue(WatchProgress.isWatched(95, 100));
        assertFalse(WatchProgress.isWatched(94.9, 100));
        assertFalse(WatchProgress.isWatched(1000, 0), "no length, no verdict");
    }

    @Test
    void theThumbnailsBarShowsTheShareWatchedOrIsFullOnceWatched() {
        assertEquals(-1, WatchProgress.barFraction(0, 100), "not started: no bar");
        assertEquals(-1, WatchProgress.barFraction(4, 100));
        assertEquals(-1, WatchProgress.barFraction(50, 0), "length unknown: no bar");
        assertEquals(0.5, WatchProgress.barFraction(50, 100));
        assertEquals(1, WatchProgress.barFraction(96, 100), "watched: the whole bar");
        assertEquals(1, WatchProgress.barFraction(500, 100), "never more than the whole bar");
    }
}
