package io.github.mihirvr.prismo.index;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The indexer's SQL against the {@code media} table. Each method takes the connection it should use. */
final class MediaStore {

    /** What the indexer needs to decide whether a file changed since it was last indexed. */
    record Stamp(long sizeBytes, long dateModified) {
    }

    private MediaStore() {
    }

    /**
     * Size + modified time of every indexed file under {@code root}.
     *
     * <p>Uses a range on {@code path} ({@code root\} ≤ path < {@code root]}, where ']' is the character
     * right after the separator) instead of {@code LIKE 'root%'}: a range can use the UNIQUE index on
     * path, and needs no escaping of '%' or '_' in folder names.
     */
    static Map<String, Stamp> stampsUnder(Connection connection, Path root) throws SQLException {
        String prefix = root.toString();
        char separator = root.getFileSystem().getSeparator().charAt(0);
        if (prefix.charAt(prefix.length() - 1) != separator) {
            prefix += separator;
        }
        String upperBound = prefix.substring(0, prefix.length() - 1) + (char) (separator + 1);

        Map<String, Stamp> stamps = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT path, size_bytes, date_modified FROM media WHERE path >= ? AND path < ?")) {
            select.setString(1, prefix);
            select.setString(2, upperBound);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    stamps.put(rows.getString(1), new Stamp(rows.getLong(2), rows.getLong(3)));
                }
            }
        }
        return stamps;
    }

    /**
     * Inserts new files and refreshes changed ones in one batch. On a re-index only the file-derived
     * columns change; the user's own state (favorite, hidden, trash, playback position) and
     * {@code date_added} are kept.
     */
    static void upsert(Connection connection, List<MediaRecord> records, long now) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO media (path, folder, file_name, extension, media_type, size_bytes, date_modified,
                                   date_added, date_taken, width, height, camera_make, camera_model,
                                   duration_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (path) DO UPDATE SET
                    folder = excluded.folder,
                    file_name = excluded.file_name,
                    extension = excluded.extension,
                    media_type = excluded.media_type,
                    size_bytes = excluded.size_bytes,
                    date_modified = excluded.date_modified,
                    date_taken = excluded.date_taken,
                    width = excluded.width,
                    height = excluded.height,
                    camera_make = excluded.camera_make,
                    camera_model = excluded.camera_model,
                    duration_seconds = excluded.duration_seconds,
                    playback_position_seconds = CASE WHEN excluded.media_type = 'video'
                                                     THEN media.playback_position_seconds END,
                    broken = 0""")) {
            for (MediaRecord record : records) {
                upsert.setString(1, record.path());
                upsert.setString(2, record.folder());
                upsert.setString(3, record.fileName());
                upsert.setString(4, record.extension());
                upsert.setString(5, record.type().dbValue());
                upsert.setLong(6, record.sizeBytes());
                upsert.setLong(7, record.dateModified());
                upsert.setLong(8, now);
                setLong(upsert, 9, record.dateTaken());
                setInt(upsert, 10, record.width());
                setInt(upsert, 11, record.height());
                upsert.setString(12, record.cameraMake());
                upsert.setString(13, record.cameraModel());
                setDouble(upsert, 14, record.durationSeconds());
                upsert.addBatch();
            }
            upsert.executeBatch();
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.REAL);
        } else {
            statement.setDouble(index, value);
        }
    }
}
