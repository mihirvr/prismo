package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.db.Database;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;

/**
 * Writes the per-item state the Collections pages show: favorite, hidden, Trash. Only Prismo's database
 * changes; the file on disk is never touched here. These columns survive re-indexing and moves (#3, #8).
 *
 * <p>Each write goes through the single writer thread ({@link Database#write}) and completes with the number
 * of rows changed (0 if the row is gone, e.g. the watcher removed it meanwhile).
 */
public final class MediaFlags {

    private final Database database;

    public MediaFlags(Database database) {
        this.database = database;
    }

    public CompletableFuture<Integer> setFavorite(long id, boolean favorite) {
        return update("UPDATE media SET favorite = ? WHERE id = ?", favorite ? 1 : 0, id);
    }

    public CompletableFuture<Integer> setHidden(long id, boolean hidden) {
        return update("UPDATE media SET hidden = ? WHERE id = ?", hidden ? 1 : 0, id);
    }

    /** Moves to Prismo's Trash: {@code trashed_at} = {@code nowMillis}. Favorite / hidden are kept for a restore. */
    public CompletableFuture<Integer> trash(long id, long nowMillis) {
        return update("UPDATE media SET trashed_at = ? WHERE id = ?", nowMillis, id);
    }

    public CompletableFuture<Integer> restore(long id) {
        return update("UPDATE media SET trashed_at = NULL WHERE id = ?", id);
    }

    /** Deletes the row (after the file itself was deleted). The FTS5 trigger (V3) removes it from search. */
    public CompletableFuture<Integer> forget(long id) {
        return update("DELETE FROM media WHERE id = ?", id);
    }

    private CompletableFuture<Integer> update(String sql, Object... params) {
        return database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
                return statement.executeUpdate();
            }
        });
    }
}
