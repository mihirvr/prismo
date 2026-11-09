package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.TagFilter;
import io.github.mihirvr.prismo.ui.TagKind;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manual tags, the database side: People (1.2.0, tables {@code person} and {@code media_person},
 * {@code V7__people.sql}) and Categories (1.3.0, {@code category} and {@code media_category},
 * {@code V9__categories.sql}). Entirely manual: the user makes a tag and puts it on items; Prismo never looks at the
 * pictures and never writes into the files. Every method takes the {@link TagKind}; the table names come from it (fixed
 * text, never user input). Plain SQL on a given connection, so tests run it on a scratch database; writes go through
 * {@code Database.write} (one transaction each).
 */
public final class Tags {

    /** Ids go into {@code IN (...)} lists in chunks of this many (SQLite allows 32,766 parameters; this stays small). */
    static final int CHUNK = 500;

    private Tags() {
    }

    /** A tag (a person or a category): its id and name. */
    public record Tag(long id, String name) {
    }

    /** Every tag of {@code kind}, by name (A to Z, case ignored). */
    public static List<Tag> all(Connection connection, TagKind kind) throws SQLException {
        List<Tag> tags = new ArrayList<>();
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery(
                        "SELECT id, name FROM " + kind.table + " ORDER BY name COLLATE NOCASE, id")) {
            while (rows.next()) {
                tags.add(new Tag(rows.getLong(1), rows.getString(2)));
            }
        }
        return tags;
    }

    /** The name, trimmed, with runs of spaces made one; empty if nothing is left. */
    public static Optional<String> cleanName(String name) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        return clean.isEmpty() ? Optional.empty() : Optional.of(clean);
    }

    /**
     * Makes a tag, or returns the one with that name already (names are unique per kind, case ignored: "asha" is
     * Asha, "nature" is Nature). A person and a category may share a name: they are different kinds.
     *
     * @throws IllegalArgumentException for an empty name
     */
    public static Tag create(Connection connection, TagKind kind, String name) throws SQLException {
        String clean = cleanName(name).orElseThrow(() -> new IllegalArgumentException("A name is needed"));
        Optional<Tag> existing = named(connection, kind, clean);
        if (existing.isPresent()) {
            return existing.get();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + kind.table + " (name, created_at) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, clean);
            insert.setLong(2, System.currentTimeMillis());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return new Tag(keys.getLong(1), clean);
            }
        }
    }

    /** The tag of {@code kind} with this name (case ignored), if any. */
    public static Optional<Tag> named(Connection connection, TagKind kind, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, name FROM " + kind.table + " WHERE name = ?")) {
            select.setString(1, name);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(new Tag(rows.getLong(1), rows.getString(2))) : Optional.empty();
            }
        }
    }

    /**
     * Renames a tag.
     *
     * @return false if another tag of the same kind already has that name (nothing changed)
     * @throws IllegalArgumentException for an empty name
     */
    public static boolean rename(Connection connection, TagKind kind, long id, String name) throws SQLException {
        String clean = cleanName(name).orElseThrow(() -> new IllegalArgumentException("A name is needed"));
        Optional<Tag> other = named(connection, kind, clean);
        if (other.isPresent() && other.get().id() != id) {
            return false;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + kind.table + " SET name = ? WHERE id = ?")) {
            update.setString(1, clean);
            update.setLong(2, id);
            update.executeUpdate();
        }
        return true;
    }

    /** Deletes a tag and takes it off every item (the files stay; ON DELETE CASCADE). */
    public static void delete(Connection connection, TagKind kind, long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + kind.table + " WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    /**
     * For each tag of {@code kind}, how many of {@code mediaIds} have it (tags on none are left out). The tag window
     * turns this into its tick boxes: all = ticked, some = a dash, none = empty.
     */
    public static Map<Long, Integer> counts(Connection connection, TagKind kind, Collection<Long> mediaIds)
            throws SQLException {
        Map<Long, Integer> counts = new HashMap<>();
        for (List<Long> chunk : chunks(mediaIds)) {
            String sql = "SELECT " + kind.column + ", count(*) FROM " + kind.linkTable + " WHERE media_id IN ("
                    + marks(chunk.size()) + ") GROUP BY " + kind.column;
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                bind(select, chunk, 1);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        counts.merge(rows.getLong(1), rows.getInt(2), Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    /**
     * Puts {@code add} on every item of {@code mediaIds} and takes {@code remove} off (other tags stay as they are).
     *
     * @return how many tags were added plus how many removed
     */
    public static int apply(Connection connection, TagKind kind, Collection<Long> mediaIds, Set<Long> add,
            Set<Long> remove) throws SQLException {
        int changed = 0;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO " + kind.linkTable + " (media_id, " + kind.column + ") VALUES (?, ?)");
                PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + kind.linkTable + " WHERE media_id = ? AND " + kind.column + " = ?")) {
            for (long media : new LinkedHashSet<>(mediaIds)) {
                for (long tag : add) {
                    insert.setLong(1, media);
                    insert.setLong(2, tag);
                    changed += insert.executeUpdate();
                }
                for (long tag : remove) {
                    delete.setLong(1, media);
                    delete.setLong(2, tag);
                    changed += delete.executeUpdate();
                }
            }
        }
        return changed;
    }

    /**
     * The ids of every item directly in one of {@code folders} (each folder exactly, not below it: the tag window
     * lists the subfolders it wants one by one). Hidden items count too (they are the user's files all the same);
     * items in Trash don't.
     */
    public static List<Long> mediaInFolders(Connection connection, Collection<String> folders) throws SQLException {
        List<Long> ids = new ArrayList<>();
        List<String> all = List.copyOf(new LinkedHashSet<>(folders));
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
            String sql = "SELECT id FROM media WHERE trashed_at IS NULL AND folder IN (" + marks(chunk.size()) + ")";
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) {
                    select.setString(i + 1, chunk.get(i));
                }
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getLong(1));
                    }
                }
            }
        }
        return ids;
    }

    /**
     * The People or Categories page's cards: every tag of {@code kind}, A to Z, with how many of its items the shared
     * filter shows and the newest as the cover (as {@link Albums} does for folders; the same "bare columns" rule of
     * SQLite). A tag with nothing to show still has a card (count 0, no cover), so a new one can be found and used.
     * When this kind's filter names tags, only their cards; "No people" / "No category" leaves no card. The other
     * kind's filter narrows the counts like any other filter.
     */
    public static List<Album> cards(Connection connection, TagKind kind, ViewOptions options) throws SQLException {
        TagFilter wanted = options.tags(kind);
        if (wanted.untagged()) {
            return List.of();
        }
        ViewOptions others = options.withTags(TagFilter.none(kind)); // counts follow the other filters only
        String where = MediaScope.LIBRARY.sqlCondition(others.osHiddenShown())
                + (others.sqlCondition() == null ? "" : " AND " + others.sqlCondition());
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, MediaRef> covers = new HashMap<>();
        String sql = "SELECT link." + kind.column + ", count(*), id, path, extension, date_modified, max(sort_date)"
                + " FROM " + kind.linkTable + " link JOIN media ON media.id = link.media_id WHERE " + where
                + " GROUP BY link." + kind.column;
        try (Statement select = connection.createStatement(); ResultSet rows = select.executeQuery(sql)) {
            while (rows.next()) {
                long tag = rows.getLong(1);
                counts.put(tag, rows.getInt(2));
                covers.put(tag, new MediaRef(rows.getLong(3), Path.of(rows.getString(4)), rows.getString(5),
                        rows.getLong(6)));
            }
        }
        List<Album> cards = new ArrayList<>();
        for (Tag tag : all(connection, kind)) {
            if (wanted.ids().isEmpty() || wanted.ids().contains(tag.id())) {
                cards.add(Album.tag(kind, tag.id(), tag.name(), counts.getOrDefault(tag.id(), 0),
                        covers.get(tag.id())));
            }
        }
        return cards;
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        List<Long> all = List.copyOf(new LinkedHashSet<>(ids));
        List<List<Long>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += CHUNK) {
            chunks.add(all.subList(from, Math.min(all.size(), from + CHUNK)));
        }
        return chunks;
    }

    private static String marks(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static void bind(PreparedStatement statement, List<Long> ids, int first) throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            statement.setLong(first + i, ids.get(i));
        }
    }
}
