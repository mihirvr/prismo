package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.ui.MediaScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuplicateFinderTest {

    @TempDir
    Path tmp;

    /** Adds a real file and its row; returns nothing (ids are given). */
    private static void add(Database database, long id, Path file, byte[] content, Long trashedAt) throws Exception {
        Files.write(file, content);
        long modified = Files.getLastModifiedTime(file).toMillis();
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                       date_modified, date_added, trashed_at)
                    VALUES (?, ?, ?, ?, 'jpg', 'photo', ?, ?, 0, ?)""")) {
                insert.setLong(1, id);
                insert.setString(2, file.toString());
                insert.setString(3, file.getParent().toString());
                insert.setString(4, file.getFileName().toString());
                insert.setLong(5, content.length);
                insert.setLong(6, modified);
                insert.setObject(7, trashedAt);
                insert.executeUpdate();
            }
            return null;
        }).get();
    }

    private static List<Long> shown(Database database) throws Exception {
        MediaScope scope = MediaScope.DUPLICATES;
        return database.read(c -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement query = c.prepareStatement(
                    "SELECT id FROM media WHERE " + scope.sqlCondition(false) + " ORDER BY id")) {
                try (ResultSet r = query.executeQuery()) {
                    while (r.next()) {
                        ids.add(r.getLong(1));
                    }
                }
            }
            return ids;
        });
    }

    private static int hashed(Database database) throws Exception {
        return database.read(c -> {
            try (PreparedStatement q = c.prepareStatement("SELECT COUNT(*) FROM media_hash");
                    ResultSet r = q.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        });
    }

    @Test
    void sameContentIsADuplicateSameSizeAloneIsNot() throws Exception {
        try (Database database = Database.open(tmp.resolve("d.db"))) {
            add(database, 1, tmp.resolve("a.jpg"), "same bytes".getBytes(), null);
            add(database, 2, tmp.resolve("copy of a.jpg"), "same bytes".getBytes(), null);
            add(database, 3, tmp.resolve("b.jpg"), "SAME BYTES".getBytes(), null); // same size, other content
            add(database, 4, tmp.resolve("c.jpg"), "a size of its own".getBytes(), null);

            int read = new DuplicateFinder(database).refresh(_ -> { }, () -> false);

            assertEquals(3, read, "only the three files that share a size are read");
            assertEquals(List.of(1L, 2L), shown(database));
            assertEquals(3, hashed(database));
            assertEquals(0, new DuplicateFinder(database).refresh(_ -> { }, () -> false), "nothing read twice");
            assertTrue(Files.exists(tmp.resolve("copy of a.jpg")), "nothing is removed: the finder only reads");
        }
    }

    @Test
    void aCopyInTrashDoesNotCountAndAChangedFileIsReadAgain() throws Exception {
        try (Database database = Database.open(tmp.resolve("d.db"))) {
            add(database, 1, tmp.resolve("a.jpg"), "same bytes".getBytes(), null);
            add(database, 2, tmp.resolve("b.jpg"), "same bytes".getBytes(), 5L); // in Trash
            add(database, 3, tmp.resolve("c.jpg"), "same bytes".getBytes(), null);
            new DuplicateFinder(database).refresh(_ -> { }, () -> false);
            assertEquals(List.of(1L, 3L), shown(database), "the Trash copy isn't shown; the other two still match");

            // c.jpg changes (same size): its old hash goes and it is read again, so it no longer matches.
            Files.write(tmp.resolve("c.jpg"), "SAME bytes".getBytes());
            long modified = Files.getLastModifiedTime(tmp.resolve("c.jpg")).toMillis() + 1;
            database.write(c -> {
                try (PreparedStatement u = c.prepareStatement("UPDATE media SET date_modified = ? WHERE id = 3")) {
                    u.setLong(1, modified);
                    u.executeUpdate();
                }
                return null;
            }).get();
            int read = new DuplicateFinder(database).refresh(_ -> { }, () -> false);
            assertEquals(1, read);
            assertEquals(List.of(), shown(database), "a.jpg's only copy is in Trash now");
        }
    }

    @Test
    void sha256OfAFile() throws Exception {
        Path file = tmp.resolve("x.bin");
        Files.write(file, "abc".getBytes());
        StringBuilder hex = new StringBuilder();
        for (byte b : DuplicateFinder.sha256(file)) {
            hex.append(String.format("%02x", b));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex.toString());
    }
}
