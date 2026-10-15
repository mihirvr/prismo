package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexerTest {

    @TempDir
    Path tmp;

    private Path library;
    private Database database;
    private Indexer indexer;

    @BeforeEach
    void setUp() throws Exception {
        library = Files.createDirectories(tmp.resolve("Pictures"));
        Files.createDirectories(library.resolve("Trip").resolve("Day 1"));
        TestMedia.jpegWithExif(library.resolve("a.jpg"), 40, 30, "Canon", "EOS R6", 1, "2023:07:14 18:30:05");
        TestMedia.png(library.resolve("b.png"), 20, 10);
        Files.writeString(library.resolve("notes.txt"), "not media");
        TestMedia.mp4(library.resolve("Trip").resolve("clip.MP4"), Instant.parse("2024-01-15T18:00:00Z"), 3.0);
        TestMedia.png(library.resolve("Trip").resolve("Day 1").resolve("c.PNG"), 8, 8);

        database = Database.open(tmp.resolve("prismo.db"));
        // Batch size 2 so a 4-file library exercises several chunks and the "one write in flight" path.
        indexer = new Indexer(database, new MetadataReader(ZoneOffset.UTC), 2);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void indexesMediaRecursivelyAndIgnoresOtherFiles() throws Exception {
        IndexProgress result = indexer.index(List.of(library), progress -> { });

        assertEquals(4, result.found());
        assertEquals(4, result.indexed());
        assertEquals(0, result.failed());
        assertTrue(result.done());
        assertEquals(4, count("SELECT count(*) FROM media"));
        assertEquals(1, count("SELECT count(*) FROM media WHERE media_type = 'video' AND duration_seconds = 3.0"));
        assertEquals(1, count("SELECT count(*) FROM media WHERE folder = '"
                + library.toRealPath().resolve("Trip").resolve("Day 1") + "'"));
    }

    @Test
    void secondRunSkipsUnchangedFiles() throws Exception {
        indexer.index(List.of(library), progress -> { });

        IndexProgress second = indexer.index(List.of(library), progress -> { });

        assertEquals(4, second.found());
        assertEquals(0, second.indexed());
        assertEquals(4, second.unchanged());
    }

    @Test
    void changedFileIsReindexedButUserStateIsKept() throws Exception {
        indexer.index(List.of(library), progress -> { });
        Path png = library.toRealPath().resolve("b.png");
        long addedBefore = longValue("SELECT date_added FROM media WHERE path = ?", png.toString());
        database.write(c -> {
            try (Statement s = c.createStatement()) {
                return s.executeUpdate("UPDATE media SET favorite = 1 WHERE file_name = 'b.png'");
            }
        }).get();

        TestMedia.png(png, 50, 25); // same name, new content and size
        Files.setLastModifiedTime(png, FileTime.from(Instant.parse("2025-05-05T05:05:05Z")));
        IndexProgress again = indexer.index(List.of(library), progress -> { });

        assertEquals(1, again.indexed());
        assertEquals(3, again.unchanged());
        assertEquals(50, longValue("SELECT width FROM media WHERE path = ?", png.toString()));
        assertEquals(1, longValue("SELECT favorite FROM media WHERE path = ?", png.toString()), "favorite kept");
        assertEquals(addedBefore, longValue("SELECT date_added FROM media WHERE path = ?", png.toString()),
                "date_added kept");
    }

    @Test
    void reportsProgressAndEndsWithDone() throws Exception {
        List<IndexProgress> updates = new ArrayList<>();

        indexer.index(List.of(library), updates::add);

        assertTrue(updates.size() >= 2, "at least one in-progress update and the final one");
        IndexProgress last = updates.get(updates.size() - 1);
        assertTrue(last.done());
        assertEquals(4, last.found());
    }

    @Test
    void missingRootIsCountedAsFailedNotFatal() throws Exception {
        IndexProgress result = indexer.index(List.of(tmp.resolve("does-not-exist"), library), progress -> { });

        assertEquals(1, result.failed());
        assertEquals(4, result.indexed());
    }

    @Test
    void interruptedThreadStopsTheRun() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> indexer.index(List.of(library), progress -> { }));
        } finally {
            Thread.interrupted(); // clear the flag for the next test
        }
    }

    @Test
    void pathRangeQueryOnlyMatchesFilesInsideTheRoot() throws Exception {
        // A naive prefix match on "...\Pictures" would also match "...\Pictures2\x.png".
        Path sibling = Files.createDirectories(tmp.resolve("Pictures2"));
        TestMedia.png(sibling.resolve("x.png"), 4, 4);
        indexer.index(List.of(library, sibling), progress -> { });

        Path realLibrary = library.toRealPath();
        var stamps = database.read(c -> MediaStore.stampsUnder(c, realLibrary));

        assertEquals(4, stamps.size());
        assertTrue(stamps.keySet().stream().allMatch(p -> p.startsWith(realLibrary + realLibrary.getFileSystem().getSeparator())));
    }

    private int count(String sql) throws Exception {
        return database.read(c -> {
            try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
                return rows.getInt(1);
            }
        });
    }

    private long longValue(String sql, String path) throws Exception {
        return database.read(c -> {
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setString(1, path);
                try (ResultSet rows = s.executeQuery()) {
                    return rows.getLong(1);
                }
            }
        });
    }
}
