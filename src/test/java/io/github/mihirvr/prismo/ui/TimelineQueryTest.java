package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
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
    void readsTheSortDateIndexBackwardsWithoutSorting() throws Exception {
        try (Database database = Database.open(tmp.resolve("prismo.db"))) {
            String plan = database.read(c -> {
                StringBuilder lines = new StringBuilder();
                try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery("EXPLAIN QUERY PLAN " + TimelineQuery.SQL)) {
                    while (rows.next()) {
                        lines.append(rows.getString("detail")).append('\n');
                    }
                }
                return lines.toString();
            });

            assertTrue(plan.contains("idx_media_sort_date"), plan);
            assertFalse(plan.contains("TEMP B-TREE"), "no separate sort step:\n" + plan);
        }
    }
}
