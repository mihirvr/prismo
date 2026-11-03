package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.NaturalOrder;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The standalone viewer's list (1.1.0): the photos and videos in one folder, as items the viewer can show, without
 * a database. Pure file reads, so it is unit-tested.
 */
public final class FolderListing {

    private FolderListing() {
    }

    /** True for a file Prismo can show (by its extension). */
    public static boolean isMedia(Path file) {
        Path name = file.getFileName();
        return name != null && MediaType.fromExtension(MediaType.extensionOf(name.toString())).isPresent();
    }

    /** The media files directly in {@code folder}, in natural name order (2 before 10), as items. */
    public static List<TimelineItem> list(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                if (isMedia(entry) && Files.isRegularFile(entry)) {
                    files.add(entry);
                }
            }
        }
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString(), NaturalOrder.INSTANCE));
        List<TimelineItem> items = new ArrayList<>(files.size());
        for (Path file : files) {
            items.add(itemOf(file));
        }
        return items;
    }

    /** One file as an item: a stable id from its path (so its thumbnail is found again next time), its file time. */
    public static TimelineItem itemOf(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        long modified;
        try {
            modified = Files.getLastModifiedTime(absolute).toMillis();
        } catch (IOException e) {
            modified = 0; // shown as "can't read" by the viewer, like a file that went missing
        }
        String extension = MediaType.extensionOf(absolute.getFileName().toString());
        return new TimelineItem(new MediaRef(idOf(absolute), absolute, extension, modified), modified, 0, 0, false,
                false, false);
    }

    /** The index of {@code file} in {@code items}, or 0 if it isn't there. */
    public static int indexOf(List<TimelineItem> items, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).ref().path().equals(absolute)) {
                return i;
            }
        }
        return 0;
    }

    /** A positive 63-bit id from the path (FNV-1a), the same on every run. */
    static long idOf(Path absolute) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : absolute.toString().getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return (hash & Long.MAX_VALUE) | 1; // never 0 or negative
    }
}
