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

            assertEquals(6, new Migrations().migrate(connection));

            try (Statement statement = connection.createStatement()) {
                assertTrue(tableExists(connection, "video_choices"));
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
