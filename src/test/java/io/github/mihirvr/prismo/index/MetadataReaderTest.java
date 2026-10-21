package io.github.mihirvr.prismo.index;

import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import io.github.mihirvr.prismo.testing.TestMedia;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetadataReaderTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @TempDir
    Path tmp;

    private final MetadataReader reader = new MetadataReader(INDIA);

    @Test
    void readsExifDateCameraAndRotatedSize() throws Exception {
        // Orientation 6 = stored landscape, displayed portrait: width and height must swap.
        Path file = TestMedia.jpegWithExif(tmp.resolve("IMG_0001.JPG"), 40, 30, "Canon", "EOS R6", 6,
                "2023:07:14 23:30:05");

        MediaRecord record = read(file, MediaType.PHOTO);

        assertEquals(floating("2023-07-14T23:30:05"), record.dateTaken(), "wall-clock time kept as-is");
        assertEquals("Canon", record.cameraMake());
        assertEquals("EOS R6", record.cameraModel());
        assertEquals(30, record.width());
        assertEquals(40, record.height());
        assertEquals("jpg", record.extension());
        assertEquals(tmp.toString(), record.folder());
        assertNull(record.durationSeconds());
    }

    @Test
    void unrotatedPhotoKeepsItsSize() throws Exception {
        Path file = TestMedia.jpegWithExif(tmp.resolve("a.jpg"), 40, 30, "Sony", "A7", 1, "2020:01:01 00:00:00");

        MediaRecord record = read(file, MediaType.PHOTO);

        assertEquals(40, record.width());
        assertEquals(30, record.height());
    }

    @Test
    void pngWithoutExifHasSizeButNoDate() throws Exception {
        MediaRecord record = read(TestMedia.png(tmp.resolve("shot.png"), 64, 48), MediaType.PHOTO);

        assertEquals(64, record.width());
        assertEquals(48, record.height());
        assertNull(record.dateTaken());
        assertNull(record.cameraMake());
    }

    @Test
    void videoCreationTimeIsConvertedToLocalWallClock() throws Exception {
        // 18:00 UTC is 23:30 in India; the Timeline should show 23:30.
        Path file = TestMedia.mp4(tmp.resolve("clip.mp4"), Instant.parse("2024-01-15T18:00:00Z"), 12.5);

        MediaRecord record = read(file, MediaType.VIDEO);

        assertEquals(floating("2024-01-15T23:30:00"), record.dateTaken());
        assertEquals(12.5, record.durationSeconds(), 0.001);
    }

    @Test
    void corruptFileIsIndexedWithoutMetadata() throws Exception {
        Path file = Files.write(tmp.resolve("broken.jpg"), new byte[] {1, 2, 3, 4, 5});

        MediaRecord record = read(file, MediaType.PHOTO);

        assertEquals(5, record.sizeBytes());
        assertNull(record.dateTaken());
        assertNull(record.width());
    }

    @Test
    void rawWithSeveralExifSubIfdsGetsTheDateAndSizeFromTheRightOne() {
        // Like a Sony ARW / Nikon NEF (measured in P7): the first SubIFD is the raw sensor data, a later one has the
        // photo's own tags. Orientation 8 (stored sideways): width and height swap.
        Metadata metadata = new Metadata();
        ExifIFD0Directory ifd0 = new ExifIFD0Directory();
        ifd0.setString(ExifDirectoryBase.TAG_MAKE, "SONY");
        ifd0.setInt(ExifDirectoryBase.TAG_ORIENTATION, 8);
        metadata.addDirectory(ifd0);
        ExifSubIFDDirectory sensor = new ExifSubIFDDirectory();
        sensor.setInt(ExifDirectoryBase.TAG_IMAGE_WIDTH, 4928);
        sensor.setInt(ExifDirectoryBase.TAG_IMAGE_HEIGHT, 3708);
        metadata.addDirectory(sensor);
        ExifSubIFDDirectory photo = new ExifSubIFDDirectory();
        photo.setString(ExifDirectoryBase.TAG_DATETIME_ORIGINAL, "2021:09:25 09:28:10");
        photo.setInt(ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH, 4896);
        photo.setInt(ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT, 2752);
        metadata.addDirectory(photo);

        MediaRecord record = reader.read(tmp.resolve("DSC00018.ARW"), MediaType.RAW, 1, 0, metadata);

        assertEquals(floating("2021-09-25T09:28:10"), record.dateTaken());
        assertEquals(2752, record.width());
        assertEquals(4896, record.height());
        assertEquals(photo, MetadataReader.photoExif(metadata));
    }

    @Test
    void rawWithoutAnExifImageSizeTakesTheLargestSubIfdImage() {
        // Like a Nikon 1 NEF: a small preview SubIFD and the raw image's, no EXIF image size.
        Metadata metadata = new Metadata();
        ExifSubIFDDirectory preview = new ExifSubIFDDirectory();
        preview.setInt(ExifDirectoryBase.TAG_IMAGE_WIDTH, 640);
        preview.setInt(ExifDirectoryBase.TAG_IMAGE_HEIGHT, 424);
        metadata.addDirectory(preview);
        ExifSubIFDDirectory sensor = new ExifSubIFDDirectory();
        sensor.setInt(ExifDirectoryBase.TAG_IMAGE_WIDTH, 4620);
        sensor.setInt(ExifDirectoryBase.TAG_IMAGE_HEIGHT, 3084);
        metadata.addDirectory(sensor);

        MediaRecord record = reader.read(tmp.resolve("DSC0521.NEF"), MediaType.RAW, 1, 0, metadata);

        assertEquals(4620, record.width());
        assertEquals(3084, record.height());
        assertNull(record.dateTaken());
        assertEquals(preview, MetadataReader.photoExif(metadata), "none has photo tags: the first");

        // An Olympus ORF keeps the raw image's size in IFD0 (and a NEF a 160 x 120 thumbnail there).
        Metadata orf = new Metadata();
        ExifIFD0Directory ifd0 = new ExifIFD0Directory();
        ifd0.setInt(ExifDirectoryBase.TAG_IMAGE_WIDTH, 3088);
        ifd0.setInt(ExifDirectoryBase.TAG_IMAGE_HEIGHT, 2309);
        orf.addDirectory(ifd0);
        orf.addDirectory(new ExifSubIFDDirectory());
        MediaRecord olympus = reader.read(tmp.resolve("PA030017.ORF"), MediaType.RAW, 1, 0, orf);
        assertEquals(3088, olympus.width());
        assertEquals(2309, olympus.height());
    }

    @Test
    void exifDateParsing() {
        assertEquals(floating("2023-07-14T18:30:05"), MetadataReader.parseExifDate("2023:07:14 18:30:05"));
        assertEquals(floating("2023-07-14T18:30:05"), MetadataReader.parseExifDate(" 2023:07:14 18:30:05\0 "));
        assertNull(MetadataReader.parseExifDate("0000:00:00 00:00:00"));
        assertNull(MetadataReader.parseExifDate("    :  :     :  :  "));
        assertNull(MetadataReader.parseExifDate(null));
    }

    private MediaRecord read(Path file, MediaType type) throws Exception {
        return reader.read(file, type, Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }

    private static long floating(String localDateTime) {
        return LocalDateTime.parse(localDateTime).toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
