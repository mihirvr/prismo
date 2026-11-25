package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CopyGroupsTest {

    private static final long MB = 1024 * 1024;

    /** File A: ids 1, 2, 3 (2 added first); file B: ids 4, 5 (same time: lower id first), plus 6, hidden (not shown). */
    private static final CopyGroups GROUPS = new CopyGroups(List.of(
            new CopyGroups.Copy(1, "a", 2 * MB, 300),
            new CopyGroups.Copy(2, "a", 2 * MB, 100),
            new CopyGroups.Copy(3, "a", 2 * MB, 200),
            new CopyGroups.Copy(4, "b", 5 * MB, 50),
            new CopyGroups.Copy(5, "b", 5 * MB, 50),
            new CopyGroups.Copy(6, "b", 5 * MB, 10)));

    private static TimelineItem item(long id) {
        return new TimelineItem(new MediaRef(id, Path.of("photos", "p" + id + ".jpg"), "jpg", 0), 0, 0, 0, false, false,
                false);
    }

    private static List<TimelineItem> items(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(CopyGroupsTest::item).toList();
    }

    private static List<Long> ids(List<TimelineItem> some) {
        return some.stream().map(i -> i.ref().id()).toList();
    }

    /** What the page shows: 6 is hidden, 7 has no copy group (found after the load). */
    private static final List<TimelineItem> SHOWN = items(1, 2, 3, 4, 5);

    @Test
    void theCopyAddedFirstStaysAmongThoseShown() {
        Map<String, Long> keepers = GROUPS.keepers(SHOWN, Map.of());
        assertEquals(Map.of("a", 2L, "b", 4L), keepers, "first added; level: the lower id; 6 isn't shown");
        assertEquals(List.of(1L, 3L, 5L), ids(GROUPS.extras(SHOWN, keepers)));
    }

    @Test
    void keepThisCopyWinsWhileItIsShown() {
        Map<String, Long> keepers = GROUPS.keepers(SHOWN, Map.of("a", 3L, "b", 6L));
        assertEquals(Map.of("a", 3L, "b", 4L), keepers, "6 was chosen but isn't shown: the rule decides");
        assertEquals(List.of(1L, 2L, 5L), ids(GROUPS.extras(SHOWN, keepers)));
    }

    @Test
    void aSectionGivesOnlyItsOwnExtras() {
        Map<String, Long> keepers = GROUPS.keepers(SHOWN, Map.of());
        assertEquals(List.of(3L), ids(GROUPS.extras(items(2, 3, 7), keepers)), "2 stays, 7 has no copy");
        assertEquals(List.of(1L), ids(GROUPS.extras(items(1), keepers)), "the keeper is in another section");
    }

    @Test
    void warnsOnlyWhenNoCopyIsLeftAnywhere() {
        assertEquals(Set.of("a"), GROUPS.leftWithoutCopy(items(1, 2, 3, 4, 5)),
                "every copy of A; B still has hidden 6");
        assertEquals(Set.of(), GROUPS.leftWithoutCopy(items(1, 3, 5)));
    }

    @Test
    void othersShownAndSummary() {
        assertEquals(List.of(1L, 2L), ids(GROUPS.othersShown(3, SHOWN)));
        assertEquals(List.of(), ids(GROUPS.othersShown(7, items(7, 1))), "no group: no others");
        assertNull(GROUPS.groupOf(7));
        CopyGroups.Summary summary = GROUPS.summary(SHOWN, GROUPS.keepers(SHOWN, Map.of()));
        assertEquals(new CopyGroups.Summary(3, 2, 9 * MB), summary, "1 and 3 of A, 5 of B");
    }

    @Test
    void aFileIsLabelledByItsFirstCopy() {
        assertEquals("p4.jpg  ·  5.0 MB each", GROUPS.label(items(4, 5)));
        assertEquals("p7.jpg", GROUPS.label(items(7)), "no group yet: the name only");
    }

    @Test
    void aFileWithoutAnotherCopyOnThePageIsLeftOut() {
        assertEquals(List.of(1L, 2L, 4L, 5L), ids(GROUPS.withCopiesShown(items(1, 2, 4, 5))));
        assertEquals(List.of(1L, 3L), ids(GROUPS.withCopiesShown(items(1, 3, 4, 7))),
                "4's other copies are hidden or filtered out; 7 has no group");
    }

    @Test
    void sizeTexts() {
        assertEquals("1 KB", CopyGroups.sizeText(10));
        assertEquals("340 KB", CopyGroups.sizeText(340 * 1024));
        assertEquals("4.2 MB", CopyGroups.sizeText((long) (4.2 * MB)));
        assertEquals("1.5 GB", CopyGroups.sizeText(1536 * MB));
    }
}
