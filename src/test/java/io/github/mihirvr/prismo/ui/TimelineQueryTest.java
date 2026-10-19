package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimelineQueryTest {

    @TempDir
    Path tmp;

    @Test
    void everythingNewestFirstExceptTrashAndHidden() throws Exception {
        try (Database database = Database.open(tmp.resolve("prismo.db"))) {
            database.write(c -> {
                try (Statement s = c.createStatement()) {
                    // date_taken NULL for id 6: it sorts by its modified time (500) instead.
                    s.executeUpdate("""
                            INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                               date_modified, date_added, date_taken, hidden, trashed_at)
                            VALUES (1, '/a.jpg',   '/', 'a.jpg',   'jpg', 'photo', 1, 0,   0, 100,  0, NULL),
                                   (2, '/b.mp4',   '/', 'b.mp4',   'mp4', 'video', 1, 0,   0, 300,  0, NULL),
                                   (3, '/c.nef',   '/', 'c.nef',   'nef', 'raw',   1, 0,   0, 200,  0, NULL),
                                   (4, '/trash.jpg','/', 'trash.jpg','jpg','photo', 1, 0,   0, 400,  0, 999),
                                   (5, '/hid.jpg', '/', 'hid.jpg', 'jpg', 'photo', 1, 0,   0, 400,  1, NULL),
                                   (6, '/d.png',   '/', 'd.png',   'png', 'photo', 1, 500, 0, NULL, 0, NULL),
                                   (7, '/e.gif',   '/', 'e.gif',   'gif', 'gif',   1, 0,   0, 300,  0, NULL)""");
                    s.executeUpdate("UPDATE media SET width = 4000, height = 3000 WHERE id = 1");
                }
                return null;
            }).get();

            List<TimelineItem> items = database.read(TimelineQuery::load);

            // 7 and 2 share sort_date 300: the higher id comes first, every time.
            assertEquals(List.of(6L, 7L, 2L, 3L, 1L), items.stream().map(item -> item.ref().id()).toList());
            assertEquals(500, items.getFirst().sortDate());
            assertEquals("mp4", items.get(2).ref().extension());
            // The viewer's size hint: known for id 1, 0 × 0 when the indexer couldn't read it (NULL).
            assertEquals(4000, items.get(4).width());
            assertEquals(3000, items.get(4).height());
            assertEquals(0, items.getFirst().width());
            assertEquals(0, items.getFirst().height());
        }
    }

    @Test
    void readsTheTableAndSortsInsteadOfWalkingTheDateIndex() throws Exception {
        try (Database database = Database.open(tmp.resolve("prismo.db"))) {
            String plan = plan(database, TimelineQuery.SQL);

            assertTrue(plan.contains("SCAN media"), plan);
            assertTrue(plan.contains("TEMP B-TREE"), "a separate sort step:\n" + plan);
            assertFalse(plan.contains("idx_media_sort_date"), plan);
        }
    }

    // --- Filters and Sort ---

    /**
     * Eight visible files plus one trashed and one hidden. sort_date (taken, else modified):
     * 1:100 2:300 3:200 4:500 5:50 6:400 7:250 8:150.
     */
    private Database library() throws Exception {
        Database database = Database.open(tmp.resolve("library.db"));
        database.write(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("""
                        INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, date_taken, width, height, hidden, trashed_at)
                        VALUES (1, '/a/IMG_1.jpg', '/a', 'IMG_1.jpg', 'jpg', 'photo', 300, 1000, 5, 100, 4000, 3000, 0, NULL),
                               (2, '/a/Beach.jpeg', '/a', 'Beach.jpeg', 'jpeg', 'photo', 100, 3000, 4, 300, 3000, 4000, 0, NULL),
                               (3, '/a/clip.mp4', '/a', 'clip.mp4', 'mp4', 'video', 900, 2000, 3, 200, 1920, 1080, 0, NULL),
                               (4, '/a/Screenshot_2024.png', '/a', 'Screenshot_2024.png', 'png', 'photo', 50, 500, 2, NULL,
                                1080, 2400, 0, NULL),
                               (5, '/a/anim.gif', '/a', 'anim.gif', 'gif', 'gif', 20, 100, 1, 50, 500, 500, 0, NULL),
                               (6, '/a/x.nef', '/a', 'x.nef', 'nef', 'raw', 800, 50, 6, 400, NULL, NULL, 0, NULL),
                               (7, '/Screenshots/phone.heic', '/Screenshots', 'phone.heic', 'heic', 'photo', 200, 10, 7, 250,
                                100, 50, 0, NULL),
                               (8, '/a/pic.webp', '/a', 'pic.webp', 'webp', 'photo', 60, 20, 8, 150, NULL, NULL, 0, NULL),
                               (9, '/a/trash.jpg', '/a', 'trash.jpg', 'jpg', 'photo', 1, 1, 9, 999, 10, 5, 0, 1),
                               (10, '/a/hid.png', '/a', 'hid.png', 'png', 'photo', 1, 1, 10, 999, 10, 5, 1, NULL)""");
            }
            return null;
        }).get();
        return database;
    }

    private static List<Long> ids(Database database, ViewOptions options) throws Exception {
        return database.read(c -> TimelineQuery.load(c, options, ZoneId.of("UTC"))).stream()
                .map(item -> item.ref().id()).toList();
    }

    @Test
    void eachFilterShowsOnlyItsKindNewestFirst() throws Exception {
        Map<MediaFilter, List<Long>> expected = Map.ofEntries(
                Map.entry(MediaFilter.ALL, List.of(4L, 6L, 2L, 7L, 3L, 8L, 1L, 5L)),
                Map.entry(MediaFilter.PHOTOS, List.of(4L, 2L, 7L, 8L, 1L)),
                Map.entry(MediaFilter.VIDEOS, List.of(3L)),
                Map.entry(MediaFilter.GIFS, List.of(5L)),
                Map.entry(MediaFilter.RAW, List.of(6L)),
                Map.entry(MediaFilter.JPEG, List.of(2L, 1L)),
                Map.entry(MediaFilter.PNG, List.of(4L)),
                Map.entry(MediaFilter.HEIC, List.of(7L)),
                Map.entry(MediaFilter.WEBP, List.of(8L)),
                Map.entry(MediaFilter.LANDSCAPE, List.of(7L, 3L, 1L)), // 5 is square; 6 and 8 have no size
                Map.entry(MediaFilter.PORTRAIT, List.of(4L, 2L)),
                Map.entry(MediaFilter.SCREENSHOTS, List.of(4L, 7L))); // 4 by its name, 7 by its folder
        assertEquals(MediaFilter.values().length, expected.size(), "every filter is checked");
        try (Database database = library()) {
            for (MediaFilter filter : MediaFilter.values()) {
                assertEquals(expected.get(filter), ids(database, ViewOptions.DEFAULT.withFilter(filter)), filter.name());
            }
        }
    }

    @Test
    void eachSortInItsDirection() throws Exception {
        try (Database database = library()) {
            ViewOptions all = ViewOptions.DEFAULT;
            assertEquals(List.of(5L, 1L, 8L, 3L, 7L, 2L, 6L, 4L), ids(database, all.withDescending(false)));
            assertEquals(List.of(2L, 3L, 1L, 4L, 5L, 6L, 8L, 7L), ids(database, all.withSort(MediaSort.DATE_MODIFIED)));
            assertEquals(List.of(8L, 7L, 6L, 1L, 2L, 3L, 4L, 5L), ids(database, all.withSort(MediaSort.DATE_ADDED)));
            // A to Z ignoring case: "anim" < "Beach" < "clip" < "IMG_1" < "phone" < "pic" < "Screenshot…" < "x".
            assertEquals(List.of(5L, 2L, 3L, 1L, 7L, 8L, 4L, 6L), ids(database, all.withSort(MediaSort.NAME)));
            assertEquals(List.of(6L, 4L, 8L, 7L, 1L, 3L, 2L, 5L),
                    ids(database, all.withSort(MediaSort.NAME).withDescending(true)));
            assertEquals(List.of(3L, 6L, 1L, 7L, 2L, 8L, 4L, 5L), ids(database, all.withSort(MediaSort.SIZE)));
            // 1 and 2 are both 12 MP: the higher id first. 6 and 8 have no size: 0, so last.
            assertEquals(List.of(2L, 1L, 4L, 3L, 5L, 7L, 8L, 6L), ids(database, all.withSort(MediaSort.RESOLUTION)));
            // A filter and a sort together.
            assertEquals(List.of(2L, 1L, 4L, 8L, 7L),
                    ids(database, all.withFilter(MediaFilter.PHOTOS).withSort(MediaSort.DATE_MODIFIED)));
        }
    }

    @Test
    void modifiedAndAddedDatesAreGroupedByTheLocalDay() throws Exception {
        try (Database database = library()) {
            ZoneId india = ZoneId.of("Asia/Kolkata"); // UTC+5:30, no daylight saving
            long offset = 19_800_000L;
            List<TimelineItem> byModified = database.read(c ->
                    TimelineQuery.load(c, ViewOptions.DEFAULT.withSort(MediaSort.DATE_MODIFIED), india));
            assertEquals(3000 + offset, byModified.getFirst().sortDate(), "item 2, modified at 3000 ms UTC");
            List<TimelineItem> byAdded = database.read(c ->
                    TimelineQuery.load(c, ViewOptions.DEFAULT.withSort(MediaSort.DATE_ADDED), india));
            assertEquals(8 + offset, byAdded.getFirst().sortDate(), "item 8, added at 8 ms UTC");
            // Date taken is already the camera's wall-clock time: never shifted.
            List<TimelineItem> byTaken = database.read(c -> TimelineQuery.load(c, ViewOptions.DEFAULT, india));
            assertEquals(500, byTaken.getFirst().sortDate());
        }
    }

    @Test
    void noFilterOrSortWalksTheDateIndex() throws Exception {
        try (Database database = library()) {
            for (MediaFilter filter : MediaFilter.values()) {
                for (MediaSort sort : MediaSort.values()) {
                    for (boolean descending : new boolean[] {true, false}) {
                        ViewOptions options = new ViewOptions(filter, sort, descending);
                        String plan = plan(database, TimelineQuery.sql(options));
                        assertFalse(plan.contains("idx_media_sort_date"), options + ":\n" + plan);
                    }
                }
            }
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
