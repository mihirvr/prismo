package io.github.mihirvr.prismo.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

/**
 * Where each video was left: {@code media.playback_position_seconds} (in the schema since V1, used from P6).
 * The indexer keeps it when a file is indexed again; it is Prismo's own data, the file is never touched.
 */
public final class PlaybackPositions {

    private final Database database;

    public PlaybackPositions(Database database) {
        this.database = database;
    }

    /** The saved position in seconds, or 0 if none (never played, not a video, or forgotten). */
    public double load(long mediaId) throws SQLException {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT playback_position_seconds FROM media WHERE id = ?")) {
                select.setLong(1, mediaId);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? rows.getDouble(1) : 0.0; // NULL reads as 0
                }
            }
        });
    }

    /**
     * Saves where video {@code mediaId} is ({@code seconds}). Also fills in its length if the index has none
     * ({@code durationSeconds} from the player: the indexer can't read MKV / WebM lengths), so its thumbnail can
     * show the length and the progress bar. Videos only: the schema refuses a position on a photo.
     */
    public CompletableFuture<Void> save(long mediaId, double seconds, double durationSeconds) {
        return database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE media SET playback_position_seconds = ?,
                                     duration_seconds = coalesce(duration_seconds, ?)
                    WHERE id = ? AND media_type = 'video'""")) {
                update.setDouble(1, Math.max(0, seconds));
                if (durationSeconds > 0) {
                    update.setDouble(2, durationSeconds);
                } else {
                    update.setNull(2, java.sql.Types.REAL);
                }
                update.setLong(3, mediaId);
                update.executeUpdate();
            }
            return null;
        });
    }
}
