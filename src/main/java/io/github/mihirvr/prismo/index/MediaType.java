package io.github.mihirvr.prismo.index;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The kinds of media Prismo indexes, decided by file extension. Matches the {@code media_type} column. */
public enum MediaType {
    PHOTO("photo"),
    VIDEO("video"),
    GIF("gif"),
    RAW("raw");

    private static final Map<String, MediaType> BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", PHOTO), Map.entry("jpeg", PHOTO), Map.entry("png", PHOTO),
            Map.entry("webp", PHOTO), Map.entry("heic", PHOTO), Map.entry("heif", PHOTO),
            Map.entry("bmp", PHOTO), Map.entry("tif", PHOTO), Map.entry("tiff", PHOTO),
            Map.entry("gif", GIF),
            Map.entry("mp4", VIDEO), Map.entry("m4v", VIDEO), Map.entry("mov", VIDEO),
            Map.entry("mkv", VIDEO), Map.entry("webm", VIDEO), Map.entry("avi", VIDEO),
            Map.entry("cr2", RAW), Map.entry("cr3", RAW), Map.entry("nef", RAW), Map.entry("arw", RAW),
            Map.entry("dng", RAW), Map.entry("orf", RAW), Map.entry("rw2", RAW), Map.entry("raf", RAW),
            Map.entry("rwl", RAW), Map.entry("srw", RAW), Map.entry("pef", RAW));

    private final String dbValue;

    MediaType(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The value stored in the {@code media.media_type} column. */
    public String dbValue() {
        return dbValue;
    }

    /** The media type for a file extension (any case, no dot), or empty if Prismo doesn't index it. */
    public static Optional<MediaType> fromExtension(String extension) {
        return Optional.ofNullable(BY_EXTENSION.get(extension.toLowerCase(Locale.ROOT)));
    }

    /** The lower-case extension of a file name without the dot, or "" if it has none. */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
