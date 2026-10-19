package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.ui.ItemAction;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link ItemAction}s for every grid and the viewer: writes the flag ({@link MediaFlags}), then tells the
 * app the library changed (so every view reloads) and puts a short message in the status bar.
 *
 * <p>Delete permanently asks first ({@code confirm}); the file is removed on a virtual thread (it's disk I/O),
 * then the row and the cached thumbnail. If the file can't be removed, the item stays in Trash.
 */
public final class ItemActions implements ItemAction.Handler {

    private static final Logger log = LoggerFactory.getLogger(ItemActions.class);

    private final MediaFlags flags;
    private final FileRemover remover;
    private final LongFunction<Path> thumbnailFile;
    private final Predicate<TimelineItem> confirmDelete;
    private final Executor ui;
    private final Consumer<String> status;
    private final Runnable changed;
    private final LongSupplier clock;

    /**
     * @param thumbnailFile where a media id's cached thumbnail is (deleted with the file)
     * @param confirmDelete asks the user; true = go ahead (runs on the UI thread)
     * @param ui            runs on the UI thread ({@code Platform::runLater})
     * @param status        shows a message (called on the UI thread)
     * @param changed       the library changed: reload what's on screen (called on the UI thread)
     */
    public ItemActions(MediaFlags flags, FileRemover remover, LongFunction<Path> thumbnailFile,
            Predicate<TimelineItem> confirmDelete, Executor ui, Consumer<String> status, Runnable changed) {
        this(flags, remover, thumbnailFile, confirmDelete, ui, status, changed, System::currentTimeMillis);
    }

    ItemActions(MediaFlags flags, FileRemover remover, LongFunction<Path> thumbnailFile,
            Predicate<TimelineItem> confirmDelete, Executor ui, Consumer<String> status, Runnable changed,
            LongSupplier clock) {
        this.flags = flags;
        this.remover = remover;
        this.thumbnailFile = thumbnailFile;
        this.confirmDelete = confirmDelete;
        this.ui = ui;
        this.status = status;
        this.changed = changed;
        this.clock = clock;
    }

    @Override
    public boolean perform(ItemAction action, TimelineItem item) {
        long id = item.ref().id();
        String name = item.ref().path().getFileName().toString();
        switch (action) {
            case FAVORITE -> report(flags.setFavorite(id, true), "Added " + name + " to Favorites.");
            case UNFAVORITE -> report(flags.setFavorite(id, false), "Removed " + name + " from Favorites.");
            case HIDE -> report(flags.setHidden(id, true),
                    "Hid " + name + ". Hidden items are only in Collections › Hidden.");
            case UNHIDE -> report(flags.setHidden(id, false), name + " is no longer hidden.");
            case TRASH -> report(flags.trash(id, clock.getAsLong()),
                    "Moved " + name + " to Trash (still on disk). Restore it from Collections › Trash.");
            case RESTORE -> report(flags.restore(id), "Restored " + name + ".");
            case DELETE_PERMANENTLY -> {
                if (!confirmDelete.test(item)) {
                    return false;
                }
                deletePermanently(item, name);
            }
        }
        return true;
    }

    private void report(CompletableFuture<Integer> write, String done) {
        write.whenComplete((_, error) -> ui.execute(() -> {
            if (error != null) {
                log.warn("Could not save a change", error);
                status.accept("Could not save that change: " + error.getMessage());
            } else {
                status.accept(done);
            }
            changed.run();
        }));
    }

    private void deletePermanently(TimelineItem item, String name) {
        Thread.ofVirtual().name("prismo-delete").start(() -> {
            Path file = item.ref().path();
            FileRemover.Outcome outcome;
            try {
                outcome = remover.remove(file);
            } catch (IOException e) {
                log.warn("Could not delete {}", file, e);
                ui.execute(() -> status.accept("Could not delete " + name + ": " + e.getMessage()
                        + ". It is still in Trash."));
                return;
            }
            log.info("Deleted {} permanently ({})", file, outcome);
            try {
                Files.deleteIfExists(thumbnailFile.apply(item.ref().id()));
            } catch (IOException e) {
                log.debug("Could not delete the thumbnail of {}: {}", file, e.toString());
            }
            report(flags.forget(item.ref().id()), switch (outcome) {
                case RECYCLE_BIN -> "Moved " + name + " to the Recycle Bin.";
                case DELETED -> "Deleted " + name + ".";
                case ALREADY_GONE -> name + " was already gone from disk; removed it from Prismo.";
            });
        });
    }
}
