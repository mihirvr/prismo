package io.github.mihirvr.prismo.db;

import io.github.mihirvr.prismo.video.TrackChoice;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.CompletableFuture;

/**
 * What the user picked for each video (P8): sound track, subtitles and subtitle delay, in the {@code video_choices}
 * table (V6), one row per video. Like {@link PlaybackPositions}: read by key, written through the writer thread; the
 * row goes when the video's {@code media} row does (a foreign key with {@code ON DELETE CASCADE}).
 */
public final class TrackChoices {

    private final Database database;

    public TrackChoices(Database database) {
        this.database = database;
    }

    /** What was picked for video {@code mediaId}, or {@link TrackChoice.Picked#NOTHING}. */
    public TrackChoice.Picked load(long mediaId) throws SQLException {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT audio_stream, subtitle, subtitle_stream, subtitle_file, subtitle_delay_micros
                    FROM video_choices WHERE media_id = ?""")) {
                select.setLong(1, mediaId);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        return TrackChoice.Picked.NOTHING;
                    }
                    int audio = row.getObject(1) == null ? -1 : row.getInt(1);
                    return new TrackChoice.Picked(audio, subtitles(row.getString(2), row.getObject(3), row.getString(4)),
                            row.getLong(5));
                }
            }
        });
    }

    /** A saved subtitle pick back; null if none was saved (or the row can't be read as one: then it is ignored). */
    private static TrackChoice.SubtitlePick subtitles(String word, Object stream, String file) {
        TrackChoice.Kind kind = TrackChoice.Kind.fromWord(word);
        if (kind == null) {
            return null;
        }
        try {
            return new TrackChoice.SubtitlePick(kind, stream == null ? -1 : ((Number) stream).intValue(), file);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Saves everything picked for video {@code mediaId} (replacing what was there). Videos only: a photo's id writes
     * nothing. Nothing picked removes the row.
     */
    public CompletableFuture<Void> save(long mediaId, TrackChoice.Picked picked) {
        return database.write(connection -> {
            if (picked.isEmpty()) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM video_choices WHERE media_id = ?")) {
                    delete.setLong(1, mediaId);
                    delete.executeUpdate();
                }
                return null;
            }
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO video_choices (media_id, audio_stream, subtitle, subtitle_stream, subtitle_file,
                                               subtitle_delay_micros)
                    SELECT id, ?, ?, ?, ?, ? FROM media WHERE id = ? AND media_type = 'video'
                    ON CONFLICT (media_id) DO UPDATE SET
                        audio_stream = excluded.audio_stream, subtitle = excluded.subtitle,
                        subtitle_stream = excluded.subtitle_stream, subtitle_file = excluded.subtitle_file,
                        subtitle_delay_micros = excluded.subtitle_delay_micros""")) {
                if (picked.audioStream() >= 0) {
                    upsert.setInt(1, picked.audioStream());
                } else {
                    upsert.setNull(1, Types.INTEGER);
                }
                TrackChoice.SubtitlePick subtitles = picked.subtitles();
                if (subtitles == null) {
                    upsert.setNull(2, Types.VARCHAR);
                    upsert.setNull(3, Types.INTEGER);
                    upsert.setNull(4, Types.VARCHAR);
                } else {
                    upsert.setString(2, subtitles.kind().word());
                    if (subtitles.kind() == TrackChoice.Kind.TRACK) {
                        upsert.setInt(3, subtitles.stream());
                    } else {
                        upsert.setNull(3, Types.INTEGER);
                    }
                    upsert.setString(4, subtitles.kind() == TrackChoice.Kind.FILE ? subtitles.file() : null);
                }
                upsert.setLong(5, picked.subtitleDelayMicros());
                upsert.setLong(6, mediaId);
                upsert.executeUpdate();
            }
            return null;
        });
    }
}
