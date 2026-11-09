package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.collections.TagChoice.Box;
import io.github.mihirvr.prismo.collections.TagChoice.Decision;
import io.github.mihirvr.prismo.collections.TagChoice.State;
import org.junit.jupiter.api.Test;

/** The tag window's tick boxes (People 1.2.0, Categories 1.3.0). */
class TagChoiceTest {

    @Test
    void theStateComesFromTheCount() {
        assertEquals(State.ALL, TagChoice.state(5, 5));
        assertEquals(State.SOME, TagChoice.state(2, 5));
        assertEquals(State.NONE, TagChoice.state(0, 5));
        assertEquals(State.NONE, TagChoice.state(0, 0), "no files");
    }

    @Test
    void leftAloneTheBoxShowsWhatTheFilesHave() {
        assertEquals(Box.TICKED, TagChoice.box(State.ALL, Decision.KEEP));
        assertEquals(Box.DASH, TagChoice.box(State.SOME, Decision.KEEP));
        assertEquals(Box.EMPTY, TagChoice.box(State.NONE, Decision.KEEP));
    }

    @Test
    void someCyclesThroughAddRemoveAndBackToAsItIs() {
        Decision d = Decision.KEEP;
        d = TagChoice.click(State.SOME, d);
        assertEquals(Decision.ADD, d);
        d = TagChoice.click(State.SOME, d);
        assertEquals(Decision.REMOVE, d);
        d = TagChoice.click(State.SOME, d);
        assertEquals(Decision.KEEP, d, "back to the dash: no change");
    }

    @Test
    void allAndNoneToggle() {
        assertEquals(Decision.REMOVE, TagChoice.click(State.ALL, Decision.KEEP));
        assertEquals(Decision.KEEP, TagChoice.click(State.ALL, Decision.REMOVE));
        assertEquals(Decision.ADD, TagChoice.click(State.NONE, Decision.KEEP));
        assertEquals(Decision.KEEP, TagChoice.click(State.NONE, Decision.ADD));
    }

    @Test
    void everyClickChangesTheBoxEvenAfterTheFilesChanged() {
        // Decided ADD while some had them; then the subfolders changed and now all have them.
        assertEquals(Box.EMPTY, TagChoice.box(State.ALL, TagChoice.click(State.ALL, Decision.ADD)));
        // Decided REMOVE; now none have them.
        assertEquals(Box.TICKED, TagChoice.box(State.NONE, TagChoice.click(State.NONE, Decision.REMOVE)));
    }

    @Test
    void changesCountOnlyTheFilesThatChange() {
        assertEquals(3, TagChoice.changes(Decision.ADD, 2, 5), "the 3 without them");
        assertEquals(2, TagChoice.changes(Decision.REMOVE, 2, 5), "the 2 with them");
        assertEquals(0, TagChoice.changes(Decision.KEEP, 2, 5));
    }
}
