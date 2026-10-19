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
import java.util.Optional;

/** The indexer's SQL against the {@code media} table. Each method takes the connection it should use. */
final class MediaStore {

    /**
     * What the indexer knows about an indexed file: enough to tell whether it changed since it was last
     * indexed (size + modified time), and to recognise it after a move or rename ({@link MoveMatcher}).
     */
    record Row(long id, String path, long sizeBytes, long dateModified) {
    }

    private MediaStore() {
    }

    /**
     * Every indexed file under {@code root}, by path.
     *
     * <p>Uses a range on {@code path} ({@code root\} ≤ path < {@code root]}, where ']' is the character
     * right after the separator) instead of {@code LIKE 'root%'}: a range can use the UNIQUE index on
     * path, and needs no escaping of '%' or '_' in folder names.
     */
    static Map<String, Row> rowsUnder(Connection connection, Path root) throws SQLException {
        String prefix = root.toString();
        char separator = root.getFileSystem().getSeparator().charAt(0);
        if (prefix.charAt(prefix.length() - 1) != separator) {
            prefix += separator;
        }
        String upperBound = prefix.substring(0, prefix.length() - 1) + (char) (separator + 1);

        Map<String, Row> found = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, path, size_bytes, date_modified FROM media WHERE path >= ? AND path < ?")) {
            select.setString(1, prefix);
            select.setString(2, upperBound);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Row row = new Row(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getLong(4));
                    found.put(row.path(), row);
                }
            }
        }
        return found;
    }

    /** The indexed file at exactly {@code path}, if any. */
    static Optional<Row> rowAt(Connection connection, String path) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, path, size_bytes, date_modified FROM media WHERE path = ?")) {
            select.setString(1, path);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? Optional.of(new Row(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getLong(4)))
                        : Optional.empty();
            }
        }
    }

    /**
     * Applies what {@link MoveMatcher} decided, in the caller's transaction.
     *
     * <ul>
     *   <li>A move: the row just inserted for the new path is deleted, and the old row takes the new path,
     *       so it keeps its id (thumbnail cache, viewer) and the user's state (favorite, hidden, trash,
     *       video position, date added). Delete first: {@code path} is UNIQUE.</li>
     *   <li>A removal: the row is deleted (the FTS5 triggers update search).</li>
     * </ul>
     */
    static void applyMovesAndRemovals(Connection connection, MoveMatcher.Result result) throws SQLException {
        try (PreparedStatement deleteNew = connection.prepareStatement("DELETE FROM media WHERE path = ?");
                PreparedStatement move = connection.prepareStatement(
                        "UPDATE media SET path = ?, folder = ?, file_name = ?, extension = ? WHERE id = ?");
                PreparedStatement remove = connection.prepareStatement("DELETE FROM media WHERE id = ?")) {
            for (MoveMatcher.Move m : result.moves()) {
                Path to = Path.of(m.to().path());
                String fileName = to.getFileName().toString();
                deleteNew.setString(1, m.to().path());
                deleteNew.executeUpdate();
                move.setString(1, m.to().path());
                move.setString(2, to.getParent().toString());
                move.setString(3, fileName);
                move.setString(4, MediaType.extensionOf(fileName));
                move.setLong(5, m.from().id());
                move.executeUpdate();
            }
            for (Row row : result.removed()) {
                remove.setLong(1, row.id());
                remove.addBatch();
            }
            remove.executeBatch();
        }
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
