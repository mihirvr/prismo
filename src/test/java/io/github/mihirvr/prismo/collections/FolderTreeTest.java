package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.ui.MediaFilter;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderTreeTest {

    @TempDir
    Path tmp;

    @Test
    void foldersWithoutMediaOfTheirOwnFillTheGapsAndParentsAddUpEverythingBelow() {
        Path photos = tmp.resolve("Photos");
        Map<Path, Integer> counts = Map.of(
                photos, 2,
                photos.resolve("2024").resolve("Goa"), 5,
                photos.resolve("2024").resolve("beach"), 1,
                photos.resolve("2023"), 3);

        List<FolderTree.Node> tree = FolderTree.build(List.of(photos), counts);

        assertEquals(1, tree.size());
        FolderTree.Node top = tree.getFirst();
        assertEquals(photos.toString(), top.name(), "a library folder shows its whole path");
        assertEquals(2, top.ownCount());
        assertEquals(11, top.totalCount());
        assertEquals(List.of("2023", "2024"), top.children().stream().map(FolderTree.Node::name).toList());
        FolderTree.Node y2024 = top.children().get(1);
        assertEquals(0, y2024.ownCount(), "no media of its own, but it is in the tree");
        assertEquals(6, y2024.totalCount());
        assertEquals(List.of("beach", "Goa"), y2024.children().stream().map(FolderTree.Node::name).toList(),
                "names sorted ignoring case");
        assertEquals(photos.resolve("2024").resolve("Goa").toString(), y2024.children().get(1).path());
        assertEquals(List.of(), y2024.children().get(1).children());
    }

    @Test
    void aLibraryFolderShowsOnlyItsLastPartAndADriveStaysWhole() {
        Path photos = tmp.resolve("Photos").resolve("Holidays");
        FolderTree.Node top = FolderTree.build(List.of(photos), Map.of(photos, 1)).getFirst();

        assertEquals("Holidays", top.shownName(), "the whole path is its name (for sorting), not what's shown");
        Path drive = tmp.getRoot();
        assertEquals(drive.toString(), new FolderTree.Node(drive.toString(), drive.toString(), 0, 0, List.of())
                .shownName(), "a drive has no last part");
    }

    @Test
    void libraryFoldersStayInTheirOrderAndStrayFoldersComeLast() {
        Path b = tmp.resolve("b");
        Path a = tmp.resolve("a");
        Path stray = tmp.resolve("elsewhere").resolve("x");
        Path empty = tmp.resolve("empty");
        List<FolderTree.Node> tree = FolderTree.build(List.of(b, empty, a),
                Map.of(a.resolve("in-a"), 1, b, 4, stray, 2));

        assertEquals(List.of(b.toString(), a.toString(), stray.toString()),
                tree.stream().map(FolderTree.Node::path).toList(), "a library folder with nothing in it is left out");
        assertEquals(1, tree.get(1).totalCount());
        assertEquals(2, tree.get(2).ownCount());
    }

    @Test
    void loadCountsWhatTheViewsShowWithTheFilter() throws Exception {
        Path photos = tmp.resolve("Photos");
        Path goa = photos.resolve("Goa");
        try (Database database = Database.open(tmp.resolve("tree.db"))) {
            database.write(c -> {
                try (PreparedStatement root = c.prepareStatement(
                        "INSERT INTO library_roots (path, added_at) VALUES (?, 0)")) {
                    root.setString(1, photos.toString());
                    root.executeUpdate();
                }
                try (PreparedStatement insert = c.prepareStatement("""
                        INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes, date_modified,
                                           date_added, hidden, trashed_at)
                        VALUES (?, ?, ?, ?, ?, 1, 0, 0, ?, ?)""")) {
                    Object[][] rows = {{goa, "a.jpg", "photo", 0, null}, {goa, "b.mp4", "video", 0, null},
                            {goa, "c.jpg", "photo", 1, null}, {goa, "d.jpg", "photo", 0, 1L},
                            {photos, "e.jpg", "photo", 0, null}};
                    for (Object[] row : rows) {
                        Path folder = (Path) row[0];
                        String name = (String) row[1];
                        insert.setString(1, folder.resolve(name).toString());
                        insert.setString(2, folder.toString());
                        insert.setString(3, name);
                        insert.setString(4, name.substring(name.indexOf('.') + 1));
                        insert.setString(5, (String) row[2]);
                        insert.setObject(6, row[3]);
                        insert.setObject(7, row[4]);
                        insert.executeUpdate();
                    }
                }
                return null;
            }).get();

            List<FolderTree.Node> all = database.read(c -> FolderTree.load(c, ViewOptions.DEFAULT));
            assertEquals(3, all.getFirst().totalCount(), "hidden and trashed items aren't counted");
            assertEquals(2, all.getFirst().children().getFirst().ownCount());
            List<FolderTree.Node> videos = database.read(c ->
                    FolderTree.load(c, ViewOptions.DEFAULT.withFilter(MediaFilter.VIDEOS)));
            assertEquals(1, videos.getFirst().totalCount());
            assertEquals(0, videos.getFirst().ownCount());
        }
    }
}
