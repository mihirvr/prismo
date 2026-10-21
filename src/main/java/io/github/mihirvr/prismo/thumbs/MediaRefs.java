package io.github.mihirvr.prismo.thumbs;

import io.github.mihirvr.prismo.db.LibraryRootStore;
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

    /** For the plan test: the query {@link #newestToThumbnail} runs. */
    static final String NEWEST_TO_THUMBNAIL = """
            SELECT id, path, extension, date_modified FROM media
            WHERE trashed_at IS NULL AND %s
            ORDER BY +sort_date DESC, id DESC""".formatted(LibraryRootStore.NOT_IN_DISABLED_FOLDER);

    /**
     * Every item not in Trash and not in a disabled library folder, newest first: the order the Timeline will show them
     * in (for the thumbnail warm-up). Every media type has a thumbnail (RAW since P7: until then the query listed only
     * photo, GIF and video, so RAW files were never warmed up and waited until they were scrolled to).
     *
     * <p>Measured in #9 (synthetic 100,000 rows, medians): this, with {@code +sort_date} and no {@code LIMIT},
     * 215–222 ms. The #4 version ({@code ORDER BY sort_date DESC LIMIT ?} with "all" as the limit) took
     * 856–862 ms: it walked {@code idx_media_sort_date} row by row (the #7 finding), and even with the walk turned
     * off, a {@code LIMIT} of 90,000 kept SQLite's "top N" sorted list (1,078–1,102 ms).
     */
    public static List<MediaRef> newestToThumbnail(Connection connection) throws SQLException {
        List<MediaRef> refs = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(NEWEST_TO_THUMBNAIL)) {
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    refs.add(new MediaRef(rows.getLong(1), Path.of(rows.getString(2)), rows.getString(3), rows.getLong(4)));
                }
            }
        }
        return refs;
    }
}
