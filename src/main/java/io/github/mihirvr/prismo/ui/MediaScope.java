package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.db.LibraryRootStore;
import java.io.File;
import java.util.List;

/**
 * Which rows of {@code media} a view starts from, before the shared Filters + Sort: the whole library (the
 * Timeline, search, Albums), one Collections page, or one folder. Every page is this one table seen through a
 * different condition (PROJECT.md "Database"): nothing is copied.
 *
 * <p>Hidden items are only on the Hidden page and items in Trash only on the Trash page; everywhere else
 * leaves both out. Every scope leaves out the rows of disabled library folders (#9 step 2).
 *
 * @param folder    the folder's path as stored in {@code media.folder}, for {@link Kind#FOLDER}; else null
 * @param recursive for a folder: also everything in the folders below it (Recursive View)
 */
public record MediaScope(Kind kind, String folder, boolean recursive) {

    public enum Kind { LIBRARY, FAVORITES, RECENTLY_ADDED, HIDDEN, TRASH, FOLDER }

    /** Not in Trash, not hidden: what every normal view shows. */
    static final String VISIBLE = "trashed_at IS NULL AND hidden = 0";

    public static final MediaScope LIBRARY = new MediaScope(Kind.LIBRARY, null, false);
    public static final MediaScope FAVORITES = new MediaScope(Kind.FAVORITES, null, false);
    public static final MediaScope RECENTLY_ADDED = new MediaScope(Kind.RECENTLY_ADDED, null, false);
    public static final MediaScope HIDDEN = new MediaScope(Kind.HIDDEN, null, false);
    public static final MediaScope TRASH = new MediaScope(Kind.TRASH, null, false);

    public MediaScope {
        if ((kind == Kind.FOLDER) != (folder != null)) {
            throw new IllegalArgumentException("a folder scope needs a folder, and only it: " + kind);
        }
    }

    /** One folder's media; with {@code recursive}, also everything below it. */
    public static MediaScope folder(String folder, boolean recursive) {
        return new MediaScope(Kind.FOLDER, folder, recursive);
    }

    /**
     * The SQL condition, with {@code ?} for each of {@link #params()}. Fixed text: a folder path only ever goes
     * in as a parameter.
     *
     * <p>"Below a folder" is a range on {@code folder}, not {@code LIKE 'path%'}: every path under
     * {@code D:\Photos} starts with {@code D:\Photos\}, so it sorts at or after that and before {@code D:\Photos]}
     * ({@code ]} is the character after {@code \}; on Linux {@code 0} after {@code /}). That also keeps
     * {@code D:\Photos 2} out and matches case exactly (paths are stored in their real form).
     *
     * <p>One folder alone uses {@code idx_media_folder} (a few rows). "Folder and below" is written {@code +folder}
     * so SQLite reads the table straight through instead of walking that index and fetching rows one by one: the
     * same reason as {@code +sort_date} ({@link ViewOptions#sqlOrderBy}). Measured (synthetic 100,000 rows, this
     * machine): the whole library through its top folder 340–353 ms against 785–794 ms for the walk.
     */
    public String sqlCondition() {
        return own() + " AND " + LibraryRootStore.NOT_IN_DISABLED_FOLDER;
    }

    private String own() {
        return switch (kind) {
            case LIBRARY, RECENTLY_ADDED -> VISIBLE;
            case FAVORITES -> VISIBLE + " AND favorite = 1";
            case HIDDEN -> "trashed_at IS NULL AND hidden = 1";
            case TRASH -> "trashed_at IS NOT NULL";
            case FOLDER -> VISIBLE
                    + (recursive ? " AND (+folder = ? OR (+folder >= ? AND +folder < ?))" : " AND folder = ?");
        };
    }

    /** The values for the {@code ?} in {@link #sqlCondition()}, in order. */
    public List<Object> params() {
        if (kind != Kind.FOLDER) {
            return List.of();
        }
        if (!recursive) {
            return List.of(folder);
        }
        String below = below(folder, File.separatorChar);
        return List.of(folder, below, below.substring(0, below.length() - 1) + (char) (File.separatorChar + 1));
    }

    /** The start every path inside {@code folder} shares: {@code D:\Photos\} (a drive root {@code D:\} as is). */
    static String below(String folder, char separator) {
        return folder.endsWith(String.valueOf(separator)) ? folder : folder + separator;
    }

    /** Recently Added always shows the newest added first, whatever the Sort says (PROJECT.md). */
    public ViewOptions applyTo(ViewOptions options) {
        return kind == Kind.RECENTLY_ADDED ? options.withSort(MediaSort.DATE_ADDED).withDescending(true) : options;
    }
}
