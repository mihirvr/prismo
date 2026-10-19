package io.github.mihirvr.prismo.search;

import io.github.mihirvr.prismo.search.SearchQuery.MonthOfYear;
import io.github.mihirvr.prismo.search.SearchQuery.Range;
import io.github.mihirvr.prismo.search.SearchQuery.Word;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineQuery;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link SearchQuery} into one SQL query over {@code media} and its FTS5 index {@code media_fts}
 * (migration V3), and runs it.
 *
 * <p>Each word becomes one condition, and all of them must hold:
 * <ul>
 *   <li>3 or more characters: {@code id IN (SELECT rowid FROM media_fts WHERE media_fts MATCH ?)}. The word
 *       goes in as a quoted FTS5 string, so whatever is typed ({@code OR}, {@code *}, {@code -}, quotes) is
 *       searched for as text and never read as FTS5 query syntax.</li>
 *   <li>1–2 characters: the trigram index can't find these, so {@code LIKE '%word%'} on the same four
 *       columns, with {@code %}, {@code _} and {@code \} escaped. Like the index, it ignores case, but only
 *       for A–Z (SQLite's built-in LIKE), and it doesn't ignore accents.</li>
 *   <li>plus, OR-ed in, the date or resolution the word stands for (see {@link SearchQuery}).</li>
 * </ul>
 * Every value is a {@code ?} parameter; the SQL text is built only from the fixed pieces below.
 *
 * <p>Results: not in Trash, not hidden, with the same Filters and Sort as the Timeline ({@link ViewOptions}).
 * A date-taken sort is written {@code +sort_date} on purpose: the unary {@code +} stops SQLite from walking
 * {@code idx_media_sort_date} to avoid sorting. For a LIKE search it otherwise did exactly that, fetching the 100,000 rows one by one in
 * date order: 639 ms against 35 ms for reading the table straight through and sorting the matches
 * (synthetic 100,000-row library, this machine).
 */
public final class SearchSql {

    private static final String FTS_MATCH = "id IN (SELECT rowid FROM media_fts WHERE media_fts MATCH ?)";
    private static final String LIKE_ANY_COLUMN = "file_name LIKE ? ESCAPE '\\' OR folder LIKE ? ESCAPE '\\'"
            + " OR camera_make LIKE ? ESCAPE '\\' OR camera_model LIKE ? ESCAPE '\\'";
    private static final String DATE_RANGE = "(sort_date >= ? AND sort_date < ?)";
    private static final String MONTH_OF_YEAR = "CAST(strftime('%m', sort_date / 1000.0, 'unixepoch') AS INTEGER) = ?";
    private static final String RESOLUTION = "(width = ? AND height = ?)";

    /** The SQL for one search and its parameters, in order. */
    record Built(String sql, List<Object> params) {
    }

    private SearchSql() {
    }

    /**
     * Runs the search; an empty query finds nothing.
     *
     * @param zone this computer's time zone, for the date headers of "Date modified" / "Date added"
     */
    public static List<TimelineItem> run(Connection connection, SearchQuery query, ViewOptions options, ZoneId zone)
            throws SQLException {
        if (query.isEmpty()) {
            return List.of();
        }
        Built built = build(query, options);
        try (PreparedStatement select = connection.prepareStatement(built.sql())) {
            for (int i = 0; i < built.params().size(); i++) {
                select.setObject(i + 1, built.params().get(i));
            }
            try (ResultSet rows = select.executeQuery()) {
                return TimelineQuery.items(rows, options, zone);
            }
        }
    }

    /** The default options: everything, newest first. */
    public static List<TimelineItem> run(Connection connection, SearchQuery query) throws SQLException {
        return run(connection, query, ViewOptions.DEFAULT, ZoneId.systemDefault());
    }

    static Built build(SearchQuery query) {
        return build(query, ViewOptions.DEFAULT);
    }

    static Built build(SearchQuery query, ViewOptions options) {
        StringBuilder sql = new StringBuilder("SELECT ").append(TimelineQuery.columns(options))
                .append(" FROM media WHERE ").append(MediaScope.LIBRARY.sqlCondition());
        if (options.sqlCondition() != null) {
            sql.append(" AND ").append(options.sqlCondition());
        }
        List<Object> params = new ArrayList<>();
        for (Word word : query.words()) {
            List<String> anyOf = new ArrayList<>();
            if (word.usesIndex()) {
                anyOf.add(FTS_MATCH);
                params.add(ftsString(word.text()));
            } else {
                anyOf.add(LIKE_ANY_COLUMN);
                String pattern = likePattern(word.text());
                params.addAll(List.of(pattern, pattern, pattern, pattern));
            }
            switch (word.date()) {
                case null -> { }
                case Range range -> {
                    anyOf.add(DATE_RANGE);
                    params.addAll(List.of(range.fromMillis(), range.untilMillis()));
                }
                case MonthOfYear month -> {
                    anyOf.add(MONTH_OF_YEAR);
                    params.add(month.month());
                }
            }
            if (word.size() != null) {
                anyOf.add(RESOLUTION);
                params.addAll(List.of(word.size().width(), word.size().height()));
            }
            sql.append(" AND (").append(String.join(" OR ", anyOf)).append(')');
        }
        sql.append(" ORDER BY ").append(options.sqlOrderBy());
        return new Built(sql.toString(), params);
    }

    /** An FTS5 string: in double quotes, with any double quote inside doubled. Matched as plain text. */
    static String ftsString(String text) {
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /** {@code %text%}, with LIKE's own special characters escaped by {@code \}. */
    static String likePattern(String text) {
        return '%' + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + '%';
    }
}
