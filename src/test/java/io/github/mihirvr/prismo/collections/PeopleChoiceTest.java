package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.collections.PeopleChoice.Box;
import io.github.mihirvr.prismo.collections.PeopleChoice.Decision;
import io.github.mihirvr.prismo.collections.PeopleChoice.State;
import org.junit.jupiter.api.Test;

/** The People window's tick boxes (1.2.0). */
class PeopleChoiceTest {

    @Test
    void theStateComesFromTheCount() {
        assertEquals(State.ALL, PeopleChoice.state(5, 5));
        assertEquals(State.SOME, PeopleChoice.state(2, 5));
        assertEquals(State.NONE, PeopleChoice.state(0, 5));
        assertEquals(State.NONE, PeopleChoice.state(0, 0), "no files");
    }

    @Test
    void leftAloneTheBoxShowsWhatTheFilesHave() {
        assertEquals(Box.TICKED, PeopleChoice.box(State.ALL, Decision.KEEP));
        assertEquals(Box.DASH, PeopleChoice.box(State.SOME, Decision.KEEP));
        assertEquals(Box.EMPTY, PeopleChoice.box(State.NONE, Decision.KEEP));
    }

    @Test
    void someCyclesThroughAddRemoveAndBackToAsItIs() {
        Decision d = Decision.KEEP;
        d = PeopleChoice.click(State.SOME, d);
        assertEquals(Decision.ADD, d);
        d = PeopleChoice.click(State.SOME, d);
        assertEquals(Decision.REMOVE, d);
        d = PeopleChoice.click(State.SOME, d);
        assertEquals(Decision.KEEP, d, "back to the dash: no change");
    }

    @Test
    void allAndNoneToggle() {
        assertEquals(Decision.REMOVE, PeopleChoice.click(State.ALL, Decision.KEEP));
        assertEquals(Decision.KEEP, PeopleChoice.click(State.ALL, Decision.REMOVE));
        assertEquals(Decision.ADD, PeopleChoice.click(State.NONE, Decision.KEEP));
        assertEquals(Decision.KEEP, PeopleChoice.click(State.NONE, Decision.ADD));
    }

    @Test
    void everyClickChangesTheBoxEvenAfterTheFilesChanged() {
        // Decided ADD while some had them; then the subfolders changed and now all have them.
        assertEquals(Box.EMPTY, PeopleChoice.box(State.ALL, PeopleChoice.click(State.ALL, Decision.ADD)));
        // Decided REMOVE; now none have them.
        assertEquals(Box.TICKED, PeopleChoice.box(State.NONE, PeopleChoice.click(State.NONE, Decision.REMOVE)));
    }

    @Test
    void changesCountOnlyTheFilesThatChange() {
        assertEquals(3, PeopleChoice.changes(Decision.ADD, 2, 5), "the 3 without them");
        assertEquals(2, PeopleChoice.changes(Decision.REMOVE, 2, 5), "the 2 with them");
        assertEquals(0, PeopleChoice.changes(Decision.KEEP, 2, 5));
    }
}
