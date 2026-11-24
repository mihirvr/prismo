package io.github.mihirvr.prismo.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Motion photos (1.4.0): a phone's JPEG with a short video stored after the picture, in the same file. Pixel and
 * other Android phones say where it starts in the photo's XMP (Google's "Container" items, or the older
 * {@code GCamera:MicroVideoOffset}); Samsung puts the marker {@code MotionPhoto_Data} right before it. Either way the
 * video is an MP4 that runs to the end of the file, which is checked ({@code ftyp} box) before it's used.
 *
 * <p>To play it, the clip is copied into Prismo's own cache folder ({@link #extract}); the photo itself is only read,
 * never changed. iPhone Live Photos are a separate .MOV file next to the photo and show as a video of their own.
 */
final class MotionPhoto {

    /** The XMP is near the start of the file: read this much of it for the tags. */
    private static final int XMP_WINDOW = 256 * 1024;
    private static final byte[] SAMSUNG_MARKER = "MotionPhoto_Data".getBytes(StandardCharsets.US_ASCII);
    /** Google's Container item for the video: its attributes, in any order. */
    private static final Pattern MOTION_ITEM = Pattern.compile("<Container:Item\\b[^>]*Item:Semantic=\"MotionPhoto\"[^>]*>");
    private static final Pattern ITEM_LENGTH = Pattern.compile("Item:Length=\"(\\d+)\"");
    private static final Pattern MICRO_VIDEO_OFFSET = Pattern.compile("GCamera:MicroVideoOffset=\"(\\d+)\"");
    /** Clips kept in the cache folder; older ones are removed when a new one is made. */
    static final int KEEP = 8;

    private MotionPhoto() {
    }

    /** Only JPEGs carry the kinds read here. */
    static boolean mayHaveOne(String extension) {
        String e = extension.toLowerCase(Locale.ROOT);
        return e.equals("jpg") || e.equals("jpeg");
    }

    /** Where the video starts in {@code file}, or -1 if it has none. Reads the whole file (blocking). */
    static long videoStart(Path file) throws IOException {
        return videoStart(Files.readAllBytes(file));
    }

    /** Where the video starts in the file's bytes, or -1. Pure. */
    static long videoStart(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, XMP_WINDOW), StandardCharsets.ISO_8859_1);
        Matcher item = MOTION_ITEM.matcher(head);
        if (item.find()) {
            Matcher length = ITEM_LENGTH.matcher(item.group());
            if (length.find()) {
                long start = data.length - Long.parseLong(length.group(1));
                if (isMp4At(data, start)) {
                    return start;
                }
            }
        }
        Matcher micro = MICRO_VIDEO_OFFSET.matcher(head);
        if (micro.find()) {
            long start = data.length - Long.parseLong(micro.group(1));
            if (isMp4At(data, start)) {
                return start;
            }
        }
        int marker = lastIndexOf(data, SAMSUNG_MARKER);
        if (marker >= 0 && isMp4At(data, marker + SAMSUNG_MARKER.length)) {
            return marker + SAMSUNG_MARKER.length;
        }
        return -1;
    }

    /** An MP4 starts with a box "size, ftyp": the size is 8 to a few hundred bytes. */
    static boolean isMp4At(byte[] data, long start) {
        if (start <= 0 || start + 8 > data.length) {
            return false;
        }
        int at = (int) start;
        long size = ((data[at] & 0xffL) << 24) | ((data[at + 1] & 0xff) << 16) | ((data[at + 2] & 0xff) << 8)
                | (data[at + 3] & 0xff);
        return size >= 8 && size <= 1024 && data[at + 4] == 'f' && data[at + 5] == 't' && data[at + 6] == 'y'
                && data[at + 7] == 'p';
    }

    private static int lastIndexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = data.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * The clip as its own .mp4 in {@code folder} (Prismo's cache), made once per photo version; at most {@link #KEEP}
     * are kept. Blocking.
     */
    static Path extract(Path photo, long start, Path folder) throws IOException {
        Files.createDirectories(folder);
        long modified = Files.getLastModifiedTime(photo).toMillis();
        String name = Long.toHexString(FolderListing.idOf(photo.toAbsolutePath().normalize()) ^ modified) + ".mp4";
        Path clip = folder.resolve(name);
        if (!Files.exists(clip)) {
            Path temp = folder.resolve(name + ".part");
            try (InputStream in = Files.newInputStream(photo); OutputStream out = Files.newOutputStream(temp)) {
                in.skipNBytes(start);
                in.transferTo(out);
            }
            Files.move(temp, clip, StandardCopyOption.REPLACE_EXISTING);
            tidy(folder, clip);
        }
        return clip;
    }

    /** Keeps the newest {@link #KEEP} clips (this one always). Only Prismo's own cache files are removed. */
    private static void tidy(Path folder, Path keep) {
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> clips = files.filter(f -> f.getFileName().toString().endsWith(".mp4") && !f.equals(keep))
                    .sorted(Comparator.comparingLong(MotionPhoto::modified).reversed()).toList();
            for (Path old : clips.subList(Math.min(clips.size(), KEEP - 1), clips.size())) {
                Files.deleteIfExists(old);
            }
        } catch (IOException e) {
            // a full cache folder is harmless; it's tried again next time
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
