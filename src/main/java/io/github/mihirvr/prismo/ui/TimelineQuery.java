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

    /** The columns {@link #items} reads, in this order. Other queries that return Timeline items select them too. */
    public static final String COLUMNS = "id, path, extension, date_modified, sort_date, width, height";

    static final String SQL = "SELECT " + COLUMNS + """
             FROM media
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
        try (PreparedStatement select = connection.prepareStatement(SQL);
                ResultSet rows = select.executeQuery()) {
            return items(rows);
        }
    }

    /** Reads every row of a query that selects {@link #COLUMNS}. */
    public static List<TimelineItem> items(ResultSet rows) throws SQLException {
        List<TimelineItem> items = new ArrayList<>();
        while (rows.next()) {
            MediaRef ref = new MediaRef(rows.getLong(1), Path.of(rows.getString(2)), rows.getString(3),
                    rows.getLong(4));
            // getInt returns 0 for NULL (size unknown), which is what TimelineItem expects.
            items.add(new TimelineItem(ref, rows.getLong(5), rows.getInt(6), rows.getInt(7)));
        }
        return items;
    }
}
