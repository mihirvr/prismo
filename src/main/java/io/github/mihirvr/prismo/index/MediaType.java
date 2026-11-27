package io.github.mihirvr.prismo.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The kinds of media Prismo indexes, decided by file extension. Matches the {@code media_type} column. */
public enum MediaType {
    PHOTO("photo"),
    VIDEO("video"),
    GIF("gif"),
    RAW("raw"),
    /** 2.0.0: music, voice notes, audiobooks. Shown only under the Audio filter (ViewOptions#sqlCondition). */
    AUDIO("audio");

    // 2.0.0: JPE, JFIF (JPEG under other names), HIF (Fujifilm's HEIF), AVIF; 3GP (older phones), MTS / M2TS
    // (camcorders, AVCHD), TS (TV recordings; checked by content, see #ofFile), WMV, FLV, MPG / MPEG, OGV.
    private static final Map<String, MediaType> BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", PHOTO), Map.entry("jpeg", PHOTO), Map.entry("jpe", PHOTO), Map.entry("jfif", PHOTO),
            Map.entry("png", PHOTO), Map.entry("webp", PHOTO), Map.entry("avif", PHOTO),
            Map.entry("heic", PHOTO), Map.entry("heif", PHOTO), Map.entry("hif", PHOTO),
            Map.entry("bmp", PHOTO), Map.entry("tif", PHOTO), Map.entry("tiff", PHOTO),
            Map.entry("gif", GIF),
            Map.entry("mp4", VIDEO), Map.entry("m4v", VIDEO), Map.entry("mov", VIDEO),
            Map.entry("mkv", VIDEO), Map.entry("webm", VIDEO), Map.entry("avi", VIDEO),
            Map.entry("3gp", VIDEO), Map.entry("mts", VIDEO), Map.entry("m2ts", VIDEO), Map.entry("ts", VIDEO),
            Map.entry("wmv", VIDEO), Map.entry("flv", VIDEO), Map.entry("mpg", VIDEO), Map.entry("mpeg", VIDEO),
            Map.entry("ogv", VIDEO),
            // 2.0.0: audio, everything the bundled FFmpeg plays. Checked with a made-up file of each: MP3, MP2, M4A
            // (AAC and ALAC), M4B (audiobooks), M4R (iPhone ringtones), AAC, FLAC, WAV, AIF / AIFF / AIFC, OGG, OGA,
            // OPUS, SPX, WMA, WV, MKA, AC3, DTS, TTA, AU / SND, CAF, WEBA, 3GA, AMR, AWB. Not checked (FFmpeg has the
            // reader, but no way to make a file): APE, DSF, DFF, MPC, TAK.
            Map.entry("mp3", AUDIO), Map.entry("mp2", AUDIO), Map.entry("m4a", AUDIO), Map.entry("m4b", AUDIO),
            Map.entry("m4r", AUDIO), Map.entry("aac", AUDIO), Map.entry("flac", AUDIO), Map.entry("wav", AUDIO),
            Map.entry("aif", AUDIO), Map.entry("aiff", AUDIO), Map.entry("aifc", AUDIO), Map.entry("ogg", AUDIO),
            Map.entry("oga", AUDIO), Map.entry("opus", AUDIO), Map.entry("spx", AUDIO), Map.entry("wma", AUDIO),
            Map.entry("wv", AUDIO), Map.entry("mka", AUDIO), Map.entry("ac3", AUDIO), Map.entry("dts", AUDIO),
            Map.entry("tta", AUDIO), Map.entry("au", AUDIO), Map.entry("snd", AUDIO), Map.entry("caf", AUDIO),
            Map.entry("weba", AUDIO), Map.entry("3ga", AUDIO), Map.entry("amr", AUDIO), Map.entry("awb", AUDIO),
            Map.entry("ape", AUDIO), Map.entry("dsf", AUDIO), Map.entry("dff", AUDIO), Map.entry("mpc", AUDIO),
            Map.entry("tak", AUDIO),
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

    /**
     * The media type of {@code file}: by its extension, except {@code .ts}, which is also the extension of TypeScript
     * code. A {@code .ts} counts only if it starts like an MPEG transport stream ({@link #isTransportStream}); empty
     * if Prismo doesn't index it.
     */
    public static Optional<MediaType> ofFile(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return Optional.empty();
        }
        String extension = extensionOf(name.toString());
        Optional<MediaType> type = fromExtension(extension);
        if (type.isPresent() && extension.equals("ts") && !isTransportStream(file)) {
            return Optional.empty();
        }
        return type;
    }

    /**
     * True if the file starts like an MPEG transport stream: 188-byte packets that each begin with the byte 0x47
     * (checked on the first three), or the 192-byte packets of Blu-ray / AVCHD, where the 0x47 comes after a 4-byte
     * time. A TypeScript file starts with text and never passes. False if it can't be read.
     */
    static boolean isTransportStream(Path file) {
        byte[] start = new byte[3 * 192];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(start, 0, start.length);
        } catch (IOException e) {
            return false;
        }
        return syncEvery(start, read, 0, 188) || syncEvery(start, read, 4, 192);
    }

    private static boolean syncEvery(byte[] bytes, int length, int offset, int packet) {
        for (int i = 0; i < 3; i++) {
            int at = offset + i * packet;
            if (at >= length || bytes[at] != 0x47) {
                return false;
            }
        }
        return true;
    }

    /** Every extension Prismo indexes (lower case, no dot). */
    public static java.util.Set<String> knownExtensions() {
        return BY_EXTENSION.keySet();
    }

    /** True for an extension Prismo indexes (lower case, no dot). */
    public static boolean isKnown(String extension) {
        return BY_EXTENSION.containsKey(extension);
    }

    /** The lower-case extension of a file name without the dot, or "" if it has none. */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
