package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.MediaDetails;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.TagFilter;
import io.github.mihirvr.prismo.ui.TagKind;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Manual tags on a scratch database: People (1.2.0) and Categories (1.3.0) work the same way, so the core tests run for
 * both kinds; the last ones check that the two kinds stay apart and combine. Made-up files, no real paths.
 */
class TagsTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @TempDir
    Path tmp;

    /** Items 1-3 in Trip, 4 in Trip/Day 2, 5 in Home; 6 in Trip but in Trash. */
    private Database library() throws Exception {
        Database database = Database.open(tmp.resolve("tags.db"));
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

    private static long make(Database database, TagKind kind, String name) {
        return database.write(c -> Tags.create(c, kind, name)).join().id();
    }

    private static void tag(Database database, TagKind kind, List<Long> items, long tag) {
        database.write(c -> Tags.apply(c, kind, items, Set.of(tag), Set.of())).join();
    }

    @ParameterizedTest
    @EnumSource(TagKind.class)
    void namesAreUniqueIgnoringCaseAndTidied(TagKind kind) throws Exception {
        try (Database database = library()) {
            Tags.Tag asha = database.write(c -> Tags.create(c, kind, "  Asha   Rao ")).join();
            assertEquals("Asha Rao", asha.name());
            assertEquals(asha, database.write(c -> Tags.create(c, kind, "asha rao")).join(), "the same tag");
            assertThrows(Exception.class, () -> database.write(c -> Tags.create(c, kind, "   ")).join());
            Tags.Tag ravi = database.write(c -> Tags.create(c, kind, "Ravi")).join();
            assertFalse(database.write(c -> Tags.rename(c, kind, ravi.id(), "ASHA RAO")).join(), "name taken");
            assertTrue(database.write(c -> Tags.rename(c, kind, ravi.id(), "Ravi K")).join());
            assertEquals(List.of("Asha Rao", "Ravi K"), database.read(c -> Tags.all(c, kind)).stream()
                    .map(Tags.Tag::name).toList());
        }
    }

    @ParameterizedTest
    @EnumSource(TagKind.class)
    void taggingOnlyAddsAndUntaggingOnlyRemovesWhatWasAsked(TagKind kind) throws Exception {
        try (Database database = library()) {
            long asha = make(database, kind, "Asha");
            long ravi = make(database, kind, "Ravi");
            long mira = make(database, kind, "Mira");
            tag(database, kind, List.of(1L), mira); // an extra tag on 1
            // A folder gets Asha and Ravi: 1 keeps Mira too.
            int added = database.write(c -> Tags.apply(c, kind, List.of(1L, 2L, 3L), Set.of(asha, ravi), Set.of()))
                    .join();
            assertEquals(6, added);
            assertEquals(Map.of(asha, 3, ravi, 3, mira, 1), database.read(c -> Tags.counts(c, kind, List.of(1L, 2L, 3L))));
            // Removing Ravi from 2 leaves everything else.
            database.write(c -> Tags.apply(c, kind, List.of(2L), Set.of(), Set.of(ravi))).join();
            assertEquals(Map.of(asha, 3, ravi, 2, mira, 1), database.read(c -> Tags.counts(c, kind, List.of(1L, 2L, 3L))));
            assertEquals(0, database.write(c -> Tags.apply(c, kind, List.of(1L), Set.of(asha), Set.of())).join(),
                    "tagging again changes nothing");
        }
    }

    @Test
    void folderTaggingTakesExactlyTheFoldersChosenAndNotTrash() throws Exception {
        try (Database database = library()) {
            String trip = tmp.resolve("Trip").toString();
            String day2 = tmp.resolve("Trip").resolve("Day 2").toString();
            assertEquals(Set.of(1L, 2L, 3L), Set.copyOf(database.read(c -> Tags.mediaInFolders(c, List.of(trip)))),
                    "not the subfolder, not the item in Trash");
            assertEquals(Set.of(1L, 2L, 3L, 4L),
                    Set.copyOf(database.read(c -> Tags.mediaInFolders(c, List.of(trip, day2)))));
        }
    }

    @ParameterizedTest
    @EnumSource(TagKind.class)
    void deletingATagOrAFileTakesTheTagsWithIt(TagKind kind) throws Exception {
        try (Database database = library()) {
            long asha = make(database, kind, "Asha");
            database.write(c -> Tags.apply(c, kind, List.of(1L, 2L), Set.of(asha), Set.of())).join();
            database.write(c -> {
                try (var delete = c.prepareStatement("DELETE FROM media WHERE id = 2")) {
                    delete.executeUpdate();
                }
                return null;
            }).join();
            assertEquals(Map.of(asha, 1), database.read(c -> Tags.counts(c, kind, List.of(1L, 2L))));
            database.write(c -> {
                Tags.delete(c, kind, asha);
                return null;
            }).join();
            assertEquals(Map.of(), database.read(c -> Tags.counts(c, kind, List.of(1L))));
        }
    }

    @ParameterizedTest
    @EnumSource(TagKind.class)
    void cardsCountWhatTheFilterShowsAndTheFilterPicksWhich(TagKind kind) throws Exception {
        try (Database database = library()) {
            long asha = make(database, kind, "Asha");
            long ravi = make(database, kind, "Ravi");
            make(database, kind, "Nobody yet");
            database.write(c -> Tags.apply(c, kind, List.of(1L, 2L, 6L), Set.of(asha), Set.of())).join();
            database.write(c -> Tags.apply(c, kind, List.of(2L, 5L), Set.of(ravi), Set.of())).join();

            List<Album> cards = database.read(c -> Tags.cards(c, kind, ViewOptions.DEFAULT));
            assertEquals(List.of("Asha", "Nobody yet", "Ravi"), cards.stream().map(Album::name).toList());
            Album ashaCard = cards.getFirst();
            assertEquals(2, ashaCard.count(), "item 6 is in Trash");
            assertEquals(2, ashaCard.cover().id(), "the newest");
            assertTrue(ashaCard.isTag());
            assertEquals(kind, ashaCard.tagKind());
            assertEquals(asha, ashaCard.tagId());
            assertEquals(0, cards.get(1).count());

            ViewOptions onlyRavi = ViewOptions.DEFAULT.withTags(TagFilter.none(kind).withTag(ravi, true));
            assertEquals(List.of("Ravi"), database.read(c -> Tags.cards(c, kind, onlyRavi)).stream().map(Album::name)
                    .toList());
            ViewOptions untagged = ViewOptions.DEFAULT.withTags(TagFilter.none(kind).withUntagged(true));
            assertEquals(List.of(), database.read(c -> Tags.cards(c, kind, untagged)), "no cards for No people");
        }
    }

    @ParameterizedTest
    @EnumSource(TagKind.class)
    void theFilterAndATagsPageShowTheRightItems(TagKind kind) throws Exception {
        try (Database database = library()) {
            long asha = make(database, kind, "Asha");
            long ravi = make(database, kind, "Ravi");
            database.write(c -> Tags.apply(c, kind, List.of(1L, 2L), Set.of(asha), Set.of())).join();
            database.write(c -> Tags.apply(c, kind, List.of(2L, 3L), Set.of(ravi), Set.of())).join();
            TagFilter both = TagFilter.none(kind).withTag(asha, true).withTag(ravi, true);

            assertEquals(Set.of(2L), ids(database, MediaScope.LIBRARY, ViewOptions.DEFAULT.withTags(both)),
                    "together: only the photo with both");
            assertEquals(Set.of(1L, 2L, 3L), ids(database, MediaScope.LIBRARY,
                    ViewOptions.DEFAULT.withTags(both.withTogether(false))), "any of them");
            assertEquals(Set.of(4L, 5L), ids(database, MediaScope.LIBRARY,
                    ViewOptions.DEFAULT.withTags(TagFilter.none(kind).withUntagged(true))), "nothing tagged");
            assertEquals(Set.of(1L, 2L), ids(database, MediaScope.tag(kind, asha), ViewOptions.DEFAULT));
        }
    }

    @Test
    void peopleAndCategoriesAreSeparateAndOneItemCanHaveBoth() throws Exception {
        try (Database database = library()) {
            long personNature = make(database, TagKind.PEOPLE, "Nature");
            long categoryNature = make(database, TagKind.CATEGORIES, "Nature");
            assertEquals(List.of("Nature"), database.read(c -> Tags.all(c, TagKind.PEOPLE)).stream()
                    .map(Tags.Tag::name).toList(), "the same name may be a person and a category");
            long asha = make(database, TagKind.PEOPLE, "Asha");
            long portraits = make(database, TagKind.CATEGORIES, "Portraits");
            tag(database, TagKind.PEOPLE, List.of(1L, 2L), asha);
            tag(database, TagKind.CATEGORIES, List.of(2L, 3L), portraits);
            tag(database, TagKind.CATEGORIES, List.of(5L), categoryNature);

            assertEquals(Map.of(asha, 2), database.read(c -> Tags.counts(c, TagKind.PEOPLE, List.of(1L, 2L, 3L))));
            assertEquals(Map.of(portraits, 2), database.read(c -> Tags.counts(c, TagKind.CATEGORIES,
                    List.of(1L, 2L, 3L))));
            // Both filters on: an item must match both.
            ViewOptions ashaPortraits = ViewOptions.DEFAULT
                    .withPeople(TagFilter.NO_PEOPLE.withTag(asha, true))
                    .withCategories(TagFilter.NO_CATEGORIES.withTag(portraits, true));
            assertEquals(Set.of(2L), ids(database, MediaScope.LIBRARY, ashaPortraits));
            // Asha's page with a Portraits filter: her portraits only (a tag's page drops only its own kind's filter).
            assertEquals(Set.of(2L), ids(database, MediaScope.person(asha),
                    ViewOptions.DEFAULT.withCategories(TagFilter.NO_CATEGORIES.withTag(portraits, true))));
            // "No category" leaves people alone.
            assertEquals(Set.of(1L, 4L), ids(database, MediaScope.LIBRARY,
                    ViewOptions.DEFAULT.withCategories(TagFilter.NO_CATEGORIES.withUntagged(true))));
            // The Categories cards' counts follow the People filter.
            ViewOptions onlyAsha = ViewOptions.DEFAULT.withPeople(TagFilter.NO_PEOPLE.withTag(asha, true));
            Map<String, Integer> counts = new java.util.HashMap<>();
            database.read(c -> Tags.cards(c, TagKind.CATEGORIES, onlyAsha))
                    .forEach(card -> counts.put(card.name(), card.count()));
            assertEquals(Map.of("Nature", 0, "Portraits", 1), counts);
            // Deleting the person "Nature" leaves the category "Nature" on item 5.
            database.write(c -> {
                Tags.delete(c, TagKind.PEOPLE, personNature);
                return null;
            }).join();
            assertEquals(Map.of(categoryNature, 1), database.read(c -> Tags.counts(c, TagKind.CATEGORIES, List.of(5L))));
        }
    }

    @Test
    void theInfoPanelListsPeopleAndCategories() throws Exception {
        try (Database database = library()) {
            tag(database, TagKind.PEOPLE, List.of(2L), make(database, TagKind.PEOPLE, "Ravi"));
            tag(database, TagKind.PEOPLE, List.of(2L), make(database, TagKind.PEOPLE, "asha"));
            tag(database, TagKind.CATEGORIES, List.of(2L), make(database, TagKind.CATEGORIES, "Portraits"));
            tag(database, TagKind.CATEGORIES, List.of(2L), make(database, TagKind.CATEGORIES, "Documents"));
            MediaDetails details = database.read(c -> MediaDetails.load(c, 2)).orElseThrow();
            assertEquals(List.of("asha", "Ravi"), details.people(), "A to Z, case ignored");
            assertEquals(List.of("Documents", "Portraits"), details.categories());
            assertEquals(List.of(), database.read(c -> MediaDetails.load(c, 1)).orElseThrow().categories());
        }
    }

    private static Set<Long> ids(Database database, MediaScope scope, ViewOptions options) throws Exception {
        List<TimelineItem> items = database.read(c -> TimelineQuery.load(c, scope, options, UTC));
        return Set.copyOf(items.stream().map(TimelineItem::ref).map(MediaRef::id).toList());
    }
}
