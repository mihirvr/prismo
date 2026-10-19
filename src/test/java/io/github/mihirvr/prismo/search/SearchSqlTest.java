package io.github.mihirvr.prismo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.ui.MediaFilter;
import io.github.mihirvr.prismo.ui.MediaSort;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchSqlTest {

    @TempDir
    Path tmp;

    private Database database;

    @BeforeEach
    void open() throws Exception {
        database = Database.open(tmp.resolve("prismo.db"));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void findsAnyPartOfTheNameIgnoringCase() throws Exception {
        insert(new Row(1, "D:\\Photos\\Goa 2024", "IMG_20240101_1200.jpg").taken(2024, 1, 1));
        insert(new Row(2, "D:\\Photos\\Family", "DSC_0001.JPG").taken(2023, 5, 1));
        insert(new Row(3, "D:\\Videos", "clip.mp4").taken(2022, 5, 1));

        assertEquals(List.of(1L), search("0101"));
        assertEquals(List.of(1L), search("img"));
        assertEquals(List.of(2L), search("dsc_0001.jpg"));
        assertEquals(List.of(1L, 2L), search("JPG")); // the extension is part of the name; newest first
    }

    @Test
    void folderWordsMatchTheWholeFolderPath() throws Exception {
        insert(new Row(1, "D:\\Photos\\Goa 2024\\Day 1", "a.jpg").taken(2024, 1, 2));
        insert(new Row(2, "D:\\Photos\\Family", "b.jpg").taken(2024, 1, 1));
        insert(new Row(3, "D:\\Videos", "c.mp4").taken(2024, 1, 3));

        assertEquals(List.of(1L), search("goa"));
        assertEquals(List.of(1L, 2L), search("photos")); // a parent folder finds everything below it
        assertEquals(List.of(1L), search("goa 2024\\day"));
    }

    @Test
    void findsTheCameraMakeAndModel() throws Exception {
        insert(new Row(1, "D:\\a", "DSC_0001.jpg").camera("NIKON CORPORATION", "NIKON D750").taken(2024, 1, 2));
        insert(new Row(2, "D:\\a", "IMG_0002.jpg").camera("Apple", "iPhone 13").taken(2024, 1, 1));
        insert(new Row(3, "D:\\a", "IMG_0003.jpg").taken(2024, 1, 3));

        assertEquals(List.of(1L), search("nikon"));
        assertEquals(List.of(1L), search("d750"));
        assertEquals(List.of(2L), search("iphone 13"));
        assertEquals(List.of(2L), search("apple"));
    }

    @Test
    void everyWordMustMatch() throws Exception {
        insert(new Row(1, "D:\\Photos\\Goa 2024", "IMG_20240101.jpg").taken(2024, 1, 1));
        insert(new Row(2, "D:\\Photos\\Family", "IMG_20240102.jpg").taken(2024, 1, 2));

        assertEquals(List.of(1L), search("goa img"));
        assertEquals(List.of(), search("goa 0102"));
        assertEquals(List.of(1L), search("goa oa")); // an indexed word and a short (LIKE) word together
    }

    @Test
    void shortWordsAreMatchedLiterallyIncludingLikeWildcards() throws Exception {
        insert(new Row(1, "D:\\a", "100%_off.jpg").taken(2024, 1, 3));
        insert(new Row(2, "D:\\a", "1000_x.jpg").taken(2024, 1, 2));
        insert(new Row(3, "D:\\a", "Ab.png").taken(2024, 1, 1));

        assertEquals(List.of(1L), search("%_"));
        assertEquals(List.of(1L), search("%"));
        assertEquals(List.of(1L, 2L), search("_"));
        assertEquals(List.of(3L), search("aB"));
        assertEquals(List.of(1L, 2L, 3L), search("\\")); // a literal backslash: every folder path has one
    }

    @Test
    void typedTextIsSearchedForAsTextNeverAsFtsSyntax() throws Exception {
        insert(new Row(1, "D:\\a", "IMG_0001.jpg").taken(2024, 1, 1));
        // Windows file names can't hold a double quote, so the quoted text goes in the camera model.
        insert(new Row(2, "D:\\a", "DSC_0002.jpg").camera("Test", "say \"hi\"").taken(2024, 1, 2));

        // As FTS5 syntax, img* would be a prefix search and find IMG_0001.jpg.
        assertEquals(List.of(), search("img*"));
        assertEquals(List.of(2L), search("\"hi\""));
        assertEquals(List.of(2L), search("\""));
        for (String text : List.of("img OR dsc", "NEAR(img jpg)", "file_name:img", "-img", "^img", "(img", "AND", "\"\"\"")) {
            search(text); // must not throw an FTS5 syntax error
        }
    }

    @Test
    void ignoresAccents() throws Exception {
        insert(new Row(1, "D:\\Photos\\Café", "résumé.png").taken(2024, 1, 1));

        assertEquals(List.of(1L), search("cafe"));
        assertEquals(List.of(1L), search("RESUME"));
        assertEquals(List.of(1L), search("café"));
    }

    @Test
    void leavesOutTrashAndHiddenAndSortsNewestFirst() throws Exception {
        insert(new Row(1, "D:\\a", "trip_1.jpg").taken(2024, 1, 1));
        insert(new Row(2, "D:\\a", "trip_2.jpg").taken(2024, 1, 3));
        insert(new Row(3, "D:\\a", "trip_3.jpg").taken(2024, 1, 3)); // same date as 2: the higher id first
        insert(new Row(4, "D:\\a", "trip_4.jpg").taken(2024, 1, 4).hidden());
        insert(new Row(5, "D:\\a", "trip_5.jpg").taken(2024, 1, 5).trashed());

        assertEquals(List.of(3L, 2L, 1L), search("trip"));
        assertEquals(List.of(3L, 2L, 1L), search("ip")); // the LIKE path too
    }

    @Test
    void datesAndResolutionsMatchTheirFieldsOrTheName() throws Exception {
        insert(new Row(1, "D:\\a", "DSC_1.jpg").taken(2024, 10, 5).size(4000, 3000));
        insert(new Row(2, "D:\\a", "DSC_2.jpg").taken(2023, 10, 7));
        insert(new Row(3, "D:\\a", "IMG_2024_trip.jpg").taken(2019, 3, 1).size(3000, 4000));
        insert(new Row(4, "D:\\a", "DSC_4.jpg").taken(2024, 3, 2));
        insert(new Row(5, "D:\\a", "old.jpg").taken(LocalDateTime.of(1969, 12, 31, 23, 0)));

        assertEquals(List.of(1L, 4L, 3L), search("2024")); // 1 and 4 by date, 3 by name
        assertEquals(List.of(1L, 2L), search("oct"));
        assertEquals(List.of(1L), search("oct 2024"));
        assertEquals(List.of(1L), search("2024-10"));
        assertEquals(List.of(1L), search("2024-10-05"));
        assertEquals(List.of(), search("2024-10-06"));
        assertEquals(List.of(1L), search("4000x3000"));
        assertEquals(List.of(3L), search("3000×4000"));
        // Before 1970 the stored time is negative; the month must still be December, not January 1970.
        assertEquals(List.of(5L), search("dec"));
        assertEquals(List.of(5L), search("1969"));
    }

    @Test
    void theSharedFiltersAndSortApplyToSearchResults() throws Exception {
        insert(new Row(1, "D:\\Goa", "beach.jpg").taken(2024, 1, 3).size(4000, 3000));
        insert(new Row(2, "D:\\Goa", "Arrival.jpg").taken(2024, 1, 1).size(3000, 4000));
        insert(new Row(3, "D:\\Goa", "cliff.jpg").taken(2024, 1, 2).size(4000, 3000));
        insert(new Row(4, "D:\\Home", "sofa.jpg").taken(2024, 1, 4).size(4000, 3000));

        ViewOptions landscape = ViewOptions.DEFAULT.withFilter(MediaFilter.LANDSCAPE);
        assertEquals(List.of(1L, 3L), search("goa", landscape));
        assertEquals(List.of(2L, 1L, 3L), search("goa", ViewOptions.DEFAULT.withSort(MediaSort.NAME)));
        assertEquals(List.of(3L, 1L), search("goa", landscape.withDescending(false)));
        assertEquals(List.of(2L), search("goa", ViewOptions.DEFAULT.withFilter(MediaFilter.PORTRAIT)));
        assertEquals(List.of(), search("goa", ViewOptions.DEFAULT.withFilter(MediaFilter.VIDEOS)));
        // The short-word (LIKE) path gets them too.
        assertEquals(List.of(1L, 3L), search("oa", landscape));
    }

    @Test
    void theIndexFollowsInsertsUpsertsUpdatesAndDeletes() throws Exception {
        insert(new Row(1, "D:\\a", "clip.mp4").taken(2024, 1, 1));
        assertEquals(List.of(1L), search("clip"));

        // The indexer's upsert: a changed file comes back through ON CONFLICT ... DO UPDATE.
        write("""
                INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes, date_modified, date_added)
                VALUES ('D:\\a\\clip.mp4', 'D:\\a', 'holiday.mp4', 'mp4', 'video', 1, 0, 0)
                ON CONFLICT (path) DO UPDATE SET file_name = excluded.file_name""");
        assertEquals(List.of(), search("clip"));
        assertEquals(List.of(1L), search("holiday"));

        write("UPDATE media SET camera_make = 'Canon' WHERE id = 1");
        assertEquals(List.of(1L), search("canon"));
        write("UPDATE media SET folder = 'D:\\Moved' WHERE id = 1");
        assertEquals(List.of(1L), search("moved"));

        write("INSERT INTO media_fts (media_fts, rank) VALUES ('integrity-check', 1)"); // throws if out of sync

        write("DELETE FROM media WHERE id = 1");
        assertEquals(List.of(), search("holiday"));
        write("INSERT INTO media_fts (media_fts, rank) VALUES ('integrity-check', 1)");
    }

    @Test
    void queryPlansUseTheIndexAndNeverWalkTheDateIndexRowByRow() throws Exception {
        String fts = plan(SearchSql.build(SearchQuery.parse("goa")));
        assertTrue(fts.contains("VIRTUAL TABLE"), fts);
        assertFalse(fts.contains("idx_media_sort_date"), fts);

        String like = plan(SearchSql.build(SearchQuery.parse("ab")));
        assertFalse(like.contains("idx_media_sort_date"), "must scan the table, then sort:\n" + like);
    }

    // --- helpers ---

    private List<Long> search(String text) throws SQLException {
        return search(text, ViewOptions.DEFAULT);
    }

    private List<Long> search(String text, ViewOptions options) throws SQLException {
        return database.read(c -> SearchSql.run(c, SearchQuery.parse(text), options, ZoneId.of("UTC"))).stream()
                .map(item -> item.ref().id()).toList();
    }

    private void write(String sql) throws Exception {
        database.write(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate(sql);
            }
            return null;
        }).get();
    }

    private String plan(SearchSql.Built built) throws SQLException {
        return database.read(c -> {
            StringBuilder lines = new StringBuilder();
            try (PreparedStatement select = c.prepareStatement("EXPLAIN QUERY PLAN " + built.sql())) {
                for (int i = 0; i < built.params().size(); i++) {
                    select.setObject(i + 1, built.params().get(i));
                }
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        lines.append(rows.getString("detail")).append('\n');
                    }
                }
            }
            return lines.toString();
        });
    }

    private void insert(Row row) throws Exception {
        database.write(c -> {
            insert(c, row);
            return null;
        }).get();
    }

    private static void insert(Connection c, Row row) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement("""
                INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes, date_modified,
                                   date_added, date_taken, width, height, camera_make, camera_model, hidden, trashed_at)
                VALUES (?, ?, ?, ?, 'jpg', 'photo', 1, 0, 0, ?, ?, ?, ?, ?, ?, ?)""")) {
            insert.setLong(1, row.id);
            insert.setString(2, row.folder + "\\" + row.name);
            insert.setString(3, row.folder);
            insert.setString(4, row.name);
            insert.setLong(5, row.taken);
            setInt(insert, 6, row.width);
            setInt(insert, 7, row.height);
            insert.setString(8, row.make);
            insert.setString(9, row.model);
            insert.setInt(10, row.hidden ? 1 : 0);
            if (row.trashed) {
                insert.setLong(11, 1);
            } else {
                insert.setNull(11, Types.INTEGER);
            }
            insert.executeUpdate();
        }
    }

    private static void setInt(PreparedStatement statement, int index, int value) throws SQLException {
        if (value == 0) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    /** One test row; the date taken is floating wall-clock time stored as if it were UTC, like the indexer's. */
    private static final class Row {
        final long id;
        final String folder;
        final String name;
        long taken;
        int width;
        int height;
        String make;
        String model;
        boolean hidden;
        boolean trashed;

        Row(long id, String folder, String name) {
            this.id = id;
            this.folder = folder;
            this.name = name;
        }

        Row taken(int year, int month, int day) {
            return taken(LocalDateTime.of(year, month, day, 12, 0));
        }

        Row taken(LocalDateTime wallClock) {
            taken = wallClock.toInstant(ZoneOffset.UTC).toEpochMilli();
            return this;
        }

        Row size(int w, int h) {
            width = w;
            height = h;
            return this;
        }

        Row camera(String cameraMake, String cameraModel) {
            make = cameraMake;
            model = cameraModel;
            return this;
        }

        Row hidden() {
            hidden = true;
            return this;
        }

        Row trashed() {
            trashed = true;
            return this;
        }
    }
}
