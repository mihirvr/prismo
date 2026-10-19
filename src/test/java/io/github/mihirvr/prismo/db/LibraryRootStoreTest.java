package io.github.mihirvr.prismo.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryRootStoreTest {

    @TempDir
    Path tmp;

    private Database database;
    private LibraryRootStore roots;

    @BeforeEach
    void open() throws Exception {
        database = Database.open(tmp.resolve("prismo.db"));
        roots = new LibraryRootStore(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void addsFoldersInOrderAsRealPaths() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("A"));
        Path b = Files.createDirectories(tmp.resolve("B"));

        roots.add(b).get();
        roots.add(a.resolve("..").resolve("A")).get(); // non-normalised spelling of A

        assertEquals(List.of(b.toRealPath(), a.toRealPath()), roots.enabledRoots());
    }

    @Test
    void addingTheSameFolderTwiceKeepsOneRowAndTurnsItBackOn() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("A")).toRealPath();

        roots.add(a).get();
        roots.setEnabled(a, false).get();
        assertEquals(List.of(), roots.enabledRoots());
        LibraryRootStore.Added again = roots.add(a).get();

        assertTrue(again.added());
        assertEquals(List.of(a), roots.enabledRoots());
        assertEquals(1, roots.all().size());
    }

    @Test
    void missingFolderIsRejected() {
        assertThrows(IOException.class, () -> roots.add(tmp.resolve("nope")));
    }

    @Test
    void aFolderInsideALibraryFolderIsRefused() throws Exception {
        Path photos = Files.createDirectories(tmp.resolve("Photos")).toRealPath();
        Path inside = Files.createDirectories(photos.resolve("2024").resolve("Goa"));
        Path lookalike = Files.createDirectories(tmp.resolve("Photos 2")).toRealPath();
        roots.add(photos).get();

        LibraryRootStore.Added refused = roots.add(inside).get();
        assertFalse(refused.added());
        assertEquals(photos, refused.insideOf());
        assertTrue(roots.add(lookalike).get().added(), "\"Photos 2\" is not inside \"Photos\"");
        assertEquals(List.of(photos, lookalike), roots.enabledRoots());
    }

    @Test
    void aParentFolderReplacesTheLibraryFoldersInsideIt() throws Exception {
        Path parent = Files.createDirectories(tmp.resolve("Pictures")).toRealPath();
        Path a = Files.createDirectories(parent.resolve("A")).toRealPath();
        Path b = Files.createDirectories(parent.resolve("B")).toRealPath();
        Path other = Files.createDirectories(tmp.resolve("Other")).toRealPath();
        roots.add(a).get();
        roots.add(other).get();
        roots.add(b).get();
        roots.setEnabled(b, false).get();
        insert(a.resolve("x.jpg"));

        LibraryRootStore.Added added = roots.add(parent).get();

        assertTrue(added.added());
        assertNull(added.insideOf());
        assertEquals(List.of(a, b), added.absorbed());
        assertEquals(List.of(other, parent), roots.all().stream().map(LibraryRootStore.Root::path).toList());
        assertEquals(1, count("SELECT count(*) FROM media"), "the inner folders' rows are kept");
    }

    @Test
    void removingForgetsItsRowsButNotOthersOrLookalikes() throws Exception {
        Path photos = Files.createDirectories(tmp.resolve("Photos")).toRealPath();
        Path lookalike = Files.createDirectories(tmp.resolve("Photos 2")).toRealPath();
        roots.add(photos).get();
        roots.add(lookalike).get();
        insert(photos.resolve("a.jpg"));
        insert(photos.resolve("2024").resolve("b.jpg"));
        insert(lookalike.resolve("c.jpg"));

        assertEquals(List.of(2, 1), roots.counts(roots.all()));
        assertEquals(2, roots.remove(photos).get());

        assertEquals(List.of(lookalike), roots.enabledRoots());
        assertEquals(1, count("SELECT count(*) FROM media"));
        assertEquals(1, count("SELECT count(*) FROM media WHERE file_name = 'c.jpg'"));
    }

    @Test
    void removingAFolderThatHoldsAnOlderNestedOneKeepsThatOnesRows() throws Exception {
        // Nested library folders can't be made any more, but a library from before #9 may have them.
        Path outer = Files.createDirectories(tmp.resolve("Outer")).toRealPath();
        Path inner = Files.createDirectories(outer.resolve("Inner")).toRealPath();
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("INSERT INTO library_roots (path, added_at) VALUES (?, 0)")) {
                for (Path p : List.of(outer, inner)) {
                    insert.setString(1, p.toString());
                    insert.executeUpdate();
                }
            }
            return null;
        }).get();
        insert(outer.resolve("a.jpg"));
        insert(inner.resolve("b.jpg"));

        assertEquals(1, roots.remove(outer).get(), "only the row not under Inner");
        assertEquals(1, count("SELECT count(*) FROM media WHERE file_name = 'b.jpg'"));
        assertEquals(1, roots.remove(inner).get(), "then Inner's own row");
    }

    @Test
    void disabledFoldersAreLeftOutByTheSharedCondition() throws Exception {
        Path photos = Files.createDirectories(tmp.resolve("Photos")).toRealPath();
        Path lookalike = Files.createDirectories(tmp.resolve("Photos 2")).toRealPath();
        roots.add(photos).get();
        roots.add(lookalike).get();
        insert(photos.resolve("a.jpg"));
        insert(photos.resolve("2024").resolve("b.jpg"));
        insert(lookalike.resolve("c.jpg"));
        String visible = "SELECT count(*) FROM media WHERE " + LibraryRootStore.NOT_IN_DISABLED_FOLDER;

        assertEquals(3, count(visible), "nothing disabled: everything");
        roots.setEnabled(photos, false).get();
        assertEquals(1, count(visible), "Photos and below left out, \"Photos 2\" kept");
        assertEquals(List.of(lookalike), roots.enabledRoots());
        assertEquals(3, count("SELECT count(*) FROM media"), "the rows are kept");
        roots.setEnabled(photos, true).get();
        assertEquals(3, count(visible));
    }

    @Test
    void aDisabledDriveRootLeavesOutEverythingOnThatDrive() throws Exception {
        Path drive = tmp.toRealPath().getRoot();
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO library_roots (path, enabled, added_at) VALUES (?, 0, 0)")) {
                insert.setString(1, drive.toString());
                insert.executeUpdate();
            }
            return null;
        }).get();
        insert(drive.resolve("top.jpg"));
        insert(tmp.resolve("deep.jpg"));

        assertEquals(0, count("SELECT count(*) FROM media WHERE " + LibraryRootStore.NOT_IN_DISABLED_FOLDER));
        assertEquals(List.of(drive.toString(), drive.toString(),
                drive.toString().substring(0, drive.toString().length() - 1) + (char) (File.separatorChar + 1)),
                LibraryRootStore.range(drive));
    }

    private void insert(Path file) throws Exception {
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes, date_modified, date_added)
                    VALUES (?, ?, ?, 'jpg', 'photo', 1, 0, 0)""")) {
                insert.setString(1, file.toString());
                insert.setString(2, file.getParent().toString());
                insert.setString(3, file.getFileName().toString());
                insert.executeUpdate();
            }
            return null;
        }).get();
    }

    private int count(String sql) throws Exception {
        return database.read(c -> {
            List<Integer> got = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement(sql); ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    got.add(rows.getInt(1));
                }
            }
            return got.getFirst();
        });
    }
}
