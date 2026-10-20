package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.db.Database;
import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Writes the per-item state the Collections pages show: favorite, hidden, Trash. Only Prismo's database
 * changes; the file on disk is never touched here. These columns survive re-indexing and moves (#3, #8).
 *
 * <p>Each write goes through the single writer thread ({@link Database#write}) and completes with the number
 * of rows changed (0 if the row is gone, e.g. the watcher removed it meanwhile). The forms that take many ids (a
 * selection, P7) are one transaction: every row changes, or none.
 */
public final class MediaFlags {

    private final Database database;

    public MediaFlags(Database database) {
        this.database = database;
    }

    public CompletableFuture<Integer> setFavorite(long id, boolean favorite) {
        return setFavorite(List.of(id), favorite);
    }

    public CompletableFuture<Integer> setFavorite(Collection<Long> ids, boolean favorite) {
        return update("UPDATE media SET favorite = ? WHERE id = ?", ids, favorite ? 1 : 0);
    }

    public CompletableFuture<Integer> setHidden(long id, boolean hidden) {
        return setHidden(List.of(id), hidden);
    }

    public CompletableFuture<Integer> setHidden(Collection<Long> ids, boolean hidden) {
        return update("UPDATE media SET hidden = ? WHERE id = ?", ids, hidden ? 1 : 0);
    }

    /** The operating system's Hidden mark, after Prismo set or cleared it on the file (the indexer agrees later). */
    public CompletableFuture<Integer> setOsHidden(long id, boolean osHidden) {
        return setOsHidden(List.of(id), osHidden);
    }

    public CompletableFuture<Integer> setOsHidden(Collection<Long> ids, boolean osHidden) {
        return update("UPDATE media SET os_hidden = ? WHERE id = ?", ids, osHidden ? 1 : 0);
    }

    /** Prismo's Hide and the file's Hidden mark together ("Both ways" mode). */
    public CompletableFuture<Integer> setHiddenBoth(long id, boolean hidden) {
        return setHiddenBoth(List.of(id), hidden);
    }

    public CompletableFuture<Integer> setHiddenBoth(Collection<Long> ids, boolean hidden) {
        return update("UPDATE media SET hidden = ?, os_hidden = ? WHERE id = ?", ids, hidden ? 1 : 0, hidden ? 1 : 0);
    }

    /** Moves to Prismo's Trash: {@code trashed_at} = {@code nowMillis}. Favorite / hidden are kept for a restore. */
    public CompletableFuture<Integer> trash(long id, long nowMillis) {
        return trash(List.of(id), nowMillis);
    }

    public CompletableFuture<Integer> trash(Collection<Long> ids, long nowMillis) {
        return update("UPDATE media SET trashed_at = ? WHERE id = ?", ids, nowMillis);
    }

    public CompletableFuture<Integer> restore(long id) {
        return restore(List.of(id));
    }

    public CompletableFuture<Integer> restore(Collection<Long> ids) {
        return update("UPDATE media SET trashed_at = NULL WHERE id = ?", ids);
    }

    /** Deletes the row (after the file itself was deleted). The FTS5 trigger (V3) removes it from search. */
    public CompletableFuture<Integer> forget(long id) {
        return forget(List.of(id));
    }

    public CompletableFuture<Integer> forget(Collection<Long> ids) {
        return update("DELETE FROM media WHERE id = ?", ids);
    }

    /** Runs {@code sql} once per id ({@code values}, then the id, as its parameters) in one transaction. */
    private CompletableFuture<Integer> update(String sql, Collection<Long> ids, Object... values) {
        List<Long> all = List.copyOf(ids);
        return database.write(connection -> {
            int changed = 0;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (long id : all) {
                    for (int i = 0; i < values.length; i++) {
                        statement.setObject(i + 1, values[i]);
                    }
                    statement.setLong(values.length + 1, id);
                    changed += statement.executeUpdate();
                }
            }
            return changed;
        });
    }
}
