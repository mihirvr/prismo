package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;

/** The Timeline's query, with the shared Filters and Sort ({@link ViewOptions}). */
public final class TimelineQuery {

    /** The default view: everything, date taken, newest first. */
    static final String SQL = sql(ViewOptions.DEFAULT);

    private TimelineQuery() {
    }

    /**
     * The columns {@link #items} reads, in this order. Other queries that return Timeline items (search)
     * select them too. The fifth is the date the view groups by: {@code sort_date}, or the sort's own date
     * column for "Date modified" / "Date added". Then width, height, favorite / hidden / Trash state, a video's
     * length, where it was left (watch progress) and the operating system's Hidden mark.
     */
    public static String columns(ViewOptions options) {
        String date = options.sort().dates() == MediaSort.Dates.INSTANT ? options.sort().expression() : "sort_date";
        return "id, path, extension, date_modified, " + date
                + ", width, height, favorite, hidden, trashed_at IS NOT NULL, duration_seconds,"
                + " playback_position_seconds, os_hidden";
    }

    /**
     * Everything the Timeline shows for these options: not in Trash, not hidden, filtered and sorted.
     *
     * @param zone this computer's time zone, for the date headers of "Date modified" / "Date added"
     */
    public static List<TimelineItem> load(Connection connection, ViewOptions options, ZoneId zone)
            throws SQLException {
        return load(connection, MediaScope.LIBRARY, options, zone);
    }

    /**
     * What one view shows: the rows of {@code scope}, filtered and sorted by {@code options} (pass them through
     * {@link MediaScope#applyTo} first).
     *
     * <p>{@code id} in the same direction breaks ties, so the order never changes between reloads. The table
     * is read straight through and sorted, never walked in index order (see {@link ViewOptions#sqlOrderBy}).
     *
     * @param zone this computer's time zone, for the date headers of "Date modified" / "Date added"
     */
    public static List<TimelineItem> load(Connection connection, MediaScope scope, ViewOptions options, ZoneId zone)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql(scope, options))) {
            List<Object> params = scope.params();
            for (int i = 0; i < params.size(); i++) {
                select.setObject(i + 1, params.get(i));
            }
            try (ResultSet rows = select.executeQuery()) {
                return items(rows, options, zone);
            }
        }
    }

    /** The default view. */
    public static List<TimelineItem> load(Connection connection) throws SQLException {
        return load(connection, ViewOptions.DEFAULT, ZoneId.systemDefault());
    }

    static String sql(ViewOptions options) {
        return sql(MediaScope.LIBRARY, options);
    }

    static String sql(MediaScope scope, ViewOptions options) {
        StringBuilder sql = new StringBuilder("SELECT ").append(columns(options))
                .append(" FROM media WHERE ").append(scope.sqlCondition(options.osHiddenShown()));
        if (options.sqlCondition() != null) {
            sql.append(" AND ").append(options.sqlCondition());
        }
        return sql.append(" ORDER BY ").append(options.sqlOrderBy()).toString();
    }

    /**
     * Reads every row of a query that selects {@link #columns}. For "Date modified" / "Date added" the fifth
     * column is a real moment; it is turned into local wall-clock time stored as if it were UTC (the same
     * "floating" form as {@code sort_date}), so the date headers show the day it was on this computer.
     */
    public static List<TimelineItem> items(ResultSet rows, ViewOptions options, ZoneId zone) throws SQLException {
        boolean instants = options.sort().dates() == MediaSort.Dates.INSTANT;
        ZoneRules rules = zone.getRules();
        List<TimelineItem> items = new ArrayList<>();
        while (rows.next()) {
            MediaRef ref = new MediaRef(rows.getLong(1), Path.of(rows.getString(2)), rows.getString(3),
                    rows.getLong(4));
            long date = rows.getLong(5);
            if (instants) {
                date += rules.getOffset(Instant.ofEpochMilli(date)).getTotalSeconds() * 1000L;
            }
            // getInt / getDouble return 0 for NULL (size or length unknown), which is what TimelineItem expects.
            items.add(new TimelineItem(ref, date, rows.getInt(6), rows.getInt(7), rows.getBoolean(8),
                    rows.getBoolean(9), rows.getBoolean(10), rows.getDouble(11), rows.getDouble(12),
                    rows.getBoolean(13)));
        }
        return items;
    }
}
