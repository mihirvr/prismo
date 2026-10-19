package io.github.mihirvr.prismo.db;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The folders the user chose to index ("library folders"), stored in the {@code library_roots} table.
 *
 * <p>Rules (#9): one library folder can't be inside another. Adding a folder inside a library folder is refused
 * (its files are already in the library); adding a folder that holds library folders replaces them (their rows
 * stay, they are under the new one). A disabled library folder keeps its rows but every view leaves them out
 * ({@link #NOT_IN_DISABLED_FOLDER}); removing one forgets its rows.
 */
public final class LibraryRootStore {

    private static final char SEPARATOR = File.separatorChar;
    /** The character after the separator: {@code ]} after {@code \}, {@code 0} after {@code /}. */
    private static final char AFTER_SEPARATOR = (char) (SEPARATOR + 1);

    /**
     * SQL condition on {@code media}: the row is not under a disabled library folder. Every view adds it.
     *
     * <p>"Under" is the same prefix range as {@code MediaScope} (path + separator up to path + the next
     * character; a drive root already ends with the separator). The first {@code NOT EXISTS} doesn't depend on
     * the row, so SQLite works it out once: with no disabled folder (the usual case) the per-row check never runs.
     * The separator is a fixed character of this OS, not user input.
     */
    public static final String NOT_IN_DISABLED_FOLDER = ("(NOT EXISTS (SELECT 1 FROM library_roots WHERE enabled = 0)"
            + " OR NOT EXISTS (SELECT 1 FROM library_roots r WHERE r.enabled = 0 AND (media.folder = r.path"
            + " OR (media.folder >= r.path || CASE WHEN substr(r.path, -1) = '{sep}' THEN '' ELSE '{sep}' END"
            + " AND media.folder < CASE WHEN substr(r.path, -1) = '{sep}' THEN substr(r.path, 1, length(r.path) - 1)"
            + " ELSE r.path END || '{next}'))))")
            .replace("{sep}", String.valueOf(SEPARATOR)).replace("{next}", String.valueOf(AFTER_SEPARATOR));

    /** One library folder. */
    public record Root(long id, Path path, boolean enabled) {
    }

    /**
     * What {@link #add} did.
     *
     * @param path     the real path asked for
     * @param insideOf the library folder it is inside (nothing was added), or null
     * @param absorbed library folders that were inside it and are now covered by it (their rows are kept)
     */
    public record Added(Path path, Path insideOf, List<Path> absorbed) {
        public boolean added() {
            return insideOf == null;
        }
    }

    private final Database database;

    public LibraryRootStore(Database database) {
        this.database = database;
    }

    /**
     * Adds a folder. The path is resolved to its real on-disk form first, so the same folder can't be added
     * twice under a different spelling. Adding one that's already there (enabled or not) enables it.
     *
     * @throws IOException if the folder doesn't exist or can't be read
     */
    public CompletableFuture<Added> add(Path folder) throws IOException {
        Path real = folder.toRealPath();
        long now = System.currentTimeMillis();
        return database.write(connection -> {
            List<Root> roots = all(connection);
            List<Path> absorbed = new ArrayList<>();
            for (Root root : roots) {
                if (!root.path().equals(real) && real.startsWith(root.path())) {
                    return new Added(real, root.path(), List.of());
                }
                if (!root.path().equals(real) && root.path().startsWith(real)) {
                    absorbed.add(root.path());
                }
            }
            for (Path inner : absorbed) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM library_roots WHERE path = ?")) {
                    delete.setString(1, inner.toString());
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO library_roots (path, added_at) VALUES (?, ?) ON CONFLICT (path) DO UPDATE SET enabled = 1")) {
                insert.setString(1, real.toString());
                insert.setLong(2, now);
                insert.executeUpdate();
            }
            return new Added(real, null, List.copyOf(absorbed));
        });
    }

    /** Enabled roots, in the order they were added. */
    public List<Path> enabledRoots() throws SQLException {
        return all().stream().filter(Root::enabled).map(Root::path).toList();
    }

    /** Every library folder, enabled or not, in the order they were added. */
    public List<Root> all() throws SQLException {
        return database.read(LibraryRootStore::all);
    }

    private static List<Root> all(Connection connection) throws SQLException {
        List<Root> roots = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, path, enabled FROM library_roots ORDER BY id");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                roots.add(new Root(rows.getLong(1), Path.of(rows.getString(2)), rows.getBoolean(3)));
            }
        }
        return roots;
    }

    /** Turns a library folder on or off. Its rows stay either way. */
    public CompletableFuture<Integer> setEnabled(Path root, boolean enabled) {
        return database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE library_roots SET enabled = ? WHERE path = ?")) {
                update.setInt(1, enabled ? 1 : 0);
                update.setString(2, root.toString());
                return update.executeUpdate();
            }
        });
    }

    /**
     * Removes a library folder and forgets every row under it (favorites, hidden, Trash included), in one
     * transaction. Rows also under another library folder are kept. The files on disk are not touched.
     *
     * @return the number of rows forgotten
     */
    public CompletableFuture<Integer> remove(Path root) {
        return database.write(connection -> {
            List<Root> others = all(connection).stream().filter(r -> !r.path().equals(root)).toList();
            int forgotten = 0;
            if (others.stream().noneMatch(other -> root.startsWith(other.path()))) {
                StringBuilder sql = new StringBuilder("DELETE FROM media WHERE ").append(UNDER);
                List<String> params = new ArrayList<>(range(root));
                for (Root other : others) {
                    if (other.path().startsWith(root)) {
                        sql.append(" AND NOT ").append(UNDER);
                        params.addAll(range(other.path()));
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        delete.setString(i + 1, params.get(i));
                    }
                    forgotten = delete.executeUpdate();
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM library_roots WHERE path = ?")) {
                delete.setString(1, root.toString());
                delete.executeUpdate();
            }
            return forgotten;
        });
    }

    /** How many rows are under each library folder (all of them: hidden and Trash too). Same order as {@link #all}. */
    public List<Integer> counts(List<Root> roots) throws SQLException {
        return database.read(connection -> {
            List<Integer> counts = new ArrayList<>();
            // No unary +: counting only needs the folder index itself (a covering index), never the rows.
            try (PreparedStatement select = connection.prepareStatement("SELECT count(*) FROM media WHERE " + UNDER)) {
                for (Root root : roots) {
                    List<String> range = range(root.path());
                    for (int i = 0; i < range.size(); i++) {
                        select.setString(i + 1, range.get(i));
                    }
                    try (ResultSet rows = select.executeQuery()) {
                        counts.add(rows.next() ? rows.getInt(1) : 0);
                    }
                }
            }
            return counts;
        });
    }

    /** {@code folder} is the root or below it. Three parameters: {@link #range}. */
    private static final String UNDER = "(folder = ? OR (folder >= ? AND folder < ?))";

    /** The root, the start every path below it shares, and the end of that range. */
    static List<String> range(Path root) {
        String path = root.toString();
        String below = path.endsWith(String.valueOf(SEPARATOR)) ? path : path + SEPARATOR;
        return List.of(path, below, below.substring(0, below.length() - 1) + AFTER_SEPARATOR);
    }
}
