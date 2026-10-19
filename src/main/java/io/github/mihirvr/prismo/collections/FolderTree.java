package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Folder Explorer's tree: only folders with media somewhere inside them, each library folder at the top.
 *
 * <p>The database only knows the folders that directly hold media ({@code media.folder}). A folder above them
 * with no media of its own (e.g. {@code 2024} in {@code Photos\2024\Goa}) is added on the way up, so the tree has
 * no gaps, and every folder's total includes everything below it ("parent folders aggregate nested media").
 */
public final class FolderTree {

    /**
     * One folder.
     *
     * @param ownCount   media directly in it
     * @param totalCount media in it and every folder below it
     */
    public record Node(String path, String name, int ownCount, int totalCount, List<Node> children) {
    }

    private static final Comparator<Node> BY_NAME =
            Comparator.comparing(Node::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Node::name);

    private FolderTree() {
    }

    /**
     * Reads the counts for the shared filter (Trash and hidden left out, like every view) and builds the tree.
     * The tops are the enabled library folders, in the order they were added.
     *
     * <p>{@code GROUP BY +folder}: the unary {@code +} stops SQLite walking {@code idx_media_folder} to avoid a
     * sort, which fetched every row one at a time (synthetic 100,000 rows: 101–104 ms against 456–477 ms).
     */
    public static List<Node> load(Connection connection, ViewOptions options) throws SQLException {
        List<Path> roots = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT path FROM library_roots WHERE enabled = 1 ORDER BY id");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                roots.add(Path.of(rows.getString(1)));
            }
        }
        Map<Path, Integer> counts = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(countsSql(options));
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                counts.put(Path.of(rows.getString(1)), rows.getInt(2));
            }
        }
        return build(roots, counts);
    }

    static String countsSql(ViewOptions options) {
        return "SELECT folder, count(*) FROM media WHERE " + MediaScope.LIBRARY.sqlCondition()
                + (options.sqlCondition() == null ? "" : " AND " + options.sqlCondition()) + " GROUP BY +folder";
    }

    /**
     * Pure: the tree for these library folders and per-folder counts. A counted folder under no library folder
     * (shouldn't happen) becomes a top of its own, after the library folders. Children are sorted by name,
     * ignoring case.
     */
    static List<Node> build(List<Path> roots, Map<Path, Integer> ownCounts) {
        Map<Path, Building> nodes = new LinkedHashMap<>();
        List<Path> tops = new ArrayList<>();
        for (var entry : ownCounts.entrySet()) {
            Path folder = entry.getKey();
            int count = entry.getValue();
            Path root = rootOf(roots, folder);
            Building child = null;
            for (Path at = folder; at != null; at = at.getParent()) {
                boolean isTop = at.equals(root) || root == null;
                Building node = nodes.get(at);
                boolean isNew = node == null;
                if (isNew) {
                    node = new Building(at, isTop);
                    nodes.put(at, node);
                    if (isTop) {
                        tops.add(at);
                    }
                }
                node.total += count;
                if (at.equals(folder)) {
                    node.own += count;
                }
                if (child != null) {
                    node.children.putIfAbsent(child.path, child);
                }
                if (isTop) {
                    break;
                }
                child = node;
            }
        }
        List<Node> result = new ArrayList<>();
        for (Path root : roots) {
            if (nodes.containsKey(root)) {
                result.add(nodes.get(root).finish());
            }
        }
        tops.stream().filter(top -> !roots.contains(top)).map(top -> nodes.get(top).finish()).sorted(BY_NAME)
                .forEach(result::add);
        return result;
    }

    /** The innermost library folder that holds {@code folder}, or null. */
    private static Path rootOf(List<Path> roots, Path folder) {
        Path best = null;
        for (Path root : roots) {
            if (folder.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        return best;
    }

    private static final class Building {
        final Path path;
        final boolean top;
        final Map<Path, Building> children = new LinkedHashMap<>();
        int own;
        int total;

        Building(Path path, boolean top) {
            this.path = path;
            this.top = top;
        }

        Node finish() {
            Path name = path.getFileName();
            List<Node> done = children.values().stream().map(Building::finish).sorted(BY_NAME).toList();
            return new Node(path.toString(), top || name == null ? path.toString() : name.toString(), own, total, done);
        }
    }
}
