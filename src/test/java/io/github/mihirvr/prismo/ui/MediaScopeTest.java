package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaScopeTest {

    private static final String S = File.separator;
    /** A library folder and folders under it (and one that only looks like it's under it). */
    private static final String PHOTOS = S + "lib" + S + "Photos";
    private static final String GOA = PHOTOS + S + "2024" + S + "Goa";
    private static final String LOOKALIKE = S + "lib" + S + "Photos 2";
    private static final String ROOT = S;

    @TempDir
    Path tmp;

    /**
     * sort_date = id * 100, so newest first is the highest id first.
     * 1 Photos, 2 Goa, 3 Goa favorite, 4 Photos hidden, 5 Goa trashed, 6 lookalike, 7 favorite hidden,
     * 8 favorite trashed, 9 in the file system's root folder.
     */
    private Database library() throws Exception {
        Database database = Database.open(tmp.resolve("scope.db"));
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                       date_modified, date_added, date_taken, favorite, hidden, trashed_at)
                    VALUES (?, ?, ?, ?, 'jpg', 'photo', 1, 0, ?, ?, ?, ?, ?)""")) {
                Object[][] rows = {
                        {1, PHOTOS, 0, 0, null}, {2, GOA, 0, 0, null}, {3, GOA, 1, 0, null},
                        {4, PHOTOS, 0, 1, null}, {5, GOA, 0, 0, 5000L}, {6, LOOKALIKE, 0, 0, null},
                        {7, PHOTOS, 1, 1, null}, {8, PHOTOS, 1, 0, 8000L}, {9, ROOT, 0, 0, null},
                        {10, GOA, 0, 1, 10000L}};
                for (Object[] row : rows) {
                    int id = (int) row[0];
                    String folder = (String) row[1];
                    String name = "f" + id + ".jpg";
                    insert.setInt(1, id);
                    insert.setString(2, folder.endsWith(S) ? folder + name : folder + S + name);
                    insert.setString(3, folder);
                    insert.setString(4, name);
                    insert.setInt(5, 10 - id); // date added: the lowest id was added last
                    insert.setLong(6, id * 100L);
                    insert.setObject(7, row[2]);
                    insert.setObject(8, row[3]);
                    insert.setObject(9, row[4]);
                    insert.executeUpdate();
                }
            }
            return null;
        }).get();
        return database;
    }

    private static List<Long> ids(Database database, MediaScope scope, ViewOptions options) throws Exception {
        return database.read(c -> TimelineQuery.load(c, scope, scope.applyTo(options), ZoneId.of("UTC"))).stream()
                .map(item -> item.ref().id()).toList();
    }

    private static List<Long> ids(Database database, MediaScope scope) throws Exception {
        return ids(database, scope, ViewOptions.DEFAULT);
    }

    @Test
    void eachPageShowsItsRowsAndHiddenAndTrashOnlyOnTheirOwnPage() throws Exception {
        try (Database database = library()) {
            assertEquals(List.of(9L, 6L, 3L, 2L, 1L), ids(database, MediaScope.LIBRARY));
            assertEquals(List.of(3L), ids(database, MediaScope.FAVORITES), "not the hidden or trashed favorites");
            assertEquals(List.of(7L, 4L), ids(database, MediaScope.HIDDEN), "not the hidden one in Trash (10)");
            assertEquals(List.of(10L, 8L, 5L), ids(database, MediaScope.TRASH), "hidden or favorite: still in Trash");
        }
    }

    /**
     * Files hidden in File Explorer (os_hidden): 2 (Goa), 3 (Goa favorite) and 4 (also hidden in Prismo). The
     * Settings switch "Show files hidden in File Explorer": off (the default) they are only on Hidden; on, they are
     * like any other file, and on Hidden only if Prismo hid them too.
     */
    @Test
    void filesHiddenInFileExplorerFollowTheSettingsSwitch() throws Exception {
        try (Database database = library()) {
            database.write(c -> {
                try (Statement update = c.createStatement()) {
                    return update.executeUpdate("UPDATE media SET os_hidden = 1 WHERE id IN (2, 3, 4)");
                }
            }).get();
            ViewOptions off = ViewOptions.DEFAULT;
            ViewOptions on = ViewOptions.DEFAULT.withOsHiddenShown(true);

            assertEquals(List.of(9L, 6L, 1L), ids(database, MediaScope.LIBRARY, off));
            assertEquals(List.of(), ids(database, MediaScope.FAVORITES, off));
            assertEquals(List.of(7L, 4L, 3L, 2L), ids(database, MediaScope.HIDDEN, off));
            assertEquals(List.of(1L), ids(database, MediaScope.folder(PHOTOS, true), off));
            assertEquals(List.of(1L, 6L, 9L), ids(database, MediaScope.RECENTLY_ADDED, off));

            assertEquals(List.of(9L, 6L, 3L, 2L, 1L), ids(database, MediaScope.LIBRARY, on));
            assertEquals(List.of(3L), ids(database, MediaScope.FAVORITES, on));
            assertEquals(List.of(7L, 4L), ids(database, MediaScope.HIDDEN, on), "4 is hidden in Prismo too");
            assertEquals(List.of(3L, 2L, 1L), ids(database, MediaScope.folder(PHOTOS, true), on));
            assertEquals(List.of(1L, 2L, 3L, 6L, 9L), ids(database, MediaScope.RECENTLY_ADDED, on));

            assertEquals(List.of(10L, 8L, 5L), ids(database, MediaScope.TRASH, off), "Trash: the switch changes nothing");
            assertEquals(List.of(10L, 8L, 5L), ids(database, MediaScope.TRASH, on));
            assertTrue(MediaScope.LIBRARY.sqlCondition(false).contains("os_hidden = 0"));
            assertFalse(MediaScope.LIBRARY.sqlCondition(true).contains("os_hidden"));
        }
    }

    @Test
    void everyPageLeavesOutADisabledLibraryFolder() throws Exception {
        try (Database database = library()) {
            database.write(c -> {
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO library_roots (path, enabled, added_at) VALUES (?, 0, 0)")) {
                    insert.setString(1, GOA);
                    return insert.executeUpdate();
                }
            }).get();

            assertEquals(List.of(9L, 6L, 1L), ids(database, MediaScope.LIBRARY), "Goa's 2 and 3 left out");
            assertEquals(List.of(), ids(database, MediaScope.FAVORITES));
            assertEquals(List.of(8L), ids(database, MediaScope.TRASH), "Goa's 5 and 10 left out");
            assertEquals(List.of(1L), ids(database, MediaScope.folder(PHOTOS, true)));
            assertEquals(List.of(1L, 6L, 9L), ids(database, MediaScope.RECENTLY_ADDED));
        }
    }

    @Test
    void recentlyAddedIsNewestAddedFirstWhateverTheSort() throws Exception {
        try (Database database = library()) {
            List<Long> newestAdded = List.of(1L, 2L, 3L, 6L, 9L);
            assertEquals(newestAdded, ids(database, MediaScope.RECENTLY_ADDED));
            assertEquals(newestAdded, ids(database, MediaScope.RECENTLY_ADDED,
                    ViewOptions.DEFAULT.withSort(MediaSort.NAME).withDescending(true)));
            assertEquals(List.of(3L), ids(database, MediaScope.RECENTLY_ADDED,
                    ViewOptions.DEFAULT.withFilter(MediaFilter.FAVORITES).withSort(MediaSort.SIZE)),
                    "the filter still applies");
            ViewOptions other = ViewOptions.DEFAULT.withSort(MediaSort.SIZE);
            assertEquals(other, MediaScope.LIBRARY.applyTo(other), "other pages keep the user's sort");
        }
    }

    @Test
    void aFolderWithOrWithoutTheFoldersBelowIt() throws Exception {
        try (Database database = library()) {
            assertEquals(List.of(1L), ids(database, MediaScope.folder(PHOTOS, false)));
            assertEquals(List.of(3L, 2L, 1L), ids(database, MediaScope.folder(PHOTOS, true)),
                    "Goa is inside; \"Photos 2\" only starts with the same letters");
            assertEquals(List.of(3L, 2L), ids(database, MediaScope.folder(GOA, true)));
            assertEquals(List.of(), ids(database, MediaScope.folder(PHOTOS + S + "2024", false)));
            assertEquals(List.of(9L), ids(database, MediaScope.folder(ROOT, false)));
            assertEquals(List.of(9L, 6L, 3L, 2L, 1L), ids(database, MediaScope.folder(ROOT, true)),
                    "the file system's root folder holds everything");
        }
    }

    @Test
    void belowAFolderIsItsPathAndOneSeparator() {
        assertEquals("D:\\Photos\\", MediaScope.below("D:\\Photos", '\\'));
        assertEquals("D:\\", MediaScope.below("D:\\", '\\'));
        assertEquals("/home/m/Pictures/", MediaScope.below("/home/m/Pictures", '/'));
        assertEquals("/", MediaScope.below("/", '/'));
        // The upper end of the range is the same with the separator's next character.
        List<Object> params = MediaScope.folder(PHOTOS, true).params();
        assertEquals(PHOTOS + S, params.get(1));
        assertEquals(PHOTOS + (char) (File.separatorChar + 1), params.get(2));
        assertEquals(List.of(PHOTOS), MediaScope.folder(PHOTOS, false).params());
        assertEquals(List.of(), MediaScope.TRASH.params());
    }

    @Test
    void aFolderScopeNeedsAFolder() {
        assertThrows(IllegalArgumentException.class, () -> new MediaScope(MediaScope.Kind.FOLDER, null, true));
        assertThrows(IllegalArgumentException.class, () -> new MediaScope(MediaScope.Kind.TRASH, "x", false));
    }

    @Test
    void itemsCarryTheirFavoriteHiddenAndTrashState() throws Exception {
        try (Database database = library()) {
            TimelineItem hidden = database.read(c -> TimelineQuery.load(c, MediaScope.HIDDEN, ViewOptions.DEFAULT,
                    ZoneId.of("UTC"))).getFirst();
            assertEquals(7, hidden.ref().id());
            assertTrue(hidden.favorite());
            assertTrue(hidden.hidden());
            assertFalse(hidden.trashed());
            TimelineItem trashed = database.read(c -> TimelineQuery.load(c, MediaScope.TRASH, ViewOptions.DEFAULT,
                    ZoneId.of("UTC"))).getFirst();
            assertTrue(trashed.trashed());
        }
    }

    @Test
    void noPageWalksAnIndexRowByRowButOneFolderUsesTheFolderIndex() throws Exception {
        try (Database database = library()) {
            List<MediaScope> scopes = List.of(MediaScope.LIBRARY, MediaScope.FAVORITES, MediaScope.RECENTLY_ADDED,
                    MediaScope.HIDDEN, MediaScope.TRASH, MediaScope.folder(PHOTOS, true),
                    MediaScope.folder(PHOTOS, false));
            for (MediaScope scope : scopes) {
                String plan = plan(database, TimelineQuery.sql(scope, ViewOptions.DEFAULT));
                assertFalse(plan.contains("idx_media_sort_date"), scope + ":\n" + plan);
                if (scope.kind() != MediaScope.Kind.FOLDER || scope.recursive()) {
                    assertFalse(plan.contains("idx_media_folder"), scope + ":\n" + plan);
                }
            }
            assertTrue(plan(database, TimelineQuery.sql(MediaScope.folder(PHOTOS, false), ViewOptions.DEFAULT))
                    .contains("idx_media_folder"));
        }
    }

    private static String plan(Database database, String sql) throws Exception {
        return database.read(c -> {
            StringBuilder lines = new StringBuilder();
            try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
                while (rows.next()) {
                    lines.append(rows.getString("detail")).append('\n');
                }
            }
            return lines.toString();
        });
    }
}
