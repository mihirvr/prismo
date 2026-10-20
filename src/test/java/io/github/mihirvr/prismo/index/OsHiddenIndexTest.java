package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.platform.OsHiddenMark;
import io.github.mihirvr.prismo.testing.TestMedia;
import io.github.mihirvr.prismo.ui.MediaScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The Windows Hidden attribute ({@link OsHiddenMark}) in the index: read on every look, even when nothing else changed. */
@EnabledOnOs(OS.WINDOWS)
class OsHiddenIndexTest {

    @TempDir
    Path tmp;

    private Path library;
    private Path photo;
    private Database database;
    private Indexer indexer;

    @BeforeEach
    void setUp() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("Pictures"));
        Files.createDirectories(folder.resolve("Trip"));
        TestMedia.png(folder.resolve("a.png"), 20, 10);
        TestMedia.png(folder.resolve("b.png"), 30, 10);
        library = folder.toRealPath();
        photo = library.resolve("a.png");
        database = Database.open(tmp.resolve("prismo.db"));
        indexer = new Indexer(database, new MetadataReader(java.time.ZoneOffset.UTC), 2);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void settingTheMarkLeavesTheFileAndItsModifiedTimeAlone() throws Exception {
        FileTime before = Files.getLastModifiedTime(photo);
        long size = Files.size(photo);

        OsHiddenMark.set(photo, true);

        assertTrue(OsHiddenMark.isHidden(OsHiddenMark.read(photo)));
        assertEquals(before, Files.getLastModifiedTime(photo), "only the attribute changes");
        assertEquals(size, Files.size(photo));
        OsHiddenMark.set(photo, false);
        assertEquals(false, OsHiddenMark.isHidden(OsHiddenMark.read(photo)));
    }

    @Test
    void aFileHiddenInExplorerIsIndexedWithTheMarkAndShowsOnlyOnHidden() throws Exception {
        OsHiddenMark.set(photo, true);

        indexer.index(List.of(library), _ -> { });

        assertEquals(1, count("SELECT os_hidden FROM media WHERE path = '" + photo + "'"));
        assertEquals(0, count("SELECT os_hidden FROM media WHERE path = '" + library.resolve("b.png") + "'"));
        assertEquals(1, count("SELECT count(*) FROM media WHERE " + MediaScope.LIBRARY.sqlCondition(false)));
        assertEquals(1, count("SELECT count(*) FROM media WHERE " + MediaScope.HIDDEN.sqlCondition(false)));
        // Settings › "Show files hidden in File Explorer" on: it's like any other file.
        assertEquals(2, count("SELECT count(*) FROM media WHERE " + MediaScope.LIBRARY.sqlCondition(true)));
        assertEquals(0, count("SELECT count(*) FROM media WHERE " + MediaScope.HIDDEN.sqlCondition(true)));
        assertEquals(0, count("SELECT hidden FROM media WHERE path = '" + photo + "'"), "Prismo's own Hide untouched");
    }

    @Test
    void aMarkOnlyChangeIsPickedUpByARescanWithoutReadingTheFileAgain() throws Exception {
        indexer.index(List.of(library), _ -> { });
        long id = count("SELECT id FROM media WHERE path = '" + photo + "'");
        write("UPDATE media SET date_added = 7, width = 999 WHERE id = " + id); // a re-read would reset width

        OsHiddenMark.set(photo, true);
        IndexProgress hidden = indexer.index(List.of(library), _ -> { });

        assertEquals(1, hidden.indexed(), "the attribute change counts as a change");
        assertEquals(1, hidden.unchanged());
        assertTrue(hidden.changedAnything(), "so the app reloads its views");
        assertEquals(1, count("SELECT os_hidden FROM media WHERE id = " + id));
        assertEquals(999, count("SELECT width FROM media WHERE id = " + id), "the file wasn't read again");
        assertEquals(7, count("SELECT date_added FROM media WHERE id = " + id));

        OsHiddenMark.set(photo, false);
        IndexProgress shown = indexer.index(List.of(library), _ -> { });

        assertEquals(1, shown.indexed());
        assertEquals(0, count("SELECT os_hidden FROM media WHERE id = " + id));
        assertEquals(0, indexer.index(List.of(library), _ -> { }).indexed(), "nothing changed since");
    }

    @Test
    void aWatcherUpdateForTheFilePicksUpTheMark() throws Exception {
        indexer.index(List.of(library), _ -> { });
        OsHiddenMark.set(photo, true);

        IndexProgress result = indexer.update(Map.of(library, Set.of(photo)), _ -> { });

        assertEquals(1, result.indexed());
        assertEquals(1, count("SELECT os_hidden FROM media WHERE path = '" + photo + "'"));
    }

    @Test
    void aMovedFileKeepsItsRowAndTakesTheMarkItHasNow() throws Exception {
        indexer.index(List.of(library), _ -> { });
        long id = count("SELECT id FROM media WHERE path = '" + photo + "'");
        write("UPDATE media SET favorite = 1 WHERE id = " + id);
        Path moved = Files.move(photo, library.resolve("Trip").resolve("a.png"));
        OsHiddenMark.set(moved, true);

        IndexProgress result = indexer.index(List.of(library), _ -> { });

        assertEquals(1, result.moved());
        assertEquals(id, count("SELECT id FROM media WHERE path = '" + moved + "'"));
        assertEquals(1, count("SELECT favorite FROM media WHERE id = " + id), "the user's state follows the file");
        assertEquals(1, count("SELECT os_hidden FROM media WHERE id = " + id), "the file's own mark, from now");
    }

    @Test
    void filesInAFolderMarkedHiddenCountAsHiddenButTheLibraryFolderItselfDoesNot() throws Exception {
        Path trip = library.resolve("Trip");
        Path inside = TestMedia.png(Files.createDirectories(trip.resolve("Day 1")).resolve("c.png"), 10, 10);
        OsHiddenMark.set(trip, true);
        OsHiddenMark.set(library, true); // a library folder picked on purpose: its own mark doesn't hide everything
        try {
            indexer.index(List.of(library), _ -> { });

            assertEquals(1, count("SELECT os_hidden FROM media WHERE path = '" + inside + "'"), "two folders down");
            assertEquals(0, count("SELECT os_hidden FROM media WHERE path = '" + photo + "'"));

            // The watcher reports the file alone: the folder's mark is still found.
            write("UPDATE media SET os_hidden = 0 WHERE path = '" + inside + "'");
            Files.setLastModifiedTime(inside, FileTime.fromMillis(Files.getLastModifiedTime(inside).toMillis() + 2000));
            indexer.update(Map.of(library, Set.of(inside)), _ -> { });
            assertEquals(1, count("SELECT os_hidden FROM media WHERE path = '" + inside + "'"));

            // The folder unhidden in Explorer: the next look shows the file again.
            OsHiddenMark.set(trip, false);
            indexer.update(Map.of(library, Set.of(trip)), _ -> { });
            assertEquals(0, count("SELECT os_hidden FROM media WHERE path = '" + inside + "'"));
        } finally {
            OsHiddenMark.set(trip, false);
            OsHiddenMark.set(library, false);
        }
    }

    private int count(String sql) throws Exception {
        return database.read(c -> {
            try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
                return rows.getInt(1);
            }
        });
    }

    private void write(String sql) throws Exception {
        database.write(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate(sql);
            }
            return null;
        }).get();
    }
}
