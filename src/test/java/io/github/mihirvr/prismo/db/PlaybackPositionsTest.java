package io.github.mihirvr.prismo.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaybackPositionsTest {

    @TempDir
    Path tmp;

    /** Video 1 with a known length, video 2 without one (MKV), photo 3. */
    private Database database() throws Exception {
        Database database = Database.open(tmp.resolve("positions.db"));
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes, date_modified,
                                       date_added, duration_seconds)
                    VALUES (?, ?, 'f', ?, ?, ?, 1, 0, 0, ?)""")) {
                Object[][] rows = {{1, "a.mp4", "mp4", "video", 600.0}, {2, "b.mkv", "mkv", "video", null},
                        {3, "c.jpg", "jpg", "photo", null}};
                for (Object[] row : rows) {
                    insert.setObject(1, row[0]);
                    insert.setString(2, tmp.resolve((String) row[1]).toString());
                    insert.setString(3, (String) row[1]);
                    insert.setString(4, (String) row[2]);
                    insert.setString(5, (String) row[3]);
                    insert.setObject(6, row[4]);
                    insert.executeUpdate();
                }
            }
            return null;
        }).get();
        return database;
    }

    @Test
    void aSavedPositionReadsBackAndNothingSavedIsZero() throws Exception {
        try (Database database = database()) {
            PlaybackPositions positions = new PlaybackPositions(database);

            assertEquals(0, positions.load(1), "never played");
            assertEquals(0, positions.load(99), "no such row");
            positions.save(1, 42.5, 600).get();
            assertEquals(42.5, positions.load(1));
            positions.save(1, -3, 600).get();
            assertEquals(0, positions.load(1), "never below the start");
        }
    }

    @Test
    void theFirstSaveFillsInALengthTheIndexCouldNotReadButNeverChangesAKnownOne() throws Exception {
        try (Database database = database()) {
            PlaybackPositions positions = new PlaybackPositions(database);

            positions.save(2, 10, 1234.5).get();
            positions.save(1, 10, 9999).get();

            assertEquals(1234.5, duration(database, 2), "MKV: the player's length, for the badge and the bar");
            assertEquals(600.0, duration(database, 1), "the index's own length stays");
            positions.save(2, 20, 0).get();
            assertEquals(1234.5, duration(database, 2), "an unknown length doesn't wipe it");
        }
    }

    @Test
    void aPhotoNeverGetsAPosition() throws Exception {
        try (Database database = database()) {
            PlaybackPositions positions = new PlaybackPositions(database);

            positions.save(3, 12, 30).get(); // the schema's CHECK would refuse it: the WHERE skips it instead

            assertEquals(0, positions.load(3));
        }
    }

    private static Object duration(Database database, long id) throws Exception {
        return database.read(c -> {
            try (PreparedStatement select = c.prepareStatement("SELECT duration_seconds FROM media WHERE id = ?")) {
                select.setLong(1, id);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? rows.getObject(1) : null;
                }
            }
        });
    }
}
