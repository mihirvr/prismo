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
        assertEquals("Making thumbnails… 120 of 266", StatusNote.State.thumbnails(120, 266, false));
        assertTrue(StatusNote.State.thumbnails(1, 40_000, true).endsWith("photos may open slowly until this finishes"));
    }
}
