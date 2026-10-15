package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The Timeline's query. */
public final class TimelineQuery {

    static final String SQL = """
            SELECT id, path, extension, date_modified, sort_date FROM media
            WHERE trashed_at IS NULL AND hidden = 0
            ORDER BY sort_date DESC, id DESC""";

    private TimelineQuery() {
    }

    /**
     * Everything the Timeline shows, newest first: all media types, not in Trash, not hidden.
     *
     * <p>{@code id DESC} breaks ties between files with the same date, so the order never changes between
     * reloads. It costs nothing: {@code id} is the rowid, and every SQLite index entry already ends with
     * the rowid, so {@code idx_media_sort_date} is sorted by {@code (sort_date, id)} and is walked
     * backwards without a separate sort step.
     */
    public static List<TimelineItem> load(Connection connection) throws SQLException {
        List<TimelineItem> items = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SQL);
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                MediaRef ref = new MediaRef(rows.getLong(1), Path.of(rows.getString(2)), rows.getString(3),
                        rows.getLong(4));
                items.add(new TimelineItem(ref, rows.getLong(5)));
            }
        }
        return items;
    }
}
