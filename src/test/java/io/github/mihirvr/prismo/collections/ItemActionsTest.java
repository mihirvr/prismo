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

    @Test
    void anItemWithTheSameRefIsFoundAfterAReload() {
        // TimelineItem equality includes the flags; the viewer and grids match items by MediaRef id.
        MediaRef ref = new MediaRef(1, Path.of("a.jpg"), "jpg", 0);
        TimelineItem plain = new TimelineItem(ref, 0, 0, 0, false, false, false);
        assertEquals(plain.ref(), plain.withFavorite(true).ref());
    }
}
