package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class SelectionTest {

    /** Items with ids 1..n, shown in that order. */
    private static List<TimelineItem> items(int n) {
        return LongStream.rangeClosed(1, n)
                .mapToObj(id -> new TimelineItem(new MediaRef(id, Path.of(id + ".jpg"), "jpg", 0), 0, 0, 0, false,
                        false, false))
                .toList();
    }

    private static List<Long> ids(List<TimelineItem> items) {
        return items.stream().map(item -> item.ref().id()).toList();
    }

    @Test
    void ctrlClickAddsAndRemovesOneItem() {
        List<TimelineItem> shown = items(5);
        Selection selection = new Selection();
        assertTrue(selection.isEmpty());

        selection.toggle(2);
        selection.toggle(4);
        assertEquals(List.of(2L, 4L), ids(selection.items(shown)));
        selection.toggle(2);
        assertEquals(List.of(4L), ids(selection.items(shown)));
        assertEquals(1, selection.size());
        assertTrue(selection.contains(4));
        assertFalse(selection.contains(2));
    }

    @Test
    void shiftClickSelectsFromTheAnchorInEitherDirection() {
        List<TimelineItem> shown = items(8);
        Selection selection = new Selection();
        selection.toggle(3); // the anchor

        selection.selectRange(shown, 6, false);
        assertEquals(List.of(3L, 4L, 5L, 6L), ids(selection.items(shown)));
        selection.selectRange(shown, 1, false);
        assertEquals(List.of(1L, 2L, 3L), ids(selection.items(shown)), "replaces the range, anchor stays at 3");
    }

    @Test
    void ctrlShiftClickAddsTheRangeToWhatIsSelected() {
        List<TimelineItem> shown = items(8);
        Selection selection = new Selection();
        selection.toggle(1);
        selection.toggle(6); // anchor = 6

        selection.selectRange(shown, 8, true);
        assertEquals(List.of(1L, 6L, 7L, 8L), ids(selection.items(shown)));
    }

    @Test
    void withoutAnAnchorARangeIsJustTheClickedItem() {
        List<TimelineItem> shown = items(4);
        Selection selection = new Selection();

        selection.selectRange(shown, 3, false);
        assertEquals(List.of(3L), ids(selection.items(shown)));
        selection.selectRange(shown, 1, false);
        assertEquals(List.of(1L, 2L, 3L), ids(selection.items(shown)), "3 became the anchor");
        selection.selectRange(shown, 99, false);
        assertEquals(3, selection.size(), "an item not shown changes nothing");
    }

    @Test
    void anchorAtStartsARangeOnlyWhenThereIsNoneYet() {
        List<TimelineItem> shown = items(6);
        Selection selection = new Selection();
        selection.anchorAt(2); // Shift + arrow from 2
        selection.selectRange(shown, 4, false);
        assertEquals(List.of(2L, 3L, 4L), ids(selection.items(shown)));

        selection.anchorAt(4); // the next Shift + arrow keeps the first anchor
        selection.selectRange(shown, 5, false);
        assertEquals(List.of(2L, 3L, 4L, 5L), ids(selection.items(shown)));
    }

    @Test
    void selectAllAndClear() {
        List<TimelineItem> shown = items(5);
        Selection selection = new Selection();
        selection.selectAll(shown);
        assertEquals(5, selection.size());

        selection.clear();
        assertTrue(selection.isEmpty());
        selection.selectRange(shown, 4, false);
        assertEquals(List.of(4L), ids(selection.items(shown)), "clear also forgets the anchor");
    }

    @Test
    void aReloadKeepsOnlyWhatIsStillShown() {
        Selection selection = new Selection();
        selection.selectAll(items(5));
        selection.toggle(5); // unselect 5: the anchor is 5

        List<TimelineItem> after = items(3); // 4 and 5 were hidden elsewhere
        assertTrue(selection.retainShown(after));
        assertEquals(List.of(1L, 2L, 3L), ids(selection.items(after)));
        assertFalse(selection.retainShown(after), "nothing more to drop");
        selection.selectRange(after, 2, false);
        assertEquals(List.of(2L), ids(selection.items(after)), "the anchor went with item 5");
        assertFalse(new Selection().retainShown(after), "an empty selection never changes");
    }

    @Test
    void itemsComeInTheOrderShownNotTheOrderPicked() {
        List<TimelineItem> shown = items(5);
        Selection selection = new Selection();
        selection.toggle(5);
        selection.toggle(1);
        selection.toggle(3);
        assertEquals(List.of(1L, 3L, 5L), ids(selection.items(shown)));
    }
}
