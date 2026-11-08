package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.PeopleFilter;
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
 * People tags (1.2.0): the database side. Entirely manual: the user makes a person and tags items with them; Prismo
 * never looks at the pictures and never writes into the files. Tables {@code person} and {@code media_person}
 * ({@code V7__people.sql}). Plain SQL on a given connection, so tests run it on a scratch database; writes go through
 * {@code Database.write} (one transaction each).
 */
public final class People {

    /** Ids go into {@code IN (...)} lists in chunks of this many (SQLite allows 32,766 parameters; this stays small). */
    static final int CHUNK = 500;

    private People() {
    }

    /** A person: their id and name. */
    public record Person(long id, String name) {
    }

    /** Every person, by name (A to Z, case ignored). */
    public static List<Person> all(Connection connection) throws SQLException {
        List<Person> people = new ArrayList<>();
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery("SELECT id, name FROM person ORDER BY name COLLATE NOCASE, id")) {
            while (rows.next()) {
                people.add(new Person(rows.getLong(1), rows.getString(2)));
            }
        }
        return people;
    }

    /** The name, trimmed, with runs of spaces made one; empty if nothing is left. */
    public static Optional<String> cleanName(String name) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        return clean.isEmpty() ? Optional.empty() : Optional.of(clean);
    }

    /**
     * Makes a person, or returns the one with that name already (names are unique, case ignored: "asha" is Asha).
     *
     * @throws IllegalArgumentException for an empty name
     */
    public static Person create(Connection connection, String name) throws SQLException {
        String clean = cleanName(name).orElseThrow(() -> new IllegalArgumentException("A name is needed"));
        Optional<Person> existing = named(connection, clean);
        if (existing.isPresent()) {
            return existing.get();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO person (name, created_at) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, clean);
            insert.setLong(2, System.currentTimeMillis());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return new Person(keys.getLong(1), clean);
            }
        }
    }

    /** The person with this name (case ignored), if any. */
    public static Optional<Person> named(Connection connection, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT id, name FROM person WHERE name = ?")) {
            select.setString(1, name);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(new Person(rows.getLong(1), rows.getString(2))) : Optional.empty();
            }
        }
    }

    /**
     * Renames a person.
     *
     * @return false if another person already has that name (nothing changed)
     * @throws IllegalArgumentException for an empty name
     */
    public static boolean rename(Connection connection, long id, String name) throws SQLException {
        String clean = cleanName(name).orElseThrow(() -> new IllegalArgumentException("A name is needed"));
        Optional<Person> other = named(connection, clean);
        if (other.isPresent() && other.get().id() != id) {
            return false;
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE person SET name = ? WHERE id = ?")) {
            update.setString(1, clean);
            update.setLong(2, id);
            update.executeUpdate();
        }
        return true;
    }

    /** Deletes a person and their tags (the files stay; ON DELETE CASCADE). */
    public static void delete(Connection connection, long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM person WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    /**
     * For each person, how many of {@code mediaIds} they are tagged in (people in none are left out). The People
     * window turns this into its tick boxes: all = ticked, some = a dash, none = empty.
     */
    public static Map<Long, Integer> counts(Connection connection, Collection<Long> mediaIds) throws SQLException {
        Map<Long, Integer> counts = new HashMap<>();
        for (List<Long> chunk : chunks(mediaIds)) {
            String sql = "SELECT person_id, count(*) FROM media_person WHERE media_id IN (" + marks(chunk.size())
                    + ") GROUP BY person_id";
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
     * Tags every item of {@code mediaIds} with {@code add} and untags {@code remove} (other tags stay as they are).
     *
     * @return how many tags were added plus how many removed
     */
    public static int apply(Connection connection, Collection<Long> mediaIds, Set<Long> add, Set<Long> remove)
            throws SQLException {
        int changed = 0;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO media_person (media_id, person_id) VALUES (?, ?)");
                PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM media_person WHERE media_id = ? AND person_id = ?")) {
            for (long media : new LinkedHashSet<>(mediaIds)) {
                for (long person : add) {
                    insert.setLong(1, media);
                    insert.setLong(2, person);
                    changed += insert.executeUpdate();
                }
                for (long person : remove) {
                    delete.setLong(1, media);
                    delete.setLong(2, person);
                    changed += delete.executeUpdate();
                }
            }
        }
        return changed;
    }

    /**
     * The ids of every item directly in one of {@code folders} (each folder exactly, not below it: the People window
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
     * The People page's cards: every person, A to Z, with how many of their items the shared filter shows and the
     * newest as the cover (as {@link Albums} does for folders; the same "bare columns" rule of SQLite). A person
     * with nothing to show still has a card (count 0, no cover), so a new person can be found and used. When the
     * People filter names people, only their cards; "No people" leaves no card.
     */
    public static List<Album> cards(Connection connection, ViewOptions options) throws SQLException {
        PeopleFilter wanted = options.people();
        if (wanted.untagged()) {
            return List.of();
        }
        ViewOptions others = options.withPeople(PeopleFilter.NONE); // counts follow the other filters only
        String where = MediaScope.LIBRARY.sqlCondition(others.osHiddenShown())
                + (others.sqlCondition() == null ? "" : " AND " + others.sqlCondition());
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, MediaRef> covers = new HashMap<>();
        String sql = "SELECT mp.person_id, count(*), id, path, extension, date_modified, max(sort_date)"
                + " FROM media_person mp JOIN media ON media.id = mp.media_id WHERE " + where
                + " GROUP BY mp.person_id";
        try (Statement select = connection.createStatement(); ResultSet rows = select.executeQuery(sql)) {
            while (rows.next()) {
                long person = rows.getLong(1);
                counts.put(person, rows.getInt(2));
                covers.put(person, new MediaRef(rows.getLong(3), Path.of(rows.getString(4)), rows.getString(5),
                        rows.getLong(6)));
            }
        }
        List<Album> cards = new ArrayList<>();
        for (Person person : all(connection)) {
            if (wanted.people().isEmpty() || wanted.people().contains(person.id())) {
                cards.add(Album.person(person.id(), person.name(), counts.getOrDefault(person.id(), 0),
                        covers.get(person.id())));
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
