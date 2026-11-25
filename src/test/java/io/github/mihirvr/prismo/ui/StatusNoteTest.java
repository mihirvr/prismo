package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatusNoteTest {

    @Test
    void workShowsOnlyAfterTheDelayAndGoesWhenDone() {
        StatusNote.State state = new StatusNote.State();
        assertEquals("", state.text(), "nothing to say: hidden");
        assertTrue(state.progress("Looking… 10 found"), "new work starts the delay");
        assertEquals("", state.text(), "a quick rescan shows nothing");
        assertFalse(state.progress("Looking… 20 found"), "the same work: the delay keeps running");
        state.delayPassed();
        assertEquals("Looking… 20 found", state.text());
        state.progress(null);
        assertEquals("", state.text(), "done: hidden again");
        state.delayPassed(); // a late timer after the work ended
        assertTrue(state.progress("Making thumbnails… 0 of 5"));
        assertEquals("", state.text(), "the next work waits for its own delay");
    }

    @Test
    void aMessageShowsAtOnceThenTheWorkComesBack() {
        StatusNote.State state = new StatusNote.State();
        state.progress("Making thumbnails… 3 of 9");
        state.delayPassed();
        state.say("  Moved 2 items to Trash.  ");
        assertEquals("Moved 2 items to Trash.", state.text());
        state.messageEnded();
        assertEquals("Making thumbnails… 3 of 9", state.text());
        state.say("");
        assertEquals("Making thumbnails… 3 of 9", state.text(), "an empty message says nothing");
    }

    @Test
    void textsAreShortAndCountOnlyWhatIsRunning() {
        assertEquals("Looking for photos and videos… 1,234 found", StatusNote.State.indexing(1234));
        assertEquals("Making thumbnails… 120 of 266", StatusNote.State.thumbnails(120, 266, 0, false));
        assertEquals("Making thumbnails… 120 of 266  ·  2 couldn't be made",
                StatusNote.State.thumbnails(120, 266, 2, false));
        assertTrue(StatusNote.State.thumbnails(1, 40_000, 0, true).endsWith("photos may open slowly until this finishes"));
    }

    @Test
    void theDuplicatesCheckHasItsOwnLine() {
        StatusNote.State state = new StatusNote.State();
        assertTrue(state.progress(StatusNote.State.Work.DUPLICATES, "Looking for copies… 1 of 4 files"));
        assertFalse(state.progress("Making thumbnails… 3 of 9"), "work already runs: no new delay");
        state.delayPassed();
        assertEquals("Making thumbnails… 3 of 9\nLooking for copies… 1 of 4 files", state.text());
        state.progress(null);
        assertEquals("Looking for copies… 1 of 4 files", state.text(), "thumbnails done: the check stays");
        state.progress(StatusNote.State.Work.DUPLICATES, null);
        assertFalse(state.working());
        assertEquals("", state.text());
    }

    @Test
    void duplicatesTextCountsFilesAndSizeInOneUnit() {
        assertEquals("Looking for copies… 120 of 800 files  ·  1.2 of 4.5 GB",
                StatusNote.State.duplicates(120, 800, (long) (1.2 * (1L << 30)), (long) (4.5 * (1L << 30))));
        assertEquals("Looking for copies… 0 of 3 files  ·  0.0 of 2.0 MB",
                StatusNote.State.duplicates(0, 3, 0, 2L << 20));
    }
}
