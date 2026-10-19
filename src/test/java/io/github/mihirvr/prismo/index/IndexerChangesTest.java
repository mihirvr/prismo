package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.testing.TestMedia;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** How the index follows files that are deleted, moved or renamed: on a rescan and from watcher updates. */
class IndexerChangesTest {

    @TempDir
    Path tmp;

    private Path library;
    private Database database;
    private Indexer indexer;

    @BeforeEach
    void setUp() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("Pictures"));
        Files.createDirectories(folder.resolve("Trip").resolve("Day 1"));
        TestMedia.jpegWithExif(folder.resolve("a.jpg"), 40, 30, "Canon", "EOS R6", 1, "2023:07:14 18:30:05");
        TestMedia.png(folder.resolve("b.png"), 20, 10);
        TestMedia.mp4(folder.resolve("Trip").resolve("clip.MP4"), Instant.parse("2024-01-15T18:00:00Z"), 3.0);
        TestMedia.png(folder.resolve("Trip").resolve("Day 1").resolve("c.PNG"), 8, 8);
        library = folder.toRealPath(); // rows hold real paths
        database = Database.open(tmp.resolve("prismo.db"));
        indexer = new Indexer(database, new MetadataReader(ZoneOffset.UTC), 2);
        indexer.index(List.of(library), _ -> { });
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    // --- Full rescans ---

    @Test
    void aDeletedFileIsRemovedOnTheNextRescan() throws Exception {
        Files.delete(library.resolve("b.png"));

        IndexProgress result = indexer.index(List.of(library), _ -> { });

        assertEquals(1, result.removed());
        assertEquals(3, count("SELECT count(*) FROM media"));
        assertEquals(-1, idOf(library.resolve("b.png")));
        checkSearchIndex();
    }

    @Test
    void aMovedFileKeepsItsRowAndTheUsersState() throws Exception {
        long id = idOf(library.resolve("a.jpg"));
        write("UPDATE media SET favorite = 1, hidden = 1 WHERE id = " + id);
        Files.move(library.resolve("a.jpg"), library.resolve("Trip").resolve("a.jpg")); // keeps size and time

        IndexProgress result = indexer.index(List.of(library), _ -> { });

        assertEquals(1, result.moved());
        assertEquals(0, result.removed());
        assertEquals(0, result.indexed(), "a move is not counted as new");
        assertEquals(id, idOf(library.resolve("Trip").resolve("a.jpg")));
        assertEquals(1, count("SELECT favorite + hidden - 1 FROM media WHERE id = " + id));
        assertEquals(1, count("SELECT count(*) FROM media WHERE id = " + id + " AND folder = '"
                + library.resolve("Trip") + "' AND file_name = 'a.jpg' AND extension = 'jpg'"));
        assertEquals(4, count("SELECT count(*) FROM media"));
        checkSearchIndex();
    }

    @Test
    void aRenamedFolderKeepsEveryRow() throws Exception {
        long clip = idOf(library.resolve("Trip").resolve("clip.MP4"));
        long c = idOf(library.resolve("Trip").resolve("Day 1").resolve("c.PNG"));
        Files.move(library.resolve("Trip"), library.resolve("Goa"));

        IndexProgress result = indexer.index(List.of(library), _ -> { });

        assertEquals(2, result.moved());
        assertEquals(clip, idOf(library.resolve("Goa").resolve("clip.MP4")));
        assertEquals(c, idOf(library.resolve("Goa").resolve("Day 1").resolve("c.PNG")));
        assertEquals(4, count("SELECT count(*) FROM media"));
    }

    @Test
    void anUnreachableLibraryFolderIsLeftAlone() throws Exception {
        Files.move(library, tmp.resolve("Unplugged")); // like a drive that's not connected

        IndexProgress result = indexer.index(List.of(library), _ -> { });

        assertEquals(1, result.failed());
        assertEquals(0, result.removed());
        assertEquals(4, count("SELECT count(*) FROM media"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void filesUnderAnUnreadableFolderAreNotTreatedAsGone() throws Exception {
        Path day1 = library.resolve("Trip").resolve("Day 1");
        AclFileAttributeView acl = Files.getFileAttributeView(day1, AclFileAttributeView.class);
        List<AclEntry> original = acl.getAcl();
        UserPrincipal me = day1.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        List<AclEntry> denied = new ArrayList<>(original);
        denied.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(me)
                .setPermissions(AclEntryPermission.LIST_DIRECTORY, AclEntryPermission.READ_DATA).build());
        acl.setAcl(denied);
        try {
            assertTrue(isUnlistable(day1), "the test needs a folder that can't be listed");

            IndexProgress result = indexer.index(List.of(library), _ -> { });

            assertEquals(0, result.removed(), "c.PNG is only out of sight, not gone");
            assertTrue(idOf(day1.resolve("c.PNG")) > 0);
            assertTrue(result.failed() >= 1);
        } finally {
            acl.setAcl(original);
        }
    }

    // --- Watcher updates ---

    @Test
    void anUpdateIndexesANewFile() throws Exception {
        Path added = TestMedia.png(library.resolve("Trip").resolve("new.png"), 6, 6);

        IndexProgress result = indexer.update(changes(added), _ -> { });

        assertEquals(1, result.indexed());
        assertTrue(idOf(added) > 0);
    }

    @Test
    void anUpdateKeepsARenamedFilesRow() throws Exception {
        long id = idOf(library.resolve("b.png"));
        write("UPDATE media SET favorite = 1 WHERE id = " + id);
        Path renamed = Files.move(library.resolve("b.png"), library.resolve("Beach.png"));

        // The watcher reports a rename as "old path deleted" + "new path created".
        IndexProgress result = indexer.update(changes(library.resolve("b.png"), renamed), _ -> { });

        assertEquals(1, result.moved());
        assertEquals(id, idOf(renamed));
        assertEquals(1, count("SELECT favorite FROM media WHERE id = " + id));
        checkSearchIndex();
    }

    @Test
    void anUpdateWalksAFolderThatWasMovedIn() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("Outside"));
        TestMedia.png(outside.resolve("x.png"), 4, 4);
        TestMedia.png(outside.resolve("y.png"), 5, 5);
        Path moved = Files.move(outside, library.resolve("Incoming"));

        // Windows reports only the folder, not the files inside it (checked in #8).
        IndexProgress result = indexer.update(changes(moved), _ -> { });

        assertEquals(2, result.indexed());
        assertTrue(idOf(moved.resolve("x.png")) > 0);
    }

    @Test
    void anUpdateRemovesEverythingUnderADeletedFolder() throws Exception {
        Path day1 = library.resolve("Trip").resolve("Day 1");
        Files.delete(day1.resolve("c.PNG"));
        Files.delete(day1);

        IndexProgress result = indexer.update(changes(day1), _ -> { });

        assertEquals(1, result.removed());
        assertEquals(3, count("SELECT count(*) FROM media"));
    }

    @Test
    void anUpdateForAnUnreachableLibraryFolderDoesNothing() throws Exception {
        Files.move(library, tmp.resolve("Unplugged")); // the drive went away; its files look "gone"
        IndexProgress result = indexer.update(changes(library.resolve("b.png"), library.resolve("Trip")), _ -> { });

        assertEquals(0, result.removed() + result.indexed());
        assertEquals(4, count("SELECT count(*) FROM media"));
    }

    @Test
    void aFileReplacedAtTheSamePathIsJustChanged() throws Exception {
        long id = idOf(library.resolve("b.png"));
        Files.delete(library.resolve("b.png"));
        TestMedia.png(library.resolve("b.png"), 30, 30); // new content, same name

        IndexProgress result = indexer.update(changes(library.resolve("b.png")), _ -> { });

        assertEquals(1, result.indexed());
        assertEquals(0, result.removed());
        assertEquals(id, idOf(library.resolve("b.png")));
    }

    // --- helpers ---

    private Map<Path, Set<Path>> changes(Path... paths) {
        return Map.of(library, new LinkedHashSet<>(List.of(paths)));
    }

    private long idOf(Path path) throws Exception {
        return database.read(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT id FROM media WHERE path = ?")) {
                s.setString(1, path.toString());
                try (ResultSet rows = s.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : -1L;
                }
            }
        });
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

    /** FTS5's own check that the search index matches the media table; throws if not. */
    private void checkSearchIndex() throws Exception {
        write("INSERT INTO media_fts (media_fts, rank) VALUES ('integrity-check', 1)");
    }

    private static boolean isUnlistable(Path folder) {
        try (var entries = Files.list(folder)) {
            entries.count();
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
