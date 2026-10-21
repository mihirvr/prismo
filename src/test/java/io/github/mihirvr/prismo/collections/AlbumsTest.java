package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.MediaFilter;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlbumsTest {

    @TempDir
    Path tmp;

    @Test
    void oneAlbumPerFolderWithItsNewestItemAsCoverNewestAlbumFirst() throws Exception {
        Path camera = tmp.resolve("Camera");
        Path clips = tmp.resolve("Clips");
        Path old = tmp.resolve("Old");
        Path raws = tmp.resolve("Raw");
        try (Database database = Database.open(tmp.resolve("albums.db"))) {
            database.write(c -> {
                try (PreparedStatement insert = c.prepareStatement("""
                        INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, date_taken, hidden, trashed_at)
                        VALUES (?, ?, ?, ?, ?, ?, 1, 0, 0, ?, ?, ?)""")) {
                    Object[][] rows = {
                            {1, camera, "a.jpg", "photo", 100, 0, null},
                            {2, camera, "b.jpg", "photo", 300, 0, null},
                            {3, camera, "c.mp4", "video", 900, 0, null}, // newest: the cover (videos have thumbnails)
                            {4, camera, "d.jpg", "photo", 950, 1, null}, // hidden: not counted, not the cover
                            {5, camera, "e.jpg", "photo", 990, 0, 1L},   // in Trash: the same
                            {6, clips, "f.mp4", "video", 500, 0, null},
                            {7, old, "g.gif", "gif", 10, 0, null},
                            {8, raws, "h.nef", "raw", 20, 0, null}};
                    for (Object[] row : rows) {
                        Path folder = (Path) row[1];
                        String name = (String) row[2];
                        insert.setInt(1, (int) row[0]);
                        insert.setString(2, folder.resolve(name).toString());
                        insert.setString(3, folder.toString());
                        insert.setString(4, name);
                        insert.setString(5, name.substring(2));
                        insert.setString(6, (String) row[3]);
                        insert.setInt(7, (int) row[4]);
                        insert.setObject(8, row[5]);
                        insert.setObject(9, row[6]);
                        insert.executeUpdate();
                    }
                }
                return null;
            }).get();

            List<Album> albums = database.read(c -> Albums.load(c, ViewOptions.DEFAULT));

            assertEquals(List.of("Camera", "Clips", "Raw", "Old"), albums.stream().map(Album::name).toList());
            assertEquals(List.of(3, 1, 1, 1), albums.stream().map(Album::count).toList());
            assertEquals(3, albums.getFirst().cover().id(), "a video can be the cover");
            assertEquals(camera.toString(), albums.getFirst().folder());
            assertEquals(6, albums.get(1).cover().id(), "only a video: it is the cover");
            assertEquals(8, albums.get(2).cover().id(), "only a RAW: it is the cover (RAW has thumbnails since P7)");
            assertEquals(7, albums.get(3).cover().id(), "a GIF can be a cover");

            List<Album> videos = database.read(c -> Albums.load(c, ViewOptions.DEFAULT.withFilter(MediaFilter.VIDEOS)));
            assertEquals(List.of("Camera", "Clips"), videos.stream().map(Album::name).toList());
            assertEquals(List.of(1, 1), videos.stream().map(Album::count).toList());
            assertEquals(3, videos.getFirst().cover().id());
            List<Album> photos = database.read(c -> Albums.load(c, ViewOptions.DEFAULT.withFilter(MediaFilter.PHOTOS)));
            assertEquals(2, photos.getFirst().cover().id(), "the filter applies to the cover too");
        }
    }

    @Test
    void albumAndFolderCountsReadTheTableInsteadOfWalkingTheFolderIndex() throws Exception {
        try (Database database = Database.open(tmp.resolve("plans.db"))) {
            for (MediaFilter filter : MediaFilter.values()) {
                ViewOptions options = ViewOptions.DEFAULT.withFilter(filter);
                for (String sql : List.of(Albums.coversSql(options), Albums.countsSql(options),
                        FolderTree.countsSql(options))) {
                    String plan = database.read(c -> {
                        StringBuilder lines = new StringBuilder();
                        try (Statement s = c.createStatement();
                                ResultSet rows = s.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
                            while (rows.next()) {
                                lines.append(rows.getString("detail")).append('\n');
                            }
                        }
                        return lines.toString();
                    });
                    assertFalse(plan.contains("idx_media_folder"), filter + " " + sql + ":\n" + plan);
                    assertTrue(plan.contains("SCAN media"), plan);
                }
            }
        }
    }
}
