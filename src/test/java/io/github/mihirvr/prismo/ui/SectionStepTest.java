package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.ui.TimelineView.SectionStep;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The header circle's clicks (1.4.1): select all / unselect, and on Duplicates extra copies, then all, then none. */
class SectionStepTest {

    private static final List<Long> SECTION = List.of(1L, 2L, 3L);

    @Test
    void everyPageButDuplicatesSelectsAllThenNone() {
        assertEquals(SectionStep.PICKED, TimelineView.nextStep(Set.of(), SECTION, SECTION, false));
        assertEquals(SectionStep.PICKED, TimelineView.nextStep(Set.of(2L), SECTION, SECTION, false), "some: all");
        assertEquals(SectionStep.NONE, TimelineView.nextStep(Set.of(1L, 2L, 3L, 9L), SECTION, SECTION, false));
    }

    @Test
    void duplicatesGoesExtrasThenEveryCopyThenNone() {
        List<Long> extras = List.of(2L, 3L); // 1 is the copy that stays
        assertEquals(SectionStep.PICKED, TimelineView.nextStep(Set.of(), SECTION, extras, true));
        assertEquals(SectionStep.ALL, TimelineView.nextStep(Set.of(2L, 3L), SECTION, extras, true));
        assertEquals(SectionStep.NONE, TimelineView.nextStep(Set.of(1L, 2L, 3L), SECTION, extras, true));
        assertEquals(SectionStep.PICKED, TimelineView.nextStep(Set.of(1L), SECTION, extras, true),
                "only the kept copy picked by hand: back to the extras");
    }

    @Test
    void aSectionOfOnlyKeptOrOnlyExtraCopiesHasTwoSteps() {
        assertEquals(SectionStep.ALL, TimelineView.nextStep(Set.of(), SECTION, List.of(), true), "nothing extra");
        assertEquals(SectionStep.NONE, TimelineView.nextStep(Set.of(1L, 2L, 3L), SECTION, SECTION, true),
                "all extra: once selected, the next click unselects");
    }
}
