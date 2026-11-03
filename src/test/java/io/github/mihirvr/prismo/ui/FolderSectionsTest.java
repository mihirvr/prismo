package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.FolderArrange.Order;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FolderSectionsTest {

    private static final Path ROOT = Path.of("Photos");

    private static long nextId = 1;

    private static TimelineItem item(String relative, long sortDate) {
        Path path = ROOT.resolve(relative);
        return new TimelineItem(new MediaRef(nextId++, path, "jpg", 0), sortDate, 0, 0, false, false, false);
    }

    private static List<String> labels(List<FolderSections.Section> sections) {
        return sections.stream().map(FolderSections.Section::label).toList();
    }

    private static List<String> names(FolderSections.Section section) {
        return section.items().stream().map(i -> i.ref().path().getFileName().toString()).toList();
    }

    /** A library: two files at the top, a Goa folder with a deeper Day 1, and a Beach folder. */
    private static List<TimelineItem> library() {
        // in the Sort bar's order (newest first)
        return List.of(item("b.jpg", 90), item("Goa/g2.jpg", 80), item("Goa/Day 1/d1.jpg", 70),
                item("a.jpg", 60), item("Beach/s1.jpg", 50), item("Goa/g1.jpg", 10));
    }

    @Test
    void theGridRollsDeeperFoldersUpAndPutsTheTopFilesUnderTheFoldersOwnName() {
        List<FolderSections.Section> sections = FolderSections.build(library(), List.of(ROOT), true,
                FolderArrange.DEFAULT);

        assertEquals(List.of("Photos", "Beach", "Goa"), labels(sections), "own files first, then folders A to Z");
        assertEquals(List.of("b.jpg", "a.jpg"), names(sections.get(0)), "Same as Sort: the loaded order");
        assertEquals(List.of("g2.jpg", "d1.jpg", "g1.jpg"), names(sections.get(2)), "Day 1 rolls up into Goa");
        assertEquals(6, FolderSections.flatten(sections).size());
    }

    @Test
    void collectionsKeepsEveryFolderNamedByItsPathBelowTheTop() {
        List<FolderSections.Section> sections = FolderSections.build(library(), List.of(ROOT), false,
                FolderArrange.DEFAULT);

        assertEquals(List.of("Photos", "Beach", "Goa", "Goa / Day 1"), labels(sections),
                "a folder comes right before the folders inside it");
    }

    @Test
    void aDirectionNeverMovesABlockOnlyTheSwitchDoes() {
        FolderArrange zToA = FolderArrange.DEFAULT.withFolders(Order.NAME_DESC);
        assertEquals(List.of("Photos", "Goa", "Beach"),
                labels(FolderSections.build(library(), List.of(ROOT), true, zToA)));

        FolderArrange foldersFirst = zToA.withFoldersFirst(true);
        assertEquals(List.of("Goa", "Beach", "Photos"),
                labels(FolderSections.build(library(), List.of(ROOT), true, foldersFirst)));
    }

    @Test
    void foldersByDateUseTheirNewestOrOldestMedia() {
        List<TimelineItem> items = library();
        assertEquals(List.of("Photos", "Goa", "Beach"), labels(FolderSections.build(items, List.of(ROOT), true,
                FolderArrange.DEFAULT.withFolders(Order.NEWEST))), "Goa has the newest (80)");
        assertEquals(List.of("Photos", "Goa", "Beach"), labels(FolderSections.build(items, List.of(ROOT), true,
                FolderArrange.DEFAULT.withFolders(Order.OLDEST))), "Goa also has the oldest (10)");
    }

    @Test
    void topFilesAndFolderFilesHaveTheirOwnOrder() {
        FolderArrange arrange = FolderArrange.DEFAULT.withTopFiles(Order.NAME_ASC).withFolderFiles(Order.OLDEST);
        List<FolderSections.Section> sections = FolderSections.build(library(), List.of(ROOT), true, arrange);

        assertEquals(List.of("a.jpg", "b.jpg"), names(sections.get(0)));
        assertEquals(List.of("g1.jpg", "d1.jpg", "g2.jpg"), names(sections.get(2)));
    }

    @Test
    void severalLibraryFoldersEachGetTheirOwnTwoBlocks() {
        Path other = Path.of("Camera");
        List<TimelineItem> items = new ArrayList<>(library());
        items.add(new TimelineItem(new MediaRef(nextId++, other.resolve("x.jpg"), "jpg", 0), 5, 0, 0, false, false,
                false));
        List<FolderSections.Section> sections = FolderSections.build(items, List.of(ROOT, other), true,
                FolderArrange.DEFAULT);

        assertEquals(List.of("Camera", "Photos", "Beach", "Goa"), labels(sections));
    }

    @Test
    void theArrangeIsKeptAsASettingAndBadValuesGiveTheDefault() {
        FolderArrange chosen = new FolderArrange(true, Order.NEWEST, Order.NAME_DESC, Order.SORT);
        assertEquals("folders,NEWEST,NAME_DESC,SORT", chosen.toSetting());
        assertEquals(chosen, FolderArrange.fromSetting(chosen.toSetting()));
        for (String bad : new String[] {null, "", "folders", "up,NEWEST,SORT,SORT", "files,GONE,SORT,SORT",
                "files,SORT,SORT,SORT", "files,NAME_ASC,SORT,SORT,extra"}) {
            assertEquals(FolderArrange.DEFAULT, FolderArrange.fromSetting(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new FolderArrange(false, Order.SORT, Order.SORT, Order.SORT), "folders need their own order");
    }

    @Test
    void sectionRowsHaveAHeaderAndRowsOfColumnsAndSkipEmptySections() {
        List<FolderSections.Section> sections = List.of(
                new FolderSections.Section("Goa", library().subList(0, 5)),
                new FolderSections.Section("Empty", List.of()));
        List<TimelineRow> rows = TimelineRows.build(sections, 2);

        assertEquals(new TimelineRow.Header("Goa", 5), rows.getFirst());
        assertEquals(4, rows.size(), "a header and 3 rows (2 + 2 + 1); no header for an empty folder");
    }
}
