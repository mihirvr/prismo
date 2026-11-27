package io.github.mihirvr.prismo.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

    @TempDir
    Path tmp;

    @Test
    void freshDatabaseGetsLatestSchema() throws Exception {
        try (Connection connection = Database.connect(url("fresh.db"), false)) {
            int version = new Migrations().migrate(connection);

            assertEquals(Migrations.SCRIPTS.size(), version);
            assertEquals(version, Migrations.userVersion(connection));
            assertTrue(tableExists(connection, "media"));
            assertTrue(tableExists(connection, "settings"));
            assertTrue(tableExists(connection, "library_roots"));
            assertTrue(tableExists(connection, "media_fts"));
        }
    }

    @Test
    void searchMigrationIndexesFilesAlreadyInTheLibrary() throws Exception {
        try (Connection connection = Database.connect(url("upgrade.db"), false)) {
            new Migrations(List.of("V1__initial_schema.sql", "V2__library_roots.sql")).migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, camera_model)
                        VALUES ('D:\\Goa\\IMG_20240101.jpg', 'D:\\Goa', 'IMG_20240101.jpg', 'jpg', 'photo', 1,
                                0, 0, 'iPhone 13')""");
            }

            new Migrations().migrate(connection);

            assertEquals(1, ftsMatches(connection, "\"0101\""));
            assertEquals(1, ftsMatches(connection, "\"goa\""));
            assertEquals(1, ftsMatches(connection, "\"iphone\""));
        }
    }

    @Test
    void theHiddenMarkMigrationKeepsRowsAndStartsThemUnmarked() throws Exception {
        try (Connection connection = Database.connect(url("v4.db"), false)) {
            new Migrations(List.of("V1__initial_schema.sql", "V2__library_roots.sql", "V3__search.sql"))
                    .migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, hidden)
                        VALUES ('D:\\a.jpg', 'D:\\', 'a.jpg', 'jpg', 'photo', 1, 0, 0, 1)""");
            }

            new Migrations().migrate(connection);

            try (Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery("SELECT hidden, os_hidden FROM media")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1), "Prismo's own Hide kept");
                assertEquals(0, row.getInt(2), "the next index run reads the real mark");
            }
            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE media SET os_hidden = 2"),
                        "0 or 1 only");
            }
        }
    }

    @Test
    void theRawMigrationMakesTheNextIndexRunReadRawFilesAgain() throws Exception {
        try (Connection connection = Database.connect(url("v5.db"), false)) {
            new Migrations(List.of("V1__initial_schema.sql", "V2__library_roots.sql", "V3__search.sql",
                    "V4__os_hidden.sql")).migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, favorite)
                        VALUES ('D:\\a.nef', 'D:\\', 'a.nef', 'nef', 'raw',   900, 77, 0, 1),
                               ('D:\\b.jpg', 'D:\\', 'b.jpg', 'jpg', 'photo', 800, 77, 0, 0)""");
            }

            new Migrations().migrate(connection);

            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT size_bytes, date_modified, favorite FROM media ORDER BY path")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1), "RAW: a size the file can't have, so the indexer reads it again");
                assertEquals(77, rows.getLong(2), "modified time kept: the thumbnail stays valid");
                assertEquals(1, rows.getInt(3), "favorite kept");
                assertTrue(rows.next());
                assertEquals(800, rows.getLong(1), "other types untouched");
            }
        }
    }

    @Test
    void theVideoChoicesTableKeepsOnlyCompletePicksAndGoesWithItsVideo() throws Exception {
        try (Connection connection = Database.connect(url("v6.db"), false)) {
            new Migrations(Migrations.SCRIPTS.subList(0, 5)).migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added)
                        VALUES (7, 'D:\\a.mkv', 'D:\\', 'a.mkv', 'mkv', 'video', 1, 0, 0)""");
            }

            assertEquals(11, new Migrations().migrate(connection)); // 1.2.0: V7 people, V8 MKV re-read; 1.3.0: V9; 1.4.0: V10; 2.0.0: V11

            try (Statement statement = connection.createStatement()) {
                assertTrue(tableExists(connection, "video_choices"));
                assertTrue(tableExists(connection, "person"));
                assertTrue(tableExists(connection, "media_person"));
                assertTrue(tableExists(connection, "category"));
                assertTrue(tableExists(connection, "media_category"));
                statement.executeUpdate("""
                        INSERT INTO video_choices (media_id, audio_stream, subtitle, subtitle_stream)
                        VALUES (7, 1, 'track', 3)""");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "UPDATE video_choices SET subtitle_stream = NULL"), "a track pick needs its stream");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "UPDATE video_choices SET subtitle = 'file'"), "a file pick needs its file");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "UPDATE video_choices SET subtitle = 'maybe'"), "off, track or file only");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO video_choices (media_id) VALUES (99)"), "only for a media row that exists");

                statement.executeUpdate("DELETE FROM media WHERE id = 7");

                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM video_choices")) {
                    assertEquals(0, rows.getInt(1), "gone with its video");
                }
            }
        }
    }

    /**
     * 2.0.0: V11 rebuilds the media table to allow audio. Everything pointing at a row must survive (with foreign keys
     * on, dropping the old table would have deleted it all), the search index must still find files, and the checks
     * must hold again afterwards.
     */
    @Test
    void theAudioMigrationKeepsEveryRowAndWhatPointsAtIt() throws Exception {
        try (Connection connection = Database.connect(url("v11.db"), false)) {
            new Migrations(Migrations.SCRIPTS.subList(0, 10)).migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, date_taken, width, height, duration_seconds,
                                           playback_position_seconds, favorite, hidden, trashed_at, os_hidden)
                        VALUES (7, 'D:\\Trip\\beach.mkv', 'D:\\Trip', 'beach.mkv', 'mkv', 'video', 10, 5, 6, 4,
                                1920, 1080, 61.5, 12.0, 1, 0, NULL, 1),
                               (8, 'D:\\Trip\\IMG_1.jpg', 'D:\\Trip', 'IMG_1.jpg', 'jpg', 'photo', 20, 5, 6, NULL,
                                4000, 3000, NULL, NULL, 0, 1, 99, 0)""");
                statement.executeUpdate("INSERT INTO person (id, name, created_at) VALUES (1, 'Asha', 0)");
                statement.executeUpdate("INSERT INTO media_person VALUES (8, 1)");
                statement.executeUpdate("INSERT INTO category (id, name, created_at) VALUES (1, 'Nature', 0)");
                statement.executeUpdate("INSERT INTO media_category VALUES (8, 1)");
                statement.executeUpdate("INSERT INTO media_hash VALUES (7, 10, 5, x'00ff')");
                statement.executeUpdate("INSERT INTO video_choices (media_id, audio_stream) VALUES (7, 2)");
            }

            assertEquals(11, new Migrations().migrate(connection));

            try (Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("""
                        SELECT id, path, media_type, duration_seconds, playback_position_seconds, favorite, hidden,
                               trashed_at, os_hidden, sort_date, width FROM media ORDER BY id""")) {
                    assertTrue(rows.next());
                    assertEquals(7, rows.getLong(1));
                    assertEquals(61.5, rows.getDouble(4));
                    assertEquals(12.0, rows.getDouble(5));
                    assertEquals(1, rows.getInt(6));
                    assertEquals(1, rows.getInt(9));
                    assertEquals(4, rows.getLong(10), "sort date = date taken");
                    assertTrue(rows.next());
                    assertEquals(8, rows.getLong(1));
                    assertEquals(1, rows.getInt(7));
                    assertEquals(99, rows.getLong(8));
                    assertEquals(5, rows.getLong(10), "sort date = modified, no date taken");
                    assertEquals(4000, rows.getInt(11));
                    assertFalse(rows.next());
                }
                for (String table : List.of("media_person", "media_category", "media_hash", "video_choices")) {
                    try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                        assertEquals(1, rows.getInt(1), table + " kept");
                    }
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT rowid FROM media_fts WHERE media_fts MATCH 'beach'")) {
                    assertTrue(rows.next(), "search still finds an old file");
                    assertEquals(7, rows.getLong(1));
                }

                statement.executeUpdate("""
                        INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, duration_seconds, playback_position_seconds)
                        VALUES (9, 'D:\\Music\\song.flac', 'D:\\Music', 'song.flac', 'flac', 'audio', 30, 5, 6,
                                215.2, 40.0)""");
                try (ResultSet rows = statement.executeQuery(
                        "SELECT rowid FROM media_fts WHERE media_fts MATCH 'song'")) {
                    assertTrue(rows.next(), "a new file is in the search index (its trigger is back)");
                }
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "UPDATE media SET media_type = 'sound' WHERE id = 9"), "still only known types");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "UPDATE media SET duration_seconds = 3 WHERE id = 8"), "still no length for a photo");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO media_hash VALUES (99, 1, 1, x'00')"), "foreign keys are on again");
                statement.executeUpdate("DELETE FROM media WHERE id = 8");
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM media_person")) {
                    assertEquals(0, rows.getInt(1), "deleting a file still takes its People rows with it");
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND tbl_name = 'media'"
                                + " AND name LIKE 'idx_media_%'")) {
                    assertEquals(3, rows.getInt(1), "sort date, folder and size indexes");
                }
            }
        }
    }

    @Test
    void migratingTwiceChangesNothing() throws Exception {
        try (Connection connection = Database.connect(url("twice.db"), false)) {
            Migrations migrations = new Migrations();
            int first = migrations.migrate(connection);
            int second = migrations.migrate(connection);

            assertEquals(first, second);
        }
    }

    @Test
    void refusesDatabaseFromNewerApp() throws Exception {
        try (Connection connection = Database.connect(url("newer.db"), false);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA user_version = 99");

            assertThrows(IllegalStateException.class, () -> new Migrations().migrate(connection));
        }
    }

    @Test
    void failedMigrationLeavesDatabaseUntouched() throws Exception {
        // test/V2__broken.sql creates a table, then hits a syntax error.
        Migrations migrations = new Migrations(List.of("V1__initial_schema.sql", "test/V2__broken.sql"));

        try (Connection connection = Database.connect(url("broken.db"), false)) {
            assertThrows(SQLException.class, () -> migrations.migrate(connection));

            assertEquals(1, Migrations.userVersion(connection), "version must not move past the failed script");
            assertFalse(tableExists(connection, "half_done"), "the failed script's first statement must be rolled back");
        }
    }

    private String url(String name) {
        return "jdbc:sqlite:" + tmp.resolve(name);
    }

    private static int ftsMatches(Connection connection, String match) throws SQLException {
        try (var select = connection.prepareStatement("SELECT count(*) FROM media_fts WHERE media_fts MATCH ?")) {
            select.setString(1, match);
            try (ResultSet rows = select.executeQuery()) {
                return rows.getInt(1);
            }
        }
    }

    static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var select = connection.prepareStatement(
                "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?")) {
            select.setString(1, table);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }
}
