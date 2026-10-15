package io.github.mihirvr.prismo.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path tmp;

    private Database database;

    @BeforeEach
    void open() throws Exception {
        // A sub-folder that doesn't exist yet: open() must create it.
        database = Database.open(tmp.resolve("data").resolve("prismo.db"));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void createsFileAndFolder() {
        assertTrue(Files.exists(tmp.resolve("data").resolve("prismo.db")));
    }

    @Test
    void connectionSettingsAreApplied() throws Exception {
        String writer = database.write(connection -> Database.pragma(connection, "journal_mode")
                + "," + Database.pragma(connection, "synchronous")
                + "," + Database.pragma(connection, "foreign_keys")
                + "," + Database.pragma(connection, "busy_timeout")).get();
        // synchronous: 1 = NORMAL
        assertEquals("wal,1,1," + Database.BUSY_TIMEOUT_MS, writer);

        String reader = database.read(connection -> Database.pragma(connection, "query_only")
                + "," + Database.pragma(connection, "journal_mode"));
        assertEquals("1,wal", reader);
    }

    @Test
    void readConnectionCannotWrite() {
        SQLException error = assertThrows(SQLException.class, () -> database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate("INSERT INTO settings (key, value) VALUES ('a', 'b')");
            }
        }));
        assertTrue(error.getMessage().contains("readonly"), error.getMessage());
    }

    @Test
    void failedWriteIsRolledBack() throws Exception {
        CompletableFuture<Void> write = database.write(connection -> {
            insertMedia(connection, "/photos/a.jpg");
            throw new SQLException("simulated failure after the insert");
        });

        ExecutionException error = assertThrows(ExecutionException.class, write::get);
        assertInstanceOf(SQLException.class, error.getCause());
        assertEquals(0, countMedia(), "the insert before the failure must be rolled back");
    }

    @Test
    void manyConcurrentWritersNeverHitLockErrors() throws Exception {
        int writers = 200;
        List<CompletableFuture<Integer>> writes = new ArrayList<>();
        // 200 virtual threads submit at once; the single writer thread serialises them.
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<CompletableFuture<Integer>>> submitted = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String path = "/photos/" + i + ".jpg";
                submitted.add(CompletableFuture.supplyAsync(
                        () -> database.write(connection -> insertMedia(connection, path)), threads));
            }
            for (var future : submitted) {
                writes.add(future.get());
            }
        }
        for (var write : writes) {
            write.get();
        }
        assertEquals(writers, countMedia());
    }

    @Test
    void schemaRejectsBadValues() {
        // CHECK constraint: unknown media type.
        assertThrows(ExecutionException.class, () -> database.write(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(mediaInsert("'/x.jpg'", "'sticker'", "NULL"));
            }
        }).get());
        // CHECK constraint: duration is video-only.
        assertThrows(ExecutionException.class, () -> database.write(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(mediaInsert("'/y.jpg'", "'photo'", "12.5"));
            }
        }).get());
        // STRICT table: text in an INTEGER column.
        assertThrows(ExecutionException.class, () -> database.write(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate("""
                        INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added)
                        VALUES ('/z.jpg', '/', 'z.jpg', 'jpg', 'photo', 'big', 0, 0)""");
            }
        }).get());
    }

    @Test
    void sortDateFallsBackToModifiedTime() throws Exception {
        database.write(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, date_taken)
                        VALUES ('/taken.jpg', '/', 'taken.jpg', 'jpg', 'photo', 1, 100, 0, 50),
                               ('/notaken.jpg', '/', 'notaken.jpg', 'jpg', 'photo', 1, 200, 0, NULL)""");
            }
            return null;
        }).get();

        List<Long> sortDates = database.read(connection -> {
            List<Long> dates = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT sort_date FROM media ORDER BY path")) {
                while (rows.next()) {
                    dates.add(rows.getLong(1));
                }
            }
            return dates;
        });
        assertEquals(List.of(200L, 50L), sortDates); // '/notaken.jpg' sorts before '/taken.jpg'
    }

    @Test
    void closedDatabaseRejectsWrites() {
        database.close();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> database.write(connection -> null));
    }

    private int countMedia() throws SQLException {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT count(*) FROM media")) {
                return rows.getInt(1);
            }
        });
    }

    private static int insertMedia(Connection connection, String path) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes,
                                   date_modified, date_added)
                VALUES (?, '/photos', ?, 'jpg', 'photo', 1, 0, 0)""")) {
            insert.setString(1, path);
            insert.setString(2, path.substring(path.lastIndexOf('/') + 1));
            return insert.executeUpdate();
        }
    }

    private static String mediaInsert(String path, String type, String duration) {
        return """
                INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes,
                                   date_modified, date_added, duration_seconds)
                VALUES (%s, '/', 'f', 'jpg', %s, 1, 0, 0, %s)""".formatted(path, type, duration);
    }
}
