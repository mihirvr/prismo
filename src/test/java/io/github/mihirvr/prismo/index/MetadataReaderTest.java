package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
