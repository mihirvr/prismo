package io.github.mihirvr.prismo.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings the schema up to date with numbered SQL scripts.
 *
 * <p>The current schema version is stored in SQLite's {@code PRAGMA user_version} (an integer in the
 * database file header). Script N takes the schema from version N-1 to N. Each script and its version
 * bump run in one transaction, so a failed migration leaves the database exactly as it was.
 *
 * <p>Scripts are listed here explicitly instead of scanned from the classpath: listing resources inside a
 * jar is awkward, and an explicit list makes the order impossible to get wrong.
 */
final class Migrations {

    private static final Logger log = LoggerFactory.getLogger(Migrations.class);
    private static final String RESOURCE_DIR = "/db/migration/";

    /** Never edit or reorder a released script; add a new one at the end. */
    static final List<String> SCRIPTS = List.of(
            "V1__initial_schema.sql",
            "V2__library_roots.sql",
            "V3__search.sql",
            "V4__os_hidden.sql");

    private final List<String> scripts;

    Migrations() {
        this(SCRIPTS);
    }

    /** For tests: a custom script list (resources under {@code /db/migration/}). */
    Migrations(List<String> scripts) {
        this.scripts = List.copyOf(scripts);
    }

    int latestVersion() {
        return scripts.size();
    }

    /**
     * Applies every script newer than the database's current version.
     *
     * @return the schema version after migrating
     * @throws IllegalStateException if the database was written by a newer Prismo (downgrade)
     */
    int migrate(Connection connection) throws SQLException {
        int current = userVersion(connection);
        int latest = latestVersion();
        if (current > latest) {
            throw new IllegalStateException("Database schema is v" + current
                    + " but this version of Prismo only knows up to v" + latest + ". Update Prismo.");
        }
        for (int version = current + 1; version <= latest; version++) {
            apply(connection, version, scripts.get(version - 1));
        }
        return latest;
    }

    static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.getInt(1);
        }
    }

    private static void apply(Connection connection, int version, String script) throws SQLException {
        String sql = readScript(script);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            statement.executeUpdate("PRAGMA user_version = " + version);
            connection.commit();
            log.info("Applied migration {} (schema v{})", script, version);
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String readScript(String script) {
        String path = RESOURCE_DIR + script;
        try (InputStream in = Migrations.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration script on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
