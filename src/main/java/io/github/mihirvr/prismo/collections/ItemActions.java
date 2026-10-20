package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.platform.OsHiddenMark;
import io.github.mihirvr.prismo.ui.ItemAction;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link ItemAction}s for every grid and the viewer: writes the flag ({@link MediaFlags}), then tells the
 * app the library changed (so every view reloads) and puts a short message in the status bar.
 *
 * <p>Delete permanently asks first ({@code confirm}); the file is removed on a virtual thread (it's disk I/O),
 * then the row and the cached thumbnail. If the file can't be removed, the item stays in Trash.
 *
 * <p><b>The File Explorer Hidden mark</b> (P6, {@link HiddenMode}): "Hide / Unhide in File Explorer" set or clear
 * the file's Hidden attribute, then the {@code os_hidden} column. In {@link HiddenMode#BOTH_WAYS} mode, Hide and
 * Unhide do that too. The attribute is written on a virtual thread (disk I/O); if it can't be (a read-only file
 * or drive), Prismo's own mark is still set and the status bar says why the file's wasn't.
 */
public final class ItemActions implements ItemAction.Handler {

    private static final Logger log = LoggerFactory.getLogger(ItemActions.class);

    private final MediaFlags flags;
    private final FileRemover remover;
    private final LongFunction<Path> thumbnailFile;
    private final Predicate<List<TimelineItem>> confirmDelete;
    private final Executor ui;
    private final Consumer<String> status;
    private final Runnable changed;
    private final LongSupplier clock;
    private final Supplier<HiddenMode> hiddenMode;
    private boolean osMarkSupported = OsHiddenMark.SUPPORTED;
    private MarkWriter markWriter = OsHiddenMark::set;
    /** The Settings switch "Show files hidden in File Explorer": only changes what the status bar says. */
    private BooleanSupplier osHiddenShown = () -> false;

    /** Sets or clears the file's Hidden attribute ({@link OsHiddenMark#set}; tests use their own). */
    @FunctionalInterface
    interface MarkWriter {
        void set(Path file, boolean hidden) throws IOException;
    }

    /**
     * @param thumbnailFile where a media id's cached thumbnail is (deleted with the file)
     * @param confirmDelete asks the user about these items (one, or a selection); true = go ahead (UI thread)
     * @param ui            runs on the UI thread ({@code Platform::runLater})
     * @param status        shows a message (called on the UI thread)
     * @param changed       the library changed: reload what's on screen (called on the UI thread)
     * @param hiddenMode    the Settings choice, read at each Hide / Unhide
     */
    public ItemActions(MediaFlags flags, FileRemover remover, LongFunction<Path> thumbnailFile,
            Predicate<List<TimelineItem>> confirmDelete, Executor ui, Consumer<String> status, Runnable changed,
            Supplier<HiddenMode> hiddenMode) {
        this(flags, remover, thumbnailFile, confirmDelete, ui, status, changed, System::currentTimeMillis,
                hiddenMode);
    }

    ItemActions(MediaFlags flags, FileRemover remover, LongFunction<Path> thumbnailFile,
            Predicate<List<TimelineItem>> confirmDelete, Executor ui, Consumer<String> status, Runnable changed,
            LongSupplier clock) {
        this(flags, remover, thumbnailFile, confirmDelete, ui, status, changed, clock, () -> HiddenMode.SEPARATE);
    }

    ItemActions(MediaFlags flags, FileRemover remover, LongFunction<Path> thumbnailFile,
            Predicate<List<TimelineItem>> confirmDelete, Executor ui, Consumer<String> status, Runnable changed,
            LongSupplier clock, Supplier<HiddenMode> hiddenMode) {
        this.flags = flags;
        this.remover = remover;
        this.thumbnailFile = thumbnailFile;
        this.confirmDelete = confirmDelete;
        this.ui = ui;
        this.status = status;
        this.changed = changed;
        this.clock = clock;
        this.hiddenMode = hiddenMode;
    }

    /** The Settings switch "Show files hidden in File Explorer", read at each action (for its message). */
    public void setOsHiddenShown(BooleanSupplier osHiddenShown) {
        this.osHiddenShown = osHiddenShown;
    }

    /** For tests: pretend the system has (or hasn't) the Hidden mark, and write it with {@code writer}. */
    ItemActions withMark(boolean supported, MarkWriter writer) {
        this.osMarkSupported = supported;
        this.markWriter = writer;
        return this;
    }

    @Override
    public boolean perform(ItemAction action, TimelineItem item) {
        long id = item.ref().id();
        String name = item.ref().path().getFileName().toString();
        switch (action) {
            case FAVORITE -> report(flags.setFavorite(id, true), "Added " + name + " to Favorites.");
            case UNFAVORITE -> report(flags.setFavorite(id, false), "Removed " + name + " from Favorites.");
            case HIDE -> {
                String done = "Hid " + name + ". Hidden items are only in Collections › Hidden.";
                if (bothWays()) {
                    changeMark(item, true, () -> flags.setHiddenBoth(id, true), () -> flags.setHidden(id, true), done);
                } else {
                    report(flags.setHidden(id, true), done);
                }
            }
            case UNHIDE -> {
                if (bothWays()) {
                    changeMark(item, false, () -> flags.setHiddenBoth(id, false), () -> flags.setHidden(id, false),
                            name + " is no longer hidden.");
                } else {
                    report(flags.setHidden(id, false), item.osHidden() && !osHiddenShown.getAsBoolean()
                            ? name + " is no longer hidden in Prismo. The file is still marked Hidden in File"
                                    + " Explorer, so it stays in Collections › Hidden."
                            : name + " is no longer hidden.");
                }
            }
            case HIDE_IN_EXPLORER, UNHIDE_IN_EXPLORER -> {
                if (!osMarkSupported) {
                    return false;
                }
                boolean hide = action == ItemAction.HIDE_IN_EXPLORER;
                changeMark(item, hide, () -> flags.setOsHidden(id, hide), null, !hide
                        ? "Cleared the Hidden mark of " + name + " in File Explorer."
                        : osHiddenShown.getAsBoolean()
                                ? "Marked " + name + " Hidden in File Explorer. Prismo still shows it (Settings)."
                                : "Marked " + name + " Hidden in File Explorer. Prismo shows it only in Collections › Hidden.");
            }
            case TRASH -> report(flags.trash(id, clock.getAsLong()),
                    "Moved " + name + " to Trash (still on disk). Restore it from Collections › Trash.");
            case RESTORE -> report(flags.restore(id), "Restored " + name + ".");
            case DELETE_PERMANENTLY -> {
                if (!confirmDelete.test(List.of(item))) {
                    return false;
                }
                deletePermanently(item, name);
            }
        }
        return true;
    }

    /**
     * A selection's action (P7): each item gets {@link ItemAction#forItem}, and items it changes nothing for are left
     * out. A single item is {@link #perform} (its messages name the file). Otherwise File Explorer marks are written
     * on a virtual thread, one file after another, then every row changes at once (one transaction per kind of
     * change), and one message says how many.
     */
    @Override
    public boolean performAll(ItemAction action, List<TimelineItem> items) {
        boolean shown = osHiddenShown.getAsBoolean();
        Map<ItemAction, List<TimelineItem>> byAction = new EnumMap<>(ItemAction.class);
        for (TimelineItem item : items) {
            ItemAction own = action.forItem(item, shown);
            if (own != null && (osMarkSupported || !explorerOnly(own))) {
                byAction.computeIfAbsent(own, _ -> new ArrayList<>()).add(item);
            }
        }
        int count = byAction.values().stream().mapToInt(List::size).sum();
        if (count == 0) {
            return false;
        }
        if (count == 1) {
            Map.Entry<ItemAction, List<TimelineItem>> only = byAction.entrySet().iterator().next();
            return perform(only.getKey(), only.getValue().getFirst());
        }
        if (action == ItemAction.DELETE_PERMANENTLY) {
            List<TimelineItem> doomed = byAction.get(ItemAction.DELETE_PERMANENTLY);
            if (!confirmDelete.test(doomed)) {
                return false;
            }
            deleteAllPermanently(doomed);
            return true;
        }
        Thread.ofVirtual().name("prismo-selection").start(() -> {
            List<CompletableFuture<Integer>> writes = new ArrayList<>();
            int markFailed = 0;
            String problem = "";
            int stillMarked = 0;
            for (Map.Entry<ItemAction, List<TimelineItem>> entry : byAction.entrySet()) {
                ItemAction own = entry.getKey();
                List<TimelineItem> group = entry.getValue();
                if (explorerOnly(own) || ((own == ItemAction.HIDE || own == ItemAction.UNHIDE) && bothWays())) {
                    boolean hide = own == ItemAction.HIDE || own == ItemAction.HIDE_IN_EXPLORER;
                    List<Long> marked = new ArrayList<>();
                    List<Long> unmarked = new ArrayList<>();
                    for (TimelineItem item : group) {
                        try {
                            markWriter.set(item.ref().path(), hide);
                            marked.add(item.ref().id());
                        } catch (IOException | RuntimeException e) {
                            log.warn("Could not change the Hidden attribute of {}: {}", item.ref().path(), e.toString());
                            markFailed++;
                            problem = problem.isEmpty() ? String.valueOf(e.getMessage()) : problem;
                            unmarked.add(item.ref().id());
                        }
                    }
                    if (!marked.isEmpty()) {
                        writes.add(explorerOnly(own) ? flags.setOsHidden(marked, hide) : flags.setHiddenBoth(marked, hide));
                    }
                    if (!unmarked.isEmpty() && !explorerOnly(own)) {
                        writes.add(flags.setHidden(unmarked, hide)); // Prismo's own mark, as for one item
                    }
                    continue;
                }
                List<Long> ids = group.stream().map(item -> item.ref().id()).toList();
                writes.add(switch (own) {
                    case FAVORITE -> flags.setFavorite(ids, true);
                    case UNFAVORITE -> flags.setFavorite(ids, false);
                    case HIDE -> flags.setHidden(ids, true);
                    case UNHIDE -> flags.setHidden(ids, false);
                    case TRASH -> flags.trash(ids, clock.getAsLong());
                    case RESTORE -> flags.restore(ids);
                    // Delete permanently returned above; the File Explorer pair took the branch before.
                    case HIDE_IN_EXPLORER, UNHIDE_IN_EXPLORER, DELETE_PERMANENTLY -> CompletableFuture.completedFuture(0);
                });
                if (own == ItemAction.UNHIDE && !shown) {
                    stillMarked += (int) group.stream().filter(TimelineItem::osHidden).count();
                }
            }
            String done = selectionText(action, count)
                    + (stillMarked == 0 ? "" : " %s still marked Hidden in File Explorer, so %s in Collections › Hidden."
                            .formatted(stillMarked == 1 ? "1 of them is" : "%,d of them are".formatted(stillMarked),
                                    stillMarked == 1 ? "it stays" : "they stay"))
                    + (markFailed == 0 ? "" : " Could not change the File Explorer mark of %s (%s)."
                            .formatted(itemCount(markFailed), problem));
            report(CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).thenApply(_ -> count), done);
        });
        return true;
    }

    /** The pair that only sets or clears the file's Hidden attribute (Windows). */
    private static boolean explorerOnly(ItemAction action) {
        return action == ItemAction.HIDE_IN_EXPLORER || action == ItemAction.UNHIDE_IN_EXPLORER;
    }

    /** "1 item", "12 items". */
    static String itemCount(int count) {
        return count == 1 ? "1 item" : "%,d items".formatted(count);
    }

    /** What the status bar says after a selection's action changed {@code count} items. */
    static String selectionText(ItemAction action, int count) {
        String items = itemCount(count);
        return switch (action) {
            case FAVORITE -> "Added " + items + " to Favorites.";
            case UNFAVORITE -> "Removed " + items + " from Favorites.";
            case HIDE -> "Hid " + items + ". Hidden items are only in Collections › Hidden.";
            case UNHIDE -> (count == 1 ? "1 item is" : items + " are") + " no longer hidden.";
            case HIDE_IN_EXPLORER -> "Marked " + items + " Hidden in File Explorer.";
            case UNHIDE_IN_EXPLORER -> "Cleared the Hidden mark of " + items + " in File Explorer.";
            case TRASH -> "Moved " + items + " to Trash (still on disk). Restore them from Collections › Trash.";
            case RESTORE -> "Restored " + items + ".";
            case DELETE_PERMANENTLY -> "Deleted " + items + ".";
        };
    }

    /** Deletes a selection's files one after another on a virtual thread, then forgets their rows at once. */
    private void deleteAllPermanently(List<TimelineItem> items) {
        Thread.ofVirtual().name("prismo-delete").start(() -> {
            List<Long> gone = new ArrayList<>();
            Map<FileRemover.Outcome, Integer> outcomes = new EnumMap<>(FileRemover.Outcome.class);
            int failed = 0;
            String problem = "";
            for (TimelineItem item : items) {
                Path file = item.ref().path();
                try {
                    FileRemover.Outcome outcome = remover.remove(file);
                    log.info("Deleted {} permanently ({})", file, outcome);
                    outcomes.merge(outcome, 1, Integer::sum);
                    gone.add(item.ref().id());
                    deleteThumbnail(item);
                } catch (IOException e) {
                    log.warn("Could not delete {}", file, e);
                    failed++;
                    problem = problem.isEmpty() ? String.valueOf(e.getMessage()) : problem;
                }
            }
            String text = deletedText(outcomes) + (failed == 0 ? "" : " Could not delete %s (%s); %s still in Trash."
                    .formatted(itemCount(failed), problem, failed == 1 ? "it is" : "they are"));
            if (gone.isEmpty()) {
                ui.execute(() -> status.accept(text.strip()));
            } else {
                report(flags.forget(gone), text.strip());
            }
        });
    }

    /** "Moved 3 items to the Recycle Bin. 1 item was already gone from disk; removed it from Prismo." */
    static String deletedText(Map<FileRemover.Outcome, Integer> outcomes) {
        StringBuilder text = new StringBuilder();
        outcomes.forEach((outcome, count) -> text.append(switch (outcome) {
            case RECYCLE_BIN -> "Moved " + itemCount(count) + " to the Recycle Bin. ";
            case DELETED -> "Deleted " + itemCount(count) + ". ";
            case ALREADY_GONE -> itemCount(count) + (count == 1 ? " was" : " were")
                    + " already gone from disk; removed from Prismo. ";
        }));
        return text.toString().strip();
    }

    private void deleteThumbnail(TimelineItem item) {
        try {
            Files.deleteIfExists(thumbnailFile.apply(item.ref().id()));
        } catch (IOException e) {
            log.debug("Could not delete the thumbnail of {}: {}", item.ref().path(), e.toString());
        }
    }

    private boolean bothWays() {
        return osMarkSupported && hiddenMode.get() == HiddenMode.BOTH_WAYS;
    }

    /**
     * Sets or clears the file's Hidden attribute on a virtual thread, then runs {@code thenWrite}. If the attribute
     * can't be changed, runs {@code orElse} instead (Prismo's own mark), or nothing if that is null, and says why.
     */
    private void changeMark(TimelineItem item, boolean hidden, Supplier<CompletableFuture<Integer>> thenWrite,
            Supplier<CompletableFuture<Integer>> orElse, String done) {
        Path file = item.ref().path();
        String name = file.getFileName().toString();
        Thread.ofVirtual().name("prismo-hidden-mark").start(() -> {
            try {
                markWriter.set(file, hidden);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not change the Hidden attribute of {}: {}", file, e.toString());
                String why = hidden
                        ? "Could not mark " + name + " Hidden in File Explorer (" + e.getMessage() + ")."
                        : "Could not clear the Hidden mark of " + name + " in File Explorer (" + e.getMessage() + ").";
                if (orElse == null) {
                    ui.execute(() -> status.accept(why));
                } else {
                    report(orElse.get(), why + (hidden ? " It is hidden in Prismo only." : " It is unhidden in Prismo."));
                }
                return;
            }
            log.info("{} the Hidden attribute of {}", hidden ? "Set" : "Cleared", file);
            report(thenWrite.get(), done);
        });
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
            deleteThumbnail(item);
            report(flags.forget(item.ref().id()), switch (outcome) {
                case RECYCLE_BIN -> "Moved " + name + " to the Recycle Bin.";
                case DELETED -> "Deleted " + name + ".";
                case ALREADY_GONE -> name + " was already gone from disk; removed it from Prismo.";
            });
        });
    }
}
