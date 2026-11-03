package io.github.mihirvr.prismo.ui;

/**
 * The shared Filters (PROJECT.md "Filters"): which media a view shows. One at a time; {@link #ALL} shows
 * everything. Used by the Timeline and by search alike, so there is one definition of each filter.
 *
 * <p>Each filter is a fixed SQL condition on the {@code media} table (no user text in it). There is no Hidden
 * filter: hidden items are shown only on the Collections page "Hidden" ({@link MediaScope}).
 */
public enum MediaFilter {
    ALL("All media", null),
    PHOTOS("Photos", "media_type = 'photo'"),
    VIDEOS("Videos", "media_type = 'video'"),
    GIFS("GIF", "media_type = 'gif'"),
    RAW("RAW", "media_type = 'raw'"),
    // 1.1.0: JPEG, PNG, HEIC and WEBP moved to the Types menu (ViewOptions#extensions), where several can be ticked.
    /** Wider than tall. Square pictures and unknown sizes are in neither Landscape nor Portrait. */
    LANDSCAPE("Landscape", "width > height"),
    PORTRAIT("Portrait", "height > width"),
    /**
     * "screenshot" anywhere in the file or folder name (Windows "Screenshot 2024-…", Android "Screenshot_…",
     * a "Screenshots" folder), or a name starting "Screen Shot" (older macOS). LIKE ignores case for A–Z.
     */
    SCREENSHOTS("Screenshots",
            "(file_name LIKE '%screenshot%' OR folder LIKE '%screenshot%' OR file_name LIKE 'screen shot%')"),
    FAVORITES("Favorites", "favorite = 1");

    private final String label;
    private final String condition;

    MediaFilter(String label, String condition) {
        this.label = label;
        this.condition = condition;
    }

    /** The SQL condition, or null for {@link #ALL}. */
    String condition() {
        return condition;
    }

    @Override
    public String toString() {
        return label; // what the filter menu shows
    }
}
