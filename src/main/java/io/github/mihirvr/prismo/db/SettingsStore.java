package io.github.mihirvr.prismo.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Key/value app settings stored in the {@code settings} table. */
public final class SettingsStore {

    private final Database database;

    public SettingsStore(Database database) {
        this.database = database;
    }

    public Optional<String> get(String key) throws SQLException {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT value FROM settings WHERE key = ?")) {
                select.setString(1, key);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
                }
            }
        });
    }

    /** Inserts or replaces the value (an "upsert"). */
    public CompletableFuture<Void> put(String key, String value) {
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO settings (key, value) VALUES (?, ?)
                    ON CONFLICT (key) DO UPDATE SET value = excluded.value""")) {
                upsert.setString(1, key);
                upsert.setString(2, value);
                upsert.executeUpdate();
            }
            return null;
        });
    }
}
