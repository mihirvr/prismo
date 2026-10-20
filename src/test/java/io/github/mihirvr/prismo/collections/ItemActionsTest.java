package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.ItemAction;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineQuery;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemActionsTest {

    @TempDir
    Path tmp;

    private Path file;
    private Path thumbnail;
    private final List<String> messages = new ArrayList<>();
    private final List<File> binned = new ArrayList<>();
    private CountDownLatch changed;

    @BeforeEach
    void files() throws Exception {
        file = Files.writeString(tmp.resolve("a.jpg"), "x");
        thumbnail = Files.writeString(tmp.resolve("1.thumb.jpg"), "t");
    }

    private Database database() throws Exception {
        Database database = Database.open(tmp.resolve("actions.db"));
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                       date_modified, date_added)
                    VALUES (1, ?, ?, 'a.jpg', 'jpg', 'photo', 1, 0, 0)""")) {
                insert.setString(1, file.toString());
                insert.setString(2, tmp.toString());
                insert.executeUpdate();
            }
            return null;
        }).get();
        return database;
    }

    private ItemActions actions(Database database, boolean confirm, FileRemover remover) {
        return new ItemActions(new MediaFlags(database), remover, _ -> thumbnail, _ -> confirm, Runnable::run,
                messages::add, () -> changed.countDown(), () -> 1234L);
    }

    private ItemActions actions(Database database, boolean confirm) {
        return actions(database, confirm, new FileRemover(f -> binned.add(f) && f.delete()));
    }

    /** Runs one action and waits until the app would reload (the write is done). */
    private boolean run(ItemActions actions, ItemAction action, TimelineItem item) throws Exception {
        changed = new CountDownLatch(1);
        boolean done = actions.perform(action, item);
        if (done) {
            assertTrue(changed.await(5, TimeUnit.SECONDS), "the app is told the library changed");
        }
        return done;
    }

    private static TimelineItem item(Database database) throws Exception {
        for (MediaScope scope : List.of(MediaScope.LIBRARY, MediaScope.HIDDEN, MediaScope.TRASH)) {
            List<TimelineItem> items = database.read(c ->
                    TimelineQuery.load(c, scope, ViewOptions.DEFAULT, ZoneId.of("UTC")));
            if (!items.isEmpty()) {
                return items.getFirst();
            }
        }
        return null;
    }

    private static Object column(Database database, String column) throws Exception {
        return database.read(c -> {
            try (PreparedStatement select = c.prepareStatement("SELECT " + column + " FROM media WHERE id = 1");
                    ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getObject(1) : "no row";
            }
        });
    }

    @Test
    void favoriteHideTrashAndRestoreOnlyChangeTheDatabase() throws Exception {
        try (Database database = database()) {
            ItemActions actions = actions(database, true);

            assertTrue(run(actions, ItemAction.FAVORITE, item(database)));
            assertTrue(item(database).favorite());
            assertTrue(run(actions, ItemAction.HIDE, item(database)));
            assertTrue(item(database).hidden());
            assertTrue(run(actions, ItemAction.TRASH, item(database)));
            assertEquals(1234L, ((Number) column(database, "trashed_at")).longValue());
            TimelineItem trashed = item(database);
            assertTrue(trashed.trashed() && trashed.favorite() && trashed.hidden(), "kept for a restore");
            assertTrue(run(actions, ItemAction.RESTORE, item(database)));
            assertFalse(item(database).trashed());
            assertTrue(run(actions, ItemAction.UNHIDE, item(database)));
            assertTrue(run(actions, ItemAction.UNFAVORITE, item(database)));
            TimelineItem back = item(database);
            assertFalse(back.favorite() || back.hidden() || back.trashed());

            assertTrue(Files.exists(file), "the file is never touched by these");
            assertEquals(List.of(), binned);
            assertEquals(6, messages.size());
            assertTrue(messages.get(2).contains("Trash"), messages.get(2));
        }
    }

    @Test
    void deletePermanentlyAsksThenRemovesTheFileTheRowAndTheThumbnail() throws Exception {
        try (Database database = database()) {
            run(actions(database, true), ItemAction.TRASH, item(database));
            TimelineItem trashed = item(database);

            assertFalse(run(actions(database, false), ItemAction.DELETE_PERMANENTLY, trashed), "cancelled");
            assertTrue(Files.exists(file));
            assertEquals(1234L, ((Number) column(database, "trashed_at")).longValue());

            assertTrue(run(actions(database, true), ItemAction.DELETE_PERMANENTLY, trashed));
            assertEquals(List.of(file.toFile()), binned, "to the recycle bin");
            assertFalse(Files.exists(file));
            assertFalse(Files.exists(thumbnail));
            assertEquals("no row", column(database, "id"));
            assertEquals("Moved a.jpg to the Recycle Bin.", messages.getLast());
        }
    }

    @Test
    void ifTheFileCantBeRemovedItStaysInTrash() throws Exception {
        try (Database database = database()) {
            run(actions(database, true), ItemAction.TRASH, item(database));
            // A failure only reports (no reload), so wait for the message instead of the reload.
            CountDownLatch reported = new CountDownLatch(1);
            ItemActions refusing = new ItemActions(new MediaFlags(database), new FileRemover(_ -> false),
                    _ -> thumbnail, _ -> true, Runnable::run, message -> {
                        messages.add(message);
                        reported.countDown();
                    }, () -> { }, () -> 1L);

            assertTrue(refusing.perform(ItemAction.DELETE_PERMANENTLY, item(database)));
            assertTrue(reported.await(5, TimeUnit.SECONDS));
            assertTrue(messages.getLast().startsWith("Could not delete a.jpg"), messages.getLast());
            assertTrue(messages.getLast().endsWith("It is still in Trash."), messages.getLast());
            assertTrue(Files.exists(file));
            assertTrue(Files.exists(thumbnail));
            assertTrue(item(database).trashed());
        }
    }

    @Test
    void aFileAlreadyGoneIsJustForgotten() throws Exception {
        try (Database database = database()) {
            run(actions(database, true), ItemAction.TRASH, item(database));
            Files.delete(file);

            assertTrue(run(actions(database, true), ItemAction.DELETE_PERMANENTLY, item(database)));
            assertEquals("no row", column(database, "id"));
            assertEquals(List.of(), binned);
            assertTrue(messages.getLast().contains("already gone"), messages.getLast());
        }
    }

    // --- The File Explorer Hidden mark (P6) ---

    /** Every set / clear of the file's Hidden attribute, as "a.jpg=true". */
    private final List<String> marks = new ArrayList<>();

    private ItemActions withMode(Database database, HiddenMode mode) {
        return new ItemActions(new MediaFlags(database), new FileRemover(_ -> false), _ -> thumbnail, _ -> true,
                Runnable::run, messages::add, () -> changed.countDown(), () -> 1L, () -> mode)
                .withMark(true, (f, hidden) -> marks.add(f.getFileName() + "=" + hidden));
    }

    private static void setColumns(Database database, String set) throws Exception {
        database.write(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE media SET " + set + " WHERE id = 1")) {
                return update.executeUpdate();
            }
        }).get();
    }

    @Test
    void inSeparateModeHideLeavesTheFileAlone() throws Exception {
        try (Database database = database()) {
            assertTrue(run(withMode(database, HiddenMode.SEPARATE), ItemAction.HIDE, item(database)));

            assertEquals(List.of(), marks);
            assertEquals(1L, ((Number) column(database, "hidden")).longValue());
            assertEquals(0L, ((Number) column(database, "os_hidden")).longValue());
        }
    }

    @Test
    void inBothWaysModeHideAlsoMarksTheFileAndUnhideClearsIt() throws Exception {
        try (Database database = database()) {
            ItemActions actions = withMode(database, HiddenMode.BOTH_WAYS);

            assertTrue(run(actions, ItemAction.HIDE, item(database)));
            TimelineItem hidden = item(database);
            assertTrue(hidden.hidden() && hidden.osHidden(), "both marks set");
            assertTrue(run(actions, ItemAction.UNHIDE, hidden));

            assertEquals(List.of("a.jpg=true", "a.jpg=false"), marks);
            TimelineItem back = item(database);
            assertFalse(back.hidden() || back.osHidden(), "back on the Timeline");
        }
    }

    @Test
    void hideInFileExplorerSetsOnlyTheFilesMarkAndUnhideThereClearsIt() throws Exception {
        try (Database database = database()) {
            ItemActions actions = withMode(database, HiddenMode.SEPARATE);

            assertTrue(run(actions, ItemAction.HIDE_IN_EXPLORER, item(database)));
            TimelineItem marked = item(database);
            assertTrue(marked.osHidden() && !marked.hidden(), "Prismo's own Hide untouched");
            assertEquals(ItemAction.UNHIDE_IN_EXPLORER, ItemAction.available(marked).get(1),
                    "on Hidden, the eye clears the Explorer mark");
            assertTrue(run(actions, ItemAction.UNHIDE_IN_EXPLORER, marked));

            assertEquals(List.of("a.jpg=true", "a.jpg=false"), marks);
            assertFalse(item(database).osHidden());
            assertTrue(messages.getFirst().startsWith("Marked a.jpg Hidden in File Explorer"), messages.getFirst());
        }
    }

    @Test
    void ifTheFileCantBeMarkedPrismoStillHidesItAndSaysWhy() throws Exception {
        try (Database database = database()) {
            ItemActions refusing = new ItemActions(new MediaFlags(database), new FileRemover(_ -> false),
                    _ -> thumbnail, _ -> true, Runnable::run, messages::add, () -> changed.countDown(), () -> 1L,
                    () -> HiddenMode.BOTH_WAYS).withMark(true, (_, _) -> {
                        throw new IOException("Access is denied");
                    });

            assertTrue(run(refusing, ItemAction.HIDE, item(database)));

            TimelineItem hidden = item(database);
            assertTrue(hidden.hidden() && !hidden.osHidden());
            assertEquals("Could not mark a.jpg Hidden in File Explorer (Access is denied). It is hidden in Prismo only.",
                    messages.getLast());
        }
    }

    @Test
    void separateUnhideOfAFileStillMarkedInExplorerSaysWhereItStays() throws Exception {
        try (Database database = database()) {
            setColumns(database, "hidden = 1, os_hidden = 1");

            assertTrue(run(withMode(database, HiddenMode.SEPARATE), ItemAction.UNHIDE, item(database)));

            assertEquals(List.of(), marks, "Separate: the file is left alone");
            assertTrue(item(database).osHidden());
            assertTrue(messages.getLast().contains("stays in Collections › Hidden"), messages.getLast());
        }
    }

    @Test
    void withTheSettingsSwitchOnTheMessagesDontSendFilesToHidden() throws Exception {
        try (Database database = database()) {
            setColumns(database, "hidden = 1, os_hidden = 1");
            ItemActions actions = withMode(database, HiddenMode.SEPARATE);
            actions.setOsHiddenShown(() -> true);

            assertTrue(run(actions, ItemAction.UNHIDE, item(database)));
            assertEquals("a.jpg is no longer hidden.", messages.getLast(), "it shows everywhere now");
            assertTrue(run(actions, ItemAction.HIDE_IN_EXPLORER, item(database)));
            assertEquals("Marked a.jpg Hidden in File Explorer. Prismo still shows it (Settings).", messages.getLast());
        }
    }

    @Test
    void withoutAHiddenMarkOnThisSystemTheExplorerActionsDoNothing() throws Exception {
        try (Database database = database()) {
            ItemActions actions = withMode(database, HiddenMode.BOTH_WAYS).withMark(false, (_, _) -> marks.add("x"));

            assertFalse(actions.perform(ItemAction.HIDE_IN_EXPLORER, item(database)));
            assertTrue(run(actions, ItemAction.HIDE, item(database)), "Hide still works, in Prismo only");
            assertEquals(List.of(), marks);
            assertEquals(0L, ((Number) column(database, "os_hidden")).longValue());
        }
    }

    @Test
    void theHiddenModeSettingReadsBackAndDefaultsToSeparate() {
        for (HiddenMode mode : HiddenMode.values()) {
            assertEquals(mode, HiddenMode.fromSetting(mode.settingValue()));
        }
        assertEquals(HiddenMode.SEPARATE, HiddenMode.fromSetting(null));
        assertEquals(HiddenMode.SEPARATE, HiddenMode.fromSetting("sometimes"));
        assertEquals("both", HiddenMode.BOTH_WAYS.settingValue(), "stored: never rename");
    }

    @Test
    void anItemWithTheSameRefIsFoundAfterAReload() {
        // TimelineItem equality includes the flags; the viewer and grids match items by MediaRef id.
        MediaRef ref = new MediaRef(1, Path.of("a.jpg"), "jpg", 0);
        TimelineItem plain = new TimelineItem(ref, 0, 0, 0, false, false, false);
        assertEquals(plain.ref(), plain.withFavorite(true).ref());
    }

    // --- Selections (P7) ---

    /** A library of {@code count} photos: ids 1..count, files p1.jpg, p2.jpg … in the temp folder. */
    private Database libraryOf(int count) throws Exception {
        List<Path> photos = new ArrayList<>();
        for (int id = 1; id <= count; id++) {
            photos.add(Files.writeString(tmp.resolve("p" + id + ".jpg"), "x"));
        }
        Database database = Database.open(tmp.resolve("selection.db"));
        database.write(c -> {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                       date_modified, date_added)
                    VALUES (?, ?, ?, ?, 'jpg', 'photo', 1, ?, 0)""")) {
                for (int i = 0; i < photos.size(); i++) {
                    insert.setLong(1, i + 1);
                    insert.setString(2, photos.get(i).toString());
                    insert.setString(3, tmp.toString());
                    insert.setString(4, photos.get(i).getFileName().toString());
                    insert.setLong(5, 1000L - i); // newest first = p1, p2, p3 …
                    insert.executeUpdate();
                }
            }
            return null;
        }).get();
        return database;
    }

    private static List<TimelineItem> page(Database database, MediaScope scope) throws Exception {
        return database.read(c -> TimelineQuery.load(c, scope, ViewOptions.DEFAULT, ZoneId.of("UTC")));
    }

    private static void setColumns(Database database, long id, String set) throws Exception {
        database.write(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE media SET " + set + " WHERE id = " + id)) {
                return update.executeUpdate();
            }
        }).get();
    }

    /** Runs a selection's action and waits until the app would reload. */
    private boolean runAll(ItemActions actions, ItemAction action, List<TimelineItem> items) throws Exception {
        changed = new CountDownLatch(1);
        boolean done = actions.performAll(action, items);
        if (done) {
            assertTrue(changed.await(5, TimeUnit.SECONDS), "the app is told the library changed");
        }
        return done;
    }

    @Test
    void aSelectionChangesEveryItemAtOnceWithOneMessage() throws Exception {
        try (Database database = libraryOf(3)) {
            setColumns(database, 2, "favorite = 1");
            ItemActions actions = actions(database, true);

            assertTrue(runAll(actions, ItemAction.FAVORITE, page(database, MediaScope.LIBRARY)));
            assertEquals(List.of("Added 2 items to Favorites."), messages, "the favorite already was one");
            assertEquals(3, page(database, MediaScope.FAVORITES).size());

            assertTrue(runAll(actions, ItemAction.HIDE, page(database, MediaScope.LIBRARY)));
            assertEquals("Hid 3 items. Hidden items are only in Collections › Hidden.", messages.getLast());
            assertEquals(List.of(), page(database, MediaScope.LIBRARY));
            assertTrue(runAll(actions, ItemAction.TRASH, page(database, MediaScope.HIDDEN)));
            assertEquals(3, page(database, MediaScope.TRASH).size());
            assertTrue(runAll(actions, ItemAction.RESTORE, page(database, MediaScope.TRASH)));
            assertEquals("Restored 3 items.", messages.getLast());
            assertTrue(runAll(actions, ItemAction.UNHIDE, page(database, MediaScope.HIDDEN)));
            assertTrue(runAll(actions, ItemAction.UNFAVORITE, page(database, MediaScope.LIBRARY)));
            assertEquals(3, page(database, MediaScope.LIBRARY).size());
            assertEquals(List.of(), page(database, MediaScope.FAVORITES));
            assertEquals(6, messages.size(), "one message per action, not per item");
            assertEquals(List.of(), binned, "files are never touched by these");
        }
    }

    @Test
    void aSelectionOfOneSaysTheFilesName() throws Exception {
        try (Database database = libraryOf(2)) {
            setColumns(database, 2, "favorite = 1");

            assertTrue(runAll(actions(database, true), ItemAction.FAVORITE, page(database, MediaScope.LIBRARY)));
            assertEquals("Added p1.jpg to Favorites.", messages.getLast());
            assertFalse(actions(database, true).performAll(ItemAction.FAVORITE, page(database, MediaScope.FAVORITES)),
                    "nothing left to favorite");
        }
    }

    @Test
    void deletingASelectionAsksOnceThenRemovesEveryFile() throws Exception {
        try (Database database = libraryOf(3)) {
            runAll(actions(database, true), ItemAction.TRASH, page(database, MediaScope.LIBRARY));
            List<Integer> asked = new ArrayList<>();
            ItemActions cancelling = new ItemActions(new MediaFlags(database),
                    new FileRemover(f -> binned.add(f) && f.delete()), _ -> thumbnail, items -> asked.add(items.size())
                    && false, Runnable::run, messages::add, () -> changed.countDown(), () -> 1L);

            assertFalse(cancelling.performAll(ItemAction.DELETE_PERMANENTLY, page(database, MediaScope.TRASH)));
            assertEquals(List.of(3), asked, "one question for the three");
            assertEquals(List.of(), binned);

            assertTrue(runAll(actions(database, true), ItemAction.DELETE_PERMANENTLY, page(database, MediaScope.TRASH)));
            assertEquals(3, binned.size());
            assertEquals(List.of(), page(database, MediaScope.TRASH), "every row forgotten");
            assertFalse(Files.exists(thumbnail));
            assertEquals("Moved 3 items to the Recycle Bin.", messages.getLast());
        }
    }

    @Test
    void aFileThatCantBeDeletedStaysInTrashAndIsCounted() throws Exception {
        try (Database database = libraryOf(3)) {
            runAll(actions(database, true), ItemAction.TRASH, page(database, MediaScope.LIBRARY));
            Files.delete(tmp.resolve("p3.jpg"));
            FileRemover picky = new FileRemover(f -> !f.getName().equals("p2.jpg") && binned.add(f) && f.delete());

            assertTrue(runAll(actions(database, true, picky), ItemAction.DELETE_PERMANENTLY,
                    page(database, MediaScope.TRASH)));
            assertEquals("Moved 1 item to the Recycle Bin. 1 item was already gone from disk; removed from Prismo."
                    + " Could not delete 1 item (the Recycle Bin refused it (in use, or no permission?)); it is still"
                    + " in Trash.", messages.getLast());
            assertEquals(List.of("p2.jpg"), page(database, MediaScope.TRASH).stream()
                    .map(item -> item.ref().path().getFileName().toString()).toList());
        }
    }

    @Test
    void bothWaysMarksEveryFileAndCountsTheOnesThatFailed() throws Exception {
        try (Database database = libraryOf(3)) {
            ItemActions actions = new ItemActions(new MediaFlags(database), new FileRemover(_ -> false),
                    _ -> thumbnail, _ -> true, Runnable::run, messages::add, () -> changed.countDown(), () -> 1L,
                    () -> HiddenMode.BOTH_WAYS).withMark(true, (f, hidden) -> {
                        if (f.getFileName().toString().equals("p2.jpg")) {
                            throw new IOException("Access is denied");
                        }
                        marks.add(f.getFileName() + "=" + hidden);
                    });

            assertTrue(runAll(actions, ItemAction.HIDE, page(database, MediaScope.LIBRARY)));
            assertEquals(List.of("p1.jpg=true", "p3.jpg=true"), marks);
            List<TimelineItem> hidden = page(database, MediaScope.HIDDEN);
            assertEquals(List.of(true, false, true), hidden.stream().map(TimelineItem::osHidden).toList());
            assertTrue(hidden.stream().allMatch(TimelineItem::hidden), "p2 is hidden in Prismo at least");
            assertEquals("Hid 3 items. Hidden items are only in Collections › Hidden. Could not change the File"
                    + " Explorer mark of 1 item (Access is denied).", messages.getLast());
        }
    }

    @Test
    void unhidingTheHiddenPageClearsExplorerOnlyMarksAndSaysWhatStays() throws Exception {
        try (Database database = libraryOf(3)) {
            setColumns(database, 1, "hidden = 1");
            setColumns(database, 2, "os_hidden = 1");
            setColumns(database, 3, "hidden = 1, os_hidden = 1");
            ItemActions actions = withMode(database, HiddenMode.SEPARATE);

            assertTrue(runAll(actions, ItemAction.UNHIDE, page(database, MediaScope.HIDDEN)));
            assertEquals(List.of("p2.jpg=false"), marks, "only the file hidden just in Explorer is changed");
            assertEquals(List.of("p3.jpg"), page(database, MediaScope.HIDDEN).stream()
                    .map(item -> item.ref().path().getFileName().toString()).toList());
            assertEquals("3 items are no longer hidden. 1 of them is still marked Hidden in File Explorer, so it"
                    + " stays in Collections › Hidden.", messages.getLast());
        }
    }

    @Test
    void selectionTextsCountItems() {
        assertEquals("1 item", ItemActions.itemCount(1));
        assertEquals("1,200 items", ItemActions.itemCount(1200));
        assertEquals("Moved 2 items to Trash (still on disk). Restore them from Collections › Trash.",
                ItemActions.selectionText(ItemAction.TRASH, 2));
        assertEquals("1 item is no longer hidden.", ItemActions.selectionText(ItemAction.UNHIDE, 1));
        assertEquals("Removed 4 items from Favorites.", ItemActions.selectionText(ItemAction.UNFAVORITE, 4));
        assertEquals("Marked 2 items Hidden in File Explorer.", ItemActions.selectionText(ItemAction.HIDE_IN_EXPLORER, 2));
        assertEquals("Cleared the Hidden mark of 2 items in File Explorer.",
                ItemActions.selectionText(ItemAction.UNHIDE_IN_EXPLORER, 2));
        assertEquals("Deleted 2 items.", ItemActions.selectionText(ItemAction.DELETE_PERMANENTLY, 2));
        java.util.Map<FileRemover.Outcome, Integer> outcomes = new java.util.EnumMap<>(FileRemover.Outcome.class);
        outcomes.put(FileRemover.Outcome.ALREADY_GONE, 2);
        outcomes.put(FileRemover.Outcome.DELETED, 1);
        assertEquals("Deleted 1 item. 2 items were already gone from disk; removed from Prismo.",
                ItemActions.deletedText(outcomes));
    }
}
