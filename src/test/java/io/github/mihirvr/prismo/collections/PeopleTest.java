package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.PeopleFilter;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineQuery;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** People tags (1.2.0) on a scratch database: made-up files, no real paths. */
class PeopleTest {

    @TempDir
    Path tmp;

    /** Items 1-3 in Trip, 4 in Trip/Day 2, 5 in Home; 6 in Trip but in Trash. */
    private Database library() throws Exception {
        Database database = Database.open(tmp.resolve("people.db"));
        Path trip = tmp.resolve("Trip");
        Path day2 = trip.resolve("Day 2");
        Path home = tmp.resolve("Home");
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                       date_modified, date_added, date_taken, trashed_at)
                    VALUES (?, ?, ?, ?, 'jpg', 'photo', 1, 0, 0, ?, ?)""")) {
                Object[][] rows = {{1, trip, 100, null}, {2, trip, 200, null}, {3, trip, 300, null},
                        {4, day2, 400, null}, {5, home, 500, null}, {6, trip, 600, 1L}};
                for (Object[] row : rows) {
                    Path folder = (Path) row[1];
                    String name = "p" + row[0] + ".jpg";
                    insert.setInt(1, (int) row[0]);
                    insert.setString(2, folder.resolve(name).toString());
                    insert.setString(3, folder.toString());
                    insert.setString(4, name);
                    insert.setInt(5, (int) row[2]);
                    insert.setObject(6, row[3]);
                    insert.executeUpdate();
                }
            }
            return null;
        }).join();
        return database;
    }

    @Test
    void namesAreUniqueIgnoringCaseAndTidied() throws Exception {
        try (Database database = library()) {
            People.Person asha = database.write(c -> People.create(c, "  Asha   Rao ")).join();
            assertEquals("Asha Rao", asha.name());
            assertEquals(asha, database.write(c -> People.create(c, "asha rao")).join(), "the same person");
            assertThrows(Exception.class, () -> database.write(c -> People.create(c, "   ")).join());
            People.Person ravi = database.write(c -> People.create(c, "Ravi")).join();
            assertFalse(database.write(c -> People.rename(c, ravi.id(), "ASHA RAO")).join(), "name taken");
            assertTrue(database.write(c -> People.rename(c, ravi.id(), "Ravi K")).join());
            assertEquals(List.of("Asha Rao", "Ravi K"), database.read(People::all).stream().map(People.Person::name)
                    .toList());
        }
    }

    @Test
    void taggingOnlyAddsAndUntaggingOnlyRemovesWhatWasAsked() throws Exception {
        try (Database database = library()) {
            long asha = database.write(c -> People.create(c, "Asha")).join().id();
            long ravi = database.write(c -> People.create(c, "Ravi")).join().id();
            long mira = database.write(c -> People.create(c, "Mira")).join().id();
            database.write(c -> People.apply(c, List.of(1L), Set.of(mira), Set.of())).join(); // an extra tag on 1
            // A folder gets Asha and Ravi: 1 keeps Mira too.
            int added = database.write(c -> People.apply(c, List.of(1L, 2L, 3L), Set.of(asha, ravi), Set.of())).join();
            assertEquals(6, added);
            Map<Long, Integer> counts = database.read(c -> People.counts(c, List.of(1L, 2L, 3L)));
            assertEquals(Map.of(asha, 3, ravi, 3, mira, 1), counts);
            // Removing Ravi from 2 leaves everything else.
            database.write(c -> People.apply(c, List.of(2L), Set.of(), Set.of(ravi))).join();
            assertEquals(Map.of(asha, 3, ravi, 2, mira, 1), database.read(c -> People.counts(c, List.of(1L, 2L, 3L))));
            assertEquals(0, database.write(c -> People.apply(c, List.of(1L), Set.of(asha), Set.of())).join(),
                    "tagging again changes nothing");
        }
    }

    @Test
    void folderTaggingTakesExactlyTheFoldersChosenAndNotTrash() throws Exception {
        try (Database database = library()) {
            String trip = tmp.resolve("Trip").toString();
            String day2 = tmp.resolve("Trip").resolve("Day 2").toString();
            assertEquals(Set.of(1L, 2L, 3L), Set.copyOf(database.read(c -> People.mediaInFolders(c, List.of(trip)))),
                    "not the subfolder, not the item in Trash");
            assertEquals(Set.of(1L, 2L, 3L, 4L),
                    Set.copyOf(database.read(c -> People.mediaInFolders(c, List.of(trip, day2)))));
        }
    }

    @Test
    void deletingAPersonOrAFileTakesTheTagsWithIt() throws Exception {
        try (Database database = library()) {
            long asha = database.write(c -> People.create(c, "Asha")).join().id();
            database.write(c -> People.apply(c, List.of(1L, 2L), Set.of(asha), Set.of())).join();
            database.write(c -> {
                try (var delete = c.prepareStatement("DELETE FROM media WHERE id = 2")) {
                    delete.executeUpdate();
                }
                return null;
            }).join();
            assertEquals(Map.of(asha, 1), database.read(c -> People.counts(c, List.of(1L, 2L))));
            database.write(c -> {
                People.delete(c, asha);
                return null;
            }).join();
            assertEquals(Map.of(), database.read(c -> People.counts(c, List.of(1L))));
        }
    }

    @Test
    void cardsCountWhatTheFilterShowsAndThePeopleFilterPicksWhom() throws Exception {
        try (Database database = library()) {
            long asha = database.write(c -> People.create(c, "Asha")).join().id();
            long ravi = database.write(c -> People.create(c, "Ravi")).join().id();
            database.write(c -> People.create(c, "Nobody yet")).join();
            database.write(c -> People.apply(c, List.of(1L, 2L, 6L), Set.of(asha), Set.of())).join();
            database.write(c -> People.apply(c, List.of(2L, 5L), Set.of(ravi), Set.of())).join();

            List<Album> cards = database.read(c -> People.cards(c, ViewOptions.DEFAULT));
            assertEquals(List.of("Asha", "Nobody yet", "Ravi"), cards.stream().map(Album::name).toList());
            Album ashaCard = cards.getFirst();
            assertEquals(2, ashaCard.count(), "item 6 is in Trash");
            assertEquals(2, ashaCard.cover().id(), "the newest");
            assertTrue(ashaCard.isPerson());
            assertEquals(0, cards.get(1).count());

            ViewOptions onlyRavi = ViewOptions.DEFAULT.withPeople(PeopleFilter.NONE.withPerson(ravi, true));
            assertEquals(List.of("Ravi"), database.read(c -> People.cards(c, onlyRavi)).stream().map(Album::name)
                    .toList());
        }
    }

    @Test
    void thePeopleFilterAndAPersonsPageShowTheRightItems() throws Exception {
        try (Database database = library()) {
            long asha = database.write(c -> People.create(c, "Asha")).join().id();
            long ravi = database.write(c -> People.create(c, "Ravi")).join().id();
            database.write(c -> People.apply(c, List.of(1L, 2L), Set.of(asha), Set.of())).join();
            database.write(c -> People.apply(c, List.of(2L, 3L), Set.of(ravi), Set.of())).join();
            ZoneId zone = ZoneId.of("UTC");
            PeopleFilter both = PeopleFilter.NONE.withPerson(asha, true).withPerson(ravi, true);

            assertEquals(Set.of(2L), ids(database, MediaScope.LIBRARY, ViewOptions.DEFAULT.withPeople(both), zone),
                    "together: only the photo with both");
            assertEquals(Set.of(1L, 2L, 3L), ids(database, MediaScope.LIBRARY,
                    ViewOptions.DEFAULT.withPeople(both.withTogether(false)), zone), "any of them");
            assertEquals(Set.of(4L, 5L), ids(database, MediaScope.LIBRARY,
                    ViewOptions.DEFAULT.withPeople(PeopleFilter.NONE.withUntagged(true)), zone), "no people");
            assertEquals(Set.of(1L, 2L), ids(database, MediaScope.person(asha), ViewOptions.DEFAULT, zone));
        }
    }

    private static Set<Long> ids(Database database, MediaScope scope, ViewOptions options, ZoneId zone)
            throws Exception {
        List<TimelineItem> items = database.read(c -> TimelineQuery.load(c, scope, options, zone));
        return Set.copyOf(items.stream().map(TimelineItem::ref).map(MediaRef::id).toList());
    }
}
