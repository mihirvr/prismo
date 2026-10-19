package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.mihirvr.prismo.db.Database;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaRefsTest {

    @TempDir
    Path tmp;

    @Test
    void newestImagesFirstWithoutVideosOrTrash() throws Exception {
        try (Database database = Database.open(tmp.resolve("prismo.db"))) {
            database.write(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("""
                            INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                               date_modified, date_added, date_taken, trashed_at)
                            VALUES (1, '/old.jpg',   '/', 'old.jpg',   'jpg', 'photo', 1, 0, 0, 100, NULL),
                                   (2, '/new.png',   '/', 'new.png',   'png', 'photo', 1, 0, 0, 300, NULL),
                                   (3, '/mid.gif',   '/', 'mid.gif',   'gif', 'gif',   1, 0, 0, 200, NULL),
                                   (4, '/clip.mp4',  '/', 'clip.mp4',  'mp4', 'video', 1, 0, 0, 400, NULL),
                                   (5, '/gone.jpg',  '/', 'gone.jpg',  'jpg', 'photo', 1, 0, 0, 500, 999),
                                   (6, '/off/x.jpg', '/off', 'x.jpg',  'jpg', 'photo', 1, 0, 0, 600, NULL),
                                   (7, '/old2.jpg',  '/', 'old2.jpg',  'jpg', 'photo', 1, 0, 0, 100, NULL)""");
                    // A disabled library folder: its photo needs no thumbnail now.
                    s.executeUpdate("INSERT INTO library_roots (path, enabled, added_at) VALUES ('/off', 0, 0)");
                }
                return null;
            }).get();

            List<Long> ids = database.read(MediaRefs::newestImages).stream().map(MediaRef::id).toList();

            assertEquals(List.of(2L, 3L, 7L, 1L), ids, "7 and 1 share a date: the higher id first");
            String plan = database.read(c -> {
                StringBuilder lines = new StringBuilder();
                try (Statement s = c.createStatement();
                        ResultSet rows = s.executeQuery("EXPLAIN QUERY PLAN " + MediaRefs.NEWEST_IMAGES)) {
                    while (rows.next()) {
                        lines.append(rows.getString("detail")).append('\n');
                    }
                }
                return lines.toString();
            });
            assertFalse(plan.contains("idx_media_sort_date"), plan);
        }
    }
}
