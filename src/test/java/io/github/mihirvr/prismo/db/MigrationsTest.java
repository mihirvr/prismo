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
