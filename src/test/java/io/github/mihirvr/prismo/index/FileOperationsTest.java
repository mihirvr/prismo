package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.testing.TestMedia;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationsTest {

    @TempDir
    Path tmp;

    private Path library;
    private Path outside;
    private Database database;
    private FileOperations operations;

    @BeforeEach
    void setUp() throws Exception {
        library = Files.createDirectories(tmp.resolve("Pictures")).toRealPath();
        Files.createDirectories(library.resolve("Trip"));
        outside = Files.createDirectories(tmp.resolve("Elsewhere")).toRealPath();
        TestMedia.png(library.resolve("a.png"), 20, 10);
        TestMedia.png(library.resolve("b.png"), 30, 10);
        TestMedia.png(library.resolve("Trip").resolve("a.png"), 40, 10);
        database = Database.open(tmp.resolve("prismo.db"));
        new Indexer(database, new MetadataReader(ZoneOffset.UTC)).index(List.of(library), _ -> { });
        operations = new FileOperations(database, folder -> folder.startsWith(library));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void nameRules() {
        assertEquals(Optional.empty(), FileOperations.nameProblem("Goa beach.jpg"));
        assertTrue(FileOperations.nameProblem("  ").isPresent());
        assertTrue(FileOperations.nameProblem("a:b.jpg").orElseThrow().contains("\u201c:\u201d"));
        assertTrue(FileOperations.nameProblem("what?.jpg").isPresent());
        assertTrue(FileOperations.nameProblem("photo.").isPresent(), "ends with a dot");
        assertTrue(FileOperations.nameProblem("CON.jpg").isPresent(), "a device name");
        assertEquals(Optional.empty(), FileOperations.nameProblem("Console.jpg"));
    }

    @Test
    void freeNamesCountUp() {
        Set<String> taken = Set.of("a.png", "a (2).png", "notes");
        assertEquals("b.png", FileOperations.freeName("b.png", taken::contains));
        assertEquals("a (3).png", FileOperations.freeName("a.png", taken::contains));
        assertEquals("notes (2)", FileOperations.freeName("notes", taken::contains), "no extension");
    }

    @Test
    void renameKeepsTheRowAndEverythingOnIt() throws Exception {
        Path a = library.resolve("a.png");
        long id = number("SELECT id FROM media WHERE path = '" + a + "'");
        write("UPDATE media SET favorite = 1 WHERE id = " + id);

        Path renamed = operations.rename(id, a, "Beach day.png");

        assertEquals(library.resolve("Beach day.png"), renamed);
        assertTrue(Files.exists(renamed));
        assertFalse(Files.exists(a));
        assertEquals(id, number("SELECT id FROM media WHERE path = '" + renamed + "'"), "same row");
        assertEquals(1, number("SELECT favorite FROM media WHERE id = " + id));
        assertEquals(1, number("SELECT count(*) FROM media WHERE file_name = 'Beach day.png' AND folder = '" + library
                + "'"));
        assertEquals(1, number("SELECT count(*) FROM media_fts WHERE media_fts MATCH '\"beach\"'"), "search finds it");
        // A later index run agrees: nothing moved, nothing new.
        IndexProgress again = new Indexer(database, new MetadataReader(ZoneOffset.UTC)).index(List.of(library), _ -> { });
        assertEquals(0, again.indexed());
        assertEquals(0, again.removed());
    }

    @Test
    void renameRefusesATakenNameOrABadOneAndChangesNothing() throws Exception {
        Path a = library.resolve("a.png");
        long id = number("SELECT id FROM media WHERE path = '" + a + "'");

        IOException taken = assertThrows(IOException.class, () -> operations.rename(id, a, "b.png"));
        assertTrue(taken.getMessage().contains("already a file"), taken.getMessage());
        assertThrows(IOException.class, () -> operations.rename(id, a, "a|b.png"));
        assertTrue(Files.exists(a));
        assertEquals(id, number("SELECT id FROM media WHERE path = '" + a + "'"));
    }

    @Test
    void moveSkipsOrKeepsBothOnANameClash() throws Exception {
        Path a = library.resolve("a.png");
        Path b = library.resolve("b.png");
        long idA = number("SELECT id FROM media WHERE path = '" + a + "'");
        long idB = number("SELECT id FROM media WHERE path = '" + b + "'");
        Path trip = library.resolve("Trip");
        assertEquals(1, FileOperations.conflicts(List.of(a, b), trip), "Trip already has an a.png");

        FileOperations.Outcome skip = operations.move(List.of(idA, idB), List.of(a, b), trip,
                FileOperations.Conflict.SKIP);

        assertEquals(1, skip.moved());
        assertEquals(1, skip.skipped());
        assertEquals(java.util.Map.of(idB, trip.resolve("b.png")), skip.newPaths());
        assertTrue(Files.exists(a), "the clashing one stayed");
        assertEquals(idB, number("SELECT id FROM media WHERE path = '" + trip.resolve("b.png") + "'"));

        FileOperations.Outcome both = operations.move(List.of(idA), List.of(a), trip, FileOperations.Conflict.KEEP_BOTH);

        assertEquals(1, both.moved());
        assertEquals(idA, number("SELECT id FROM media WHERE path = '" + trip.resolve("a (2).png") + "'"));
        assertTrue(Files.exists(trip.resolve("a.png")), "the one already there is untouched");
    }

    @Test
    void movingOutOfTheLibraryLetsPrismoForgetTheFile() throws Exception {
        Path b = library.resolve("b.png");
        long id = number("SELECT id FROM media WHERE path = '" + b + "'");

        FileOperations.Outcome out = operations.move(List.of(id), List.of(b), outside, FileOperations.Conflict.SKIP);

        assertEquals(1, out.leftLibrary());
        assertTrue(Files.exists(outside.resolve("b.png")), "the file itself is fine, just elsewhere");
        assertEquals(0, number("SELECT count(*) FROM media WHERE id = " + id));
    }

    @Test
    void aMissingFileIsReportedAndTheRestStillMove() throws Exception {
        Path a = library.resolve("a.png");
        Path b = library.resolve("b.png");
        long idA = number("SELECT id FROM media WHERE path = '" + a + "'");
        long idB = number("SELECT id FROM media WHERE path = '" + b + "'");
        Files.delete(a);

        FileOperations.Outcome result = operations.move(List.of(idA, idB), List.of(a, b), outside.resolveSibling(
                "Pictures").resolve("Trip"), FileOperations.Conflict.KEEP_BOTH);

        assertEquals(1, result.failed());
        assertEquals(1, result.moved());
        assertTrue(result.problem().orElseThrow().contains("isn't there"), result.problem().orElseThrow());
    }

    private long number(String sql) throws Exception {
        return database.read(c -> {
            try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
                return rows.getLong(1);
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
