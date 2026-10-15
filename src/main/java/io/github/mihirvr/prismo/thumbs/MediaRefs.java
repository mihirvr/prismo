package io.github.mihirvr.prismo.thumbs;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Queries that hand the thumbnail code its {@link MediaRef}s. */
public final class MediaRefs {

    private MediaRefs() {
    }

    /** The newest {@code limit} photos and GIFs not in Trash: the order the Timeline will show them in. */
    public static List<MediaRef> newestImages(Connection connection, int limit) throws SQLException {
        List<MediaRef> refs = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, path, extension, date_modified FROM media
                WHERE media_type IN ('photo', 'gif') AND trashed_at IS NULL
                ORDER BY sort_date DESC
                LIMIT ?""")) {
            select.setInt(1, limit);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    refs.add(new MediaRef(rows.getLong(1), Path.of(rows.getString(2)), rows.getString(3), rows.getLong(4)));
                }
            }
        }
        return refs;
    }
}
