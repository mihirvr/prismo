package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The Albums page's query: one {@link Album} per folder that directly holds media. */
public final class Albums {

    private Albums() {
    }

    /**
     * Every album for the shared filter (Trash and hidden left out), the one with the newest media first, then
     * by name. An album's cover is its newest photo, GIF or video: the kinds that have thumbnails.
     *
     * <p>The cover query uses SQLite's documented "bare columns" rule: with a single {@code max()} in an
     * aggregate query, the other selected columns come from the row that has that maximum.
     *
     * <p>Both group by {@code +folder}, for the reason in {@link FolderTree#load} (synthetic 100,000 rows: 255–265 ms
     * against 972–1,041 ms when SQLite walked the folder index).
     */
    public static List<Album> load(Connection connection, ViewOptions options) throws SQLException {
        Map<String, MediaRef> covers = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(coversSql(options));
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                covers.put(rows.getString(1),
                        new MediaRef(rows.getLong(2), Path.of(rows.getString(3)), rows.getString(4), rows.getLong(5)));
            }
        }
        List<Album> albums = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(countsSql(options));
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                String folder = rows.getString(1);
                Path name = Path.of(folder).getFileName();
                albums.add(new Album(folder, name == null ? folder : name.toString(), rows.getInt(2), covers.get(folder)));
            }
        }
        return albums;
    }

    static String coversSql(ViewOptions options) {
        return "SELECT folder, id, path, extension, date_modified, max(sort_date)" + where(options)
                + " AND media_type IN ('photo', 'gif', 'video') GROUP BY +folder"; // videos have thumbnails (P1)
    }

    static String countsSql(ViewOptions options) {
        return "SELECT folder, count(*), max(sort_date) AS newest" + where(options)
                + " GROUP BY +folder ORDER BY newest DESC, folder";
    }

    private static String where(ViewOptions options) {
        return " FROM media WHERE " + MediaScope.LIBRARY.sqlCondition(options.osHiddenShown())
                + (options.sqlCondition() == null ? "" : " AND " + options.sqlCondition());
    }
}
