package io.github.mihirvr.prismo.index;

import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.bmp.BmpHeaderDirectory;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.gif.GifHeaderDirectory;
import com.drew.metadata.heif.HeifDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.webp.WebpDirectory;
import io.github.mihirvr.prismo.platform.FileReads;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the metadata Prismo stores (date taken, dimensions, camera, video duration) with
 * metadata-extractor.
 *
 * <p><b>Date taken is "floating" local time.</b> EXIF {@code DateTimeOriginal} is the camera's clock
 * reading with no time zone. We keep that wall-clock time exactly and store it as if it were UTC, so a
 * photo taken at 23:30 on a holiday groups under that day no matter where the app runs. Video creation
 * times are real UTC instants (MP4/QuickTime spec), so they are converted to wall-clock time in
 * {@code zone} first, then stored the same way.
 *
 * <p>A file whose metadata can't be read is still indexed, with the unknown fields left null.
 */
public final class MetadataReader {

    private static final Logger log = LoggerFactory.getLogger(MetadataReader.class);
    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
    /** MP4 files with an unset creation time report 1904-01-01; anything before 1971 is treated as unknown. */
    private static final Instant EARLIEST_PLAUSIBLE = Instant.parse("1971-01-01T00:00:00Z");

    private final ZoneId zone;

    public MetadataReader(ZoneId zone) {
        this.zone = zone;
    }

    public MediaRecord read(Path file, MediaType type, long sizeBytes, long dateModified) {
        return read(file, type, sizeBytes, dateModified, readMetadata(file));
    }

    /** The record for {@code file} from metadata already read ({@code null}: none could be read). */
    MediaRecord read(Path file, MediaType type, long sizeBytes, long dateModified, Metadata metadata) {
        String fileName = file.getFileName().toString();

        Long dateTaken = null;
        Integer width = null;
        Integer height = null;
        String make = null;
        String model = null;
        Double duration = null;
        if (metadata != null) {
            if (type == MediaType.VIDEO) {
                dateTaken = videoCreationTime(metadata);
                width = firstInt(metadata, Mp4VideoDirectory.class, Mp4VideoDirectory.TAG_WIDTH,
                        QuickTimeVideoDirectory.class, QuickTimeVideoDirectory.TAG_WIDTH);
                height = firstInt(metadata, Mp4VideoDirectory.class, Mp4VideoDirectory.TAG_HEIGHT,
                        QuickTimeVideoDirectory.class, QuickTimeVideoDirectory.TAG_HEIGHT);
                duration = videoDuration(metadata);
            } else {
                dateTaken = exifDateTaken(metadata);
                int[] size = imageSize(metadata);
                if (size != null) {
                    boolean rotated = isRotated90(metadata);
                    width = rotated ? size[1] : size[0];
                    height = rotated ? size[0] : size[1];
                }
            }
            make = exifString(metadata, ExifDirectoryBase.TAG_MAKE);
            model = exifString(metadata, ExifDirectoryBase.TAG_MODEL);
        }

        Path parent = file.getParent();
        return new MediaRecord(file.toString(), parent == null ? "" : parent.toString(), fileName,
                MediaType.extensionOf(fileName), type, sizeBytes, dateModified, dateTaken,
                positiveOrNull(width), positiveOrNull(height), make, model, duration);
    }

    private static Metadata readMetadata(Path file) {
        try {
            return FileReads.metadata(file);
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            // Unknown container, truncated or corrupt file: index it anyway, without metadata.
            log.debug("No metadata for {}: {}", file, e.toString());
            return null;
        }
    }

    private static Long exifDateTaken(Metadata metadata) {
        ExifSubIFDDirectory exif = photoExif(metadata);
        if (exif == null) {
            return null;
        }
        Long original = parseExifDate(exif.getString(ExifDirectoryBase.TAG_DATETIME_ORIGINAL));
        return original != null ? original : parseExifDate(exif.getString(ExifDirectoryBase.TAG_DATETIME_DIGITIZED));
    }

    /**
     * The Exif SubIFD that holds the photo's own tags (date taken, exposure): the first one with any of them, else the
     * first one. A JPEG has one; many RAW files (NEF, ARW, DNG) have several, and the first describes the raw sensor
     * data or a preview, so taking the first lost their date and exposure (P7).
     */
    public static ExifSubIFDDirectory photoExif(Metadata metadata) {
        ExifSubIFDDirectory first = null;
        for (ExifSubIFDDirectory exif : metadata.getDirectoriesOfType(ExifSubIFDDirectory.class)) {
            if (first == null) {
                first = exif;
            }
            if (exif.containsTag(ExifDirectoryBase.TAG_DATETIME_ORIGINAL)
                    || exif.containsTag(ExifDirectoryBase.TAG_DATETIME_DIGITIZED)
                    || exif.containsTag(ExifDirectoryBase.TAG_EXPOSURE_TIME)) {
                return exif;
            }
        }
        return first;
    }

    /**
     * The picture's size in a RAW (or TIFF) file without an EXIF image size: the largest image IFD0 or an Exif SubIFD
     * describes (the others are previews; Olympus ORF keeps the raw image in IFD0). Null when none says.
     */
    private static int[] largestTiffImage(Metadata metadata) {
        List<ExifDirectoryBase> directories = new ArrayList<>(metadata.getDirectoriesOfType(ExifIFD0Directory.class));
        directories.addAll(metadata.getDirectoriesOfType(ExifSubIFDDirectory.class));
        int[] largest = null;
        for (ExifDirectoryBase exif : directories) {
            Integer width = exif.getInteger(ExifDirectoryBase.TAG_IMAGE_WIDTH);
            Integer height = exif.getInteger(ExifDirectoryBase.TAG_IMAGE_HEIGHT);
            if (width != null && height != null && width > 0 && height > 0
                    && (largest == null || (long) width * height > (long) largest[0] * largest[1])) {
                largest = new int[] {width, height};
            }
        }
        return largest;
    }

    /** "2023:07:14 18:30:05" → floating epoch millis. Blank, zeroed ("0000:00:00 …") or malformed → null. */
    static Long parseExifDate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 19) {
            return null;
        }
        try {
            LocalDateTime local = LocalDateTime.parse(trimmed.substring(0, 19), EXIF_DATE);
            return local.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Long videoCreationTime(Metadata metadata) {
        Date created = firstDate(metadata, Mp4Directory.class, Mp4Directory.TAG_CREATION_TIME);
        if (created == null) {
            created = firstDate(metadata, QuickTimeDirectory.class, QuickTimeDirectory.TAG_CREATION_TIME);
        }
        if (created == null || created.toInstant().isBefore(EARLIEST_PLAUSIBLE)) {
            return null;
        }
        LocalDateTime local = LocalDateTime.ofInstant(created.toInstant(), zone);
        return local.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static Double videoDuration(Metadata metadata) {
        Double seconds = firstDouble(metadata, Mp4Directory.class, Mp4Directory.TAG_DURATION_SECONDS);
        if (seconds == null) {
            seconds = firstDouble(metadata, QuickTimeDirectory.class, QuickTimeDirectory.TAG_DURATION_SECONDS);
        }
        return seconds != null && seconds > 0 ? seconds : null;
    }

    /** Pixel size from the container's own header first (most reliable), EXIF as the fallback. */
    private static int[] imageSize(Metadata metadata) {
        int[][] candidates = {
                pair(metadata, JpegDirectory.class, JpegDirectory.TAG_IMAGE_WIDTH, JpegDirectory.TAG_IMAGE_HEIGHT),
                pair(metadata, PngDirectory.class, PngDirectory.TAG_IMAGE_WIDTH, PngDirectory.TAG_IMAGE_HEIGHT),
                pair(metadata, GifHeaderDirectory.class, GifHeaderDirectory.TAG_IMAGE_WIDTH, GifHeaderDirectory.TAG_IMAGE_HEIGHT),
                pair(metadata, WebpDirectory.class, WebpDirectory.TAG_IMAGE_WIDTH, WebpDirectory.TAG_IMAGE_HEIGHT),
                pair(metadata, HeifDirectory.class, HeifDirectory.TAG_IMAGE_WIDTH, HeifDirectory.TAG_IMAGE_HEIGHT),
                pair(metadata, BmpHeaderDirectory.class, BmpHeaderDirectory.TAG_IMAGE_WIDTH, BmpHeaderDirectory.TAG_IMAGE_HEIGHT),
                exifImageSize(photoExif(metadata)),
                largestTiffImage(metadata),
        };
        for (int[] candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static int[] exifImageSize(ExifSubIFDDirectory exif) {
        if (exif == null) {
            return null;
        }
        Integer width = exif.getInteger(ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH);
        Integer height = exif.getInteger(ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT);
        return width != null && height != null && width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    /** EXIF orientations 5–8 mean the stored pixels must be turned 90° to display, so width/height swap. */
    private static boolean isRotated90(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        Integer orientation = ifd0 == null ? null : ifd0.getInteger(ExifDirectoryBase.TAG_ORIENTATION);
        return orientation != null && orientation >= 5 && orientation <= 8;
    }

    private static String exifString(Metadata metadata, int tag) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        String value = ifd0 == null ? null : ifd0.getString(tag);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int[] pair(Metadata metadata, Class<? extends Directory> type, int widthTag, int heightTag) {
        Directory directory = metadata.getFirstDirectoryOfType(type);
        if (directory == null) {
            return null;
        }
        Integer width = directory.getInteger(widthTag);
        Integer height = directory.getInteger(heightTag);
        return width != null && height != null && width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    private static Integer firstInt(Metadata metadata, Class<? extends Directory> first, int firstTag,
            Class<? extends Directory> second, int secondTag) {
        Directory a = metadata.getFirstDirectoryOfType(first);
        Integer value = a == null ? null : a.getInteger(firstTag);
        if (value != null) {
            return value;
        }
        Directory b = metadata.getFirstDirectoryOfType(second);
        return b == null ? null : b.getInteger(secondTag);
    }

    private static Date firstDate(Metadata metadata, Class<? extends Directory> type, int tag) {
        Directory directory = metadata.getFirstDirectoryOfType(type);
        return directory == null ? null : directory.getDate(tag);
    }

    private static Double firstDouble(Metadata metadata, Class<? extends Directory> type, int tag) {
        Directory directory = metadata.getFirstDirectoryOfType(type);
        return directory == null ? null : directory.getDoubleObject(tag);
    }

    private static Integer positiveOrNull(Integer value) {
        return value != null && value > 0 ? value : null;
    }
}
