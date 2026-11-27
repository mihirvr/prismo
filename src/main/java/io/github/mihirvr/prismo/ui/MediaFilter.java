package io.github.mihirvr.prismo.ui;

/**
 * The shared Filters (PROJECT.md "Filters"): which media a view shows. One at a time; {@link #ALL} shows
 * everything. Used by the Timeline and by search alike, so there is one definition of each filter.
 *
 * <p>Each filter is a fixed SQL condition on the {@code media} table (no user text in it). There is no Hidden
 * filter: hidden items are shown only on the Collections page "Hidden" ({@link MediaScope}).
 */
public enum MediaFilter {
    /** The main view (2.0.0: "All media" before audio came): every photo and video, no audio. */
    ALL("Photos and videos", null),
    /** 2.0.0: the main view plus audio files. */
    EVERYTHING("Photos, videos and audio", null),
    PHOTOS("Photos", "media_type = 'photo'"),
    VIDEOS("Videos", "media_type = 'video'"),
    GIFS("GIF", "media_type = 'gif'"),
    RAW("RAW", "media_type = 'raw'"),
    /** 2.0.0: audio shows only here (or when an audio type is ticked); see ViewOptions#sqlCondition. */
    AUDIO("Audio", "media_type = 'audio'"),
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

    /** The SQL condition, or null for {@link #ALL} and {@link #EVERYTHING}. */
    String condition() {
        return condition;
    }

    /**
     * 2.0.0: the kinds of file this filter can show, for the Types menu (it lists only their file types): one kind for
     * Photos, Videos, GIF, RAW and Audio; every photo and video kind for the rest; audio too for {@link #EVERYTHING}.
     */
    public java.util.Set<io.github.mihirvr.prismo.index.MediaType> kinds() {
        return switch (this) {
            case PHOTOS -> java.util.EnumSet.of(io.github.mihirvr.prismo.index.MediaType.PHOTO);
            case VIDEOS -> java.util.EnumSet.of(io.github.mihirvr.prismo.index.MediaType.VIDEO);
            case GIFS -> java.util.EnumSet.of(io.github.mihirvr.prismo.index.MediaType.GIF);
            case RAW -> java.util.EnumSet.of(io.github.mihirvr.prismo.index.MediaType.RAW);
            case AUDIO -> java.util.EnumSet.of(io.github.mihirvr.prismo.index.MediaType.AUDIO);
            case EVERYTHING -> java.util.EnumSet.allOf(io.github.mihirvr.prismo.index.MediaType.class);
            default -> java.util.EnumSet.complementOf(java.util.EnumSet.of(io.github.mihirvr.prismo.index.MediaType.AUDIO));
        };
    }

    @Override
    public String toString() {
        return label; // what the filter menu shows
    }
}
