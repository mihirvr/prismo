package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaDetailsTest {

    /** 14 July 2023 18:30:05 (wall clock as stored) and 2 January 2024 09:15 / 10:00 UTC. */
    private static final long TAKEN = 1_689_359_405_000L;
    private static final long MODIFIED = 1_704_186_900_000L;
    private static final long ADDED = 1_704_189_600_000L;

    @TempDir
    Path tmp;

    private static MediaDetails photo(String make, String model) {
        return new MediaDetails(Path.of("D:", "Photos", "Goa", "IMG_0001.JPG"), "jpg", "photo", 4_404_019, TAKEN,
                MODIFIED, ADDED, 4032, 3024, make, model, 0, true, false, true, false);
    }

    @Test
    void aPhotosLinesInOrder() {
        List<MediaDetails.Line> lines = photo("Apple", "iPhone 15 Pro").lines(ZoneId.of("Asia/Kolkata"));

        assertEquals(List.of(
                new MediaDetails.Line("Type", "JPG photo"),
                new MediaDetails.Line("Size", "4.4 MB (4,404,019 bytes)"),
                new MediaDetails.Line("Dimensions", "4,032 × 3,024 pixels"),
                new MediaDetails.Line("Date taken", "14 July 2023, 18:30"),
                new MediaDetails.Line("Date modified", "2 January 2024, 14:45"),
                new MediaDetails.Line("Added to Prismo", "2 January 2024, 15:30"),
                new MediaDetails.Line("In Prismo", "Favorite · Hidden in File Explorer")), lines);
        MediaDetails details = photo("Apple", "iPhone 15 Pro");
        assertEquals("IMG_0001.JPG", details.name(), "the name and folder head the panel, outside the lines");
        assertEquals(Path.of("D:", "Photos", "Goa").toString(), details.folder());
        assertEquals("Apple iPhone 15 Pro", details.camera(), "the camera has its own section");
    }

    @Test
    void aRawFileSaysItsPictureIsThePreviewInsideIt() {
        MediaDetails raw = new MediaDetails(Path.of("DSC_0001.NEF"), "nef", "raw", 25_000_000, TAKEN, MODIFIED, ADDED,
                6000, 4000, null, null, 0, false, false, false, false);

        assertEquals(List.of("Type", "Size", "Dimensions", "Picture", "Date taken", "Date modified", "Added to Prismo"),
                raw.lines(ZoneOffset.UTC).stream().map(MediaDetails.Line::label).toList());
        assertTrue(raw.isRaw());
        assertTrue(photo("", "").lines(ZoneOffset.UTC).stream().noneMatch(l -> l.label().equals("Picture")),
                "only RAW files get the note");
    }

    @Test
    void dateTakenIsShownAsTheCameraWroteItInAnyTimeZone() {
        String taken = photo("", "").lines(ZoneId.of("America/New_York")).stream()
                .filter(l -> l.label().equals("Date taken")).findFirst().orElseThrow().value();

        assertEquals("14 July 2023, 18:30", taken, "floating wall-clock time: never shifted");
    }

    @Test
    void unknownThingsAreLeftOut() {
        MediaDetails video = new MediaDetails(Path.of("clip.mkv"), "mkv", "video", 900, null, MODIFIED, ADDED, 0, 0,
                null, null, 3723.4, false, false, false, false);

        assertEquals(List.of("Type", "Size", "Date modified", "Added to Prismo"),
                video.lines(ZoneOffset.UTC).stream().map(MediaDetails.Line::label).toList(),
                "a video's length and size are in its own section");
        assertTrue(video.isVideo());
        assertEquals("", new MediaDetails(Path.of("clip.mkv").getFileName(), "mkv", "video", 900, null, MODIFIED,
                ADDED, 0, 0, null, null, 0, false, false, false, false).folder(), "no folder: empty, not \"null\"");
        assertEquals("MKV video", video.kind());
        assertEquals("1:02:03", MediaDetails.length(3723.4));
        assertEquals("0:42", MediaDetails.length(42));
    }

    @Test
    void sizesUseFileExplorersDecimalUnits() {
        assertEquals("0 bytes", MediaDetails.size(0));
        assertEquals("999 bytes", MediaDetails.size(999));
        assertEquals("1.0 KB (1,000 bytes)", MediaDetails.size(1000));
        assertEquals("1.5 GB (1,500,000,000 bytes)", MediaDetails.size(1_500_000_000L));
    }

    @Test
    void theMakeIsNotRepeatedWhenTheModelAlreadyStartsWithIt() {
        assertEquals("Canon EOS R6", photo("Canon", "Canon EOS R6").camera());
        assertEquals("NIKON D750", photo("NIKON CORPORATION", "NIKON D750").camera());
        assertEquals("Apple iPhone 15", photo(" Apple ", "iPhone 15 ").camera());
        assertEquals("", photo(null, null).camera());
        assertEquals("Sony", photo("Sony", null).camera());
    }

    @Test
    void loadReadsTheRowAndAGoneRowIsEmpty() throws Exception {
        try (Database database = Database.open(tmp.resolve("details.db"))) {
            database.write(c -> {
                try (PreparedStatement insert = c.prepareStatement("""
                        INSERT INTO media (id, path, folder, file_name, extension, media_type, size_bytes,
                                           date_modified, date_added, date_taken, width, height, camera_make,
                                           camera_model, duration_seconds, favorite, hidden, os_hidden, trashed_at)
                        VALUES (5, ?, ?, 'v.mp4', 'mp4', 'video', 2048, ?, ?, NULL, 1920, 1080, NULL, NULL, 12.5,
                                0, 1, 0, 99)""")) {
                    insert.setString(1, tmp.resolve("v.mp4").toString());
                    insert.setString(2, tmp.toString());
                    insert.setLong(3, MODIFIED);
                    insert.setLong(4, ADDED);
                    return insert.executeUpdate();
                }
            }).get();

            MediaDetails details = database.read(c -> MediaDetails.load(c, 5)).orElseThrow();

            assertEquals(new MediaDetails(tmp.resolve("v.mp4"), "mp4", "video", 2048, null, MODIFIED, ADDED, 1920,
                    1080, null, null, 12.5, false, true, false, true), details);
            assertEquals("Hidden · In Trash", details.state());
            assertEquals(Optional.empty(), database.read(c -> MediaDetails.load(c, 6)));
        }
    }
}
