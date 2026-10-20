package io.github.mihirvr.prismo.ui;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything the index knows about one file, for the viewer's Info panel: read from the {@code media} row (no file
 * access), and turned into plain "label: value" lines by {@link #lines}. Pure apart from {@link #load}, so the
 * wording is unit-tested.
 *
 * @param dateTaken    "floating" wall-clock time stored as if UTC (see {@code MetadataReader}), or null
 * @param dateModified the file's modified time, a real moment (epoch ms)
 * @param dateAdded    when Prismo first indexed it, a real moment (epoch ms)
 * @param width        0 if unknown
 * @param duration     a video's length in seconds, 0 if unknown
 */
public record MediaDetails(Path path, String extension, String mediaType, long sizeBytes, Long dateTaken,
        long dateModified, long dateAdded, int width, int height, String cameraMake, String cameraModel,
        double duration, boolean favorite, boolean hidden, boolean osHidden, boolean trashed) {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.UK);

    /** One line of the Info panel. */
    public record Line(String label, String value) {
    }

    /** The row of media {@code id}, or empty if it's gone (removed meanwhile). */
    public static Optional<MediaDetails> load(Connection connection, long id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT path, extension, media_type, size_bytes, date_taken, date_modified, date_added, width, height,
                       camera_make, camera_model, duration_seconds, favorite, hidden, os_hidden,
                       trashed_at IS NOT NULL
                FROM media WHERE id = ?""")) {
            select.setLong(1, id);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                long taken = row.getLong(5);
                Long dateTaken = row.wasNull() ? null : taken;
                return Optional.of(new MediaDetails(Path.of(row.getString(1)), row.getString(2), row.getString(3),
                        row.getLong(4), dateTaken, row.getLong(6), row.getLong(7), row.getInt(8), row.getInt(9),
                        row.getString(10), row.getString(11), row.getDouble(12), row.getBoolean(13),
                        row.getBoolean(14), row.getBoolean(15), row.getBoolean(16)));
            }
        }
    }

    /** The file's name ("IMG_0001.JPG"). */
    public String name() {
        return String.valueOf(path.getFileName());
    }

    /** The folder it's in, or "" for a file at the top of a drive's path. */
    public String folder() {
        Path parent = path.getParent();
        return parent == null ? "" : parent.toString();
    }

    public boolean isVideo() {
        return "video".equals(mediaType);
    }

    /**
     * The Details lines, in order; anything unknown is left out. A video's size and length are left to its own
     * section (what the player reads from the file). {@code zone}: this computer's time zone.
     */
    public List<Line> lines(ZoneId zone) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("Type", kind()));
        lines.add(new Line("Size", size(sizeBytes)));
        if (width > 0 && height > 0 && !isVideo()) {
            lines.add(new Line("Dimensions", dimensions(width, height)));
        }
        if (dateTaken != null) {
            // Wall-clock time as the camera wrote it: shown as is, never shifted by a time zone.
            lines.add(new Line("Date taken", DATE_TIME.format(Instant.ofEpochMilli(dateTaken).atOffset(ZoneOffset.UTC))));
        }
        lines.add(new Line("Date modified", DATE_TIME.format(Instant.ofEpochMilli(dateModified).atZone(zone))));
        lines.add(new Line("Added to Prismo", DATE_TIME.format(Instant.ofEpochMilli(dateAdded).atZone(zone))));
        String state = state();
        if (!state.isEmpty()) {
            lines.add(new Line("In Prismo", state));
        }
        return lines;
    }

    /** "JPG photo", "MP4 video", "GIF animation", "NEF raw photo". */
    String kind() {
        String upper = extension.toUpperCase(Locale.ROOT);
        return switch (mediaType) {
            case "video" -> upper + " video";
            case "gif" -> upper + " animation";
            case "raw" -> upper + " raw photo";
            default -> upper + " photo";
        };
    }

    /** "4.2 MB (4,404,019 bytes)"; under 1 KB only the bytes. Decimal units, like File Explorer's "Size". */
    static String size(long bytes) {
        return bytes < 1000 ? shortSize(bytes) : shortSize(bytes) + " (%,d bytes)".formatted(bytes);
    }

    /** "4.2 MB"; under 1 KB "512 bytes". Decimal units, like File Explorer. */
    public static String shortSize(long bytes) {
        if (bytes < 1000) {
            return "%,d bytes".formatted(bytes);
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return String.format(Locale.UK, "%.1f %s", value, units[unit]);
    }

    /** "4,032 × 3,024 pixels". */
    public static String dimensions(int width, int height) {
        return "%,d × %,d pixels".formatted(width, height);
    }

    /** "0:42", "12:05", "1:02:03". */
    public static String length(double seconds) {
        long s = Math.round(seconds);
        return s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * "Apple iPhone 15 Pro". The make is left out when the model already starts with its first word, as most
     * cameras write it ("Canon" + "Canon EOS R6", "NIKON CORPORATION" + "NIKON D750").
     */
    public String camera() {
        String make = cameraMake == null ? "" : cameraMake.strip();
        String model = cameraModel == null ? "" : cameraModel.strip();
        String brand = make.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!model.isEmpty() && model.toLowerCase(Locale.ROOT).startsWith(brand)) {
            return model;
        }
        return (make + " " + model).strip();
    }

    /** "Favorite · Hidden in File Explorer", or "" for none of these. */
    String state() {
        List<String> parts = new ArrayList<>();
        if (favorite) {
            parts.add("Favorite");
        }
        if (hidden) {
            parts.add("Hidden");
        }
        if (osHidden) {
            parts.add("Hidden in File Explorer");
        }
        if (trashed) {
            parts.add("In Trash");
        }
        return String.join(" · ", parts);
    }
}
