package io.github.mihirvr.prismo.db;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The folders the user chose to index, stored in the {@code library_roots} table. */
public final class LibraryRootStore {

    private final Database database;

    public LibraryRootStore(Database database) {
        this.database = database;
    }

    /**
     * Adds a folder. The path is resolved to its real on-disk form first, so the same folder can't be
     * added twice under a different spelling. Adding an existing folder is a no-op.
     *
     * @return the stored (real) path
     * @throws IOException if the folder doesn't exist or can't be read
     */
    public CompletableFuture<Path> add(Path folder) throws IOException {
        Path real = folder.toRealPath();
        long now = System.currentTimeMillis();
        return database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO library_roots (path, added_at) VALUES (?, ?) ON CONFLICT (path) DO NOTHING")) {
                insert.setString(1, real.toString());
                insert.setLong(2, now);
                insert.executeUpdate();
            }
            return real;
        });
    }

    /** Enabled roots, in the order they were added. */
    public List<Path> enabledRoots() throws SQLException {
        return database.read(connection -> {
            List<Path> roots = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT path FROM library_roots WHERE enabled = 1 ORDER BY id");
                    ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    roots.add(Path.of(rows.getString(1)));
                }
            }
            return roots;
        });
    }
}
