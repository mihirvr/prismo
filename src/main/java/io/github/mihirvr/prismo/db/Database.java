package io.github.mihirvr.prismo.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prismo's SQLite database: opens the file, migrates the schema, and hands out connections.
 *
 * <ul>
 *   <li>Writes go through {@link #write}, which queues them on the single {@link DbWriter} thread.</li>
 *   <li>Reads go through {@link #read}, on a separate read-only connection. In WAL mode readers see the
 *       last committed data and are never blocked by the writer.</li>
 * </ul>
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /** How long a connection waits for a lock before failing with "database is locked". */
    static final int BUSY_TIMEOUT_MS = 5_000;

    private final String url;
    private final DbWriter writer;

    private Database(String url, DbWriter writer) {
        this.url = url;
        this.writer = writer;
    }

    /** Opens the database file (creating it and its folder if needed) and migrates it to the latest schema. */
    public static Database open(Path file) throws SQLException, IOException {
        return open(file, new Migrations());
    }

    static Database open(Path file, Migrations migrations) throws SQLException, IOException {
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        String url = "jdbc:sqlite:" + absolute;

        Connection writeConnection = connect(url, false);
        try {
            int version = migrations.migrate(writeConnection);
            log.info("Database ready: {} (schema v{}, journal_mode={})",
                    absolute, version, pragma(writeConnection, "journal_mode"));
            return new Database(url, new DbWriter(writeConnection));
        } catch (SQLException | RuntimeException e) {
            writeConnection.close();
            throw e;
        }
    }

    /** Queues write work on the writer thread; runs in its own transaction. */
    public <T> CompletableFuture<T> write(SqlWork<T> work) {
        return writer.submit(work);
    }

    /**
     * Runs read work on the calling thread with a fresh read-only connection.
     *
     * <p>Opening a SQLite connection is cheap (it's a local file). If profiling later shows it matters,
     * a small pool of read connections can replace this without changing callers.
     */
    public <T> T read(SqlWork<T> work) throws SQLException {
        try (Connection connection = connect(url, true)) {
            return work.run(connection);
        }
    }

    /** Waits for queued writes, then closes the write connection. */
    @Override
    public void close() {
        writer.close();
        log.info("Database closed");
    }

    /**
     * Opens a connection and applies the per-connection settings. Most PRAGMAs apply only to the
     * connection that runs them, so every connection must set them; journal_mode=WAL is stored in the
     * file, so only the writer sets it.
     */
    static Connection connect(String url, boolean readOnly) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA foreign_keys = ON");
            if (readOnly) {
                statement.execute("PRAGMA query_only = ON");
            } else {
                statement.execute("PRAGMA journal_mode = WAL");
                // With WAL, NORMAL is crash-safe for the database (a power cut can lose only the last
                // commits, never corrupt the file) and avoids an fsync on every commit.
                statement.execute("PRAGMA synchronous = NORMAL");
            }
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    static String pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            return rows.getString(1);
        }
    }
}
