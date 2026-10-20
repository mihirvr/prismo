package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drew.lang.Rational;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import io.github.mihirvr.prismo.platform.FileReads;
import io.github.mihirvr.prismo.testing.TestMedia;
import io.github.mihirvr.prismo.ui.MediaDetails;
import io.github.mihirvr.prismo.ui.MediaDetails.Line;
import io.github.mihirvr.prismo.video.Track;
import io.github.mihirvr.prismo.video.VideoPlayer;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the Info panel shows for photos and videos: the sections, the EXIF wording and the video's formats. */
class InfoSectionsTest {

    private static final long MODIFIED = 1_704_186_900_000L;

    @TempDir
    Path tmp;

    private static MediaDetails photo(Path path, String make, String model) {
        return new MediaDetails(path, "jpg", "photo", 2048, null, MODIFIED, MODIFIED, 640, 480, make, model, 0,
                false, false, false, false);
    }

    private static MediaDetails video(int width, int height, double seconds) {
        return new MediaDetails(Path.of("clip.mkv"), "mkv", "video", 9000, null, MODIFIED, MODIFIED, width, height,
                null, null, seconds, false, false, false, false);
    }

    private static List<String> titles(List<InfoSections.Section> sections) {
        return sections.stream().map(InfoSections.Section::title).toList();
    }

    @Test
    void aPhotosCameraSettingsAndGpsAreReadFromTheFile() throws Exception {
        Path file = TestMedia.jpegWithCameraExif(tmp.resolve("camera.jpg"), 64, 48);
        Metadata metadata = FileReads.metadata(file);

        assertEquals(List.of(
                new Line("Lens", "Apple iPhone 15 Pro back camera"),
                new Line("Exposure", "1/250 s"),
                new Line("Aperture", "f/1.8"),
                new Line("ISO", "200"),
                new Line("Focal length", "6.9 mm (24 mm in 35 mm terms)"),
                new Line("Flash", "Did not fire")), InfoSections.photoLines(metadata));
        assertEquals(List.of(new Line("GPS", "18.520430, 73.856743")), InfoSections.location(metadata));

        List<InfoSections.Section> sections = InfoSections.build(photo(file, "Apple", "iPhone 15 Pro"),
                ZoneOffset.UTC, metadata, null);
        assertEquals(List.of("Details", "Camera", "Location", "Path"), titles(sections));
        assertEquals(new Line("Camera", "Apple iPhone 15 Pro"), sections.get(1).lines().getFirst(),
                "the camera from the index first, then what the file says");
        assertEquals(List.of(new Line("", file.toString())), sections.getLast().lines(), "the full path");
    }

    @Test
    void missingValuesAndEmptySectionsAreLeftOut() {
        List<InfoSections.Section> sections = InfoSections.build(photo(Path.of("D:", "a.jpg"), null, null),
                ZoneOffset.UTC, null, null);
        assertEquals(List.of("Details", "Path"), titles(sections), "no camera, no EXIF, no GPS");

        Metadata metadata = new Metadata();
        ExifSubIFDDirectory exif = new ExifSubIFDDirectory();
        exif.setRational(ExifDirectoryBase.TAG_FNUMBER, new Rational(0, 0));    // as some cameras write "unknown"
        exif.setRational(ExifDirectoryBase.TAG_EXPOSURE_TIME, new Rational(0, 1));
        exif.setInt(ExifDirectoryBase.TAG_ISO_EQUIVALENT, 0);
        metadata.addDirectory(exif);
        GpsDirectory gps = new GpsDirectory();
        gps.setString(GpsDirectory.TAG_LATITUDE_REF, "N");
        gps.setRationalArray(GpsDirectory.TAG_LATITUDE, new Rational[] {new Rational(0, 1), new Rational(0, 1),
                new Rational(0, 1)});
        gps.setString(GpsDirectory.TAG_LONGITUDE_REF, "E");
        gps.setRationalArray(GpsDirectory.TAG_LONGITUDE, new Rational[] {new Rational(0, 1), new Rational(0, 1),
                new Rational(0, 1)});
        metadata.addDirectory(gps);
        assertEquals(List.of(), InfoSections.photoLines(metadata), "0/0, zero exposure and ISO 0 mean unknown");
        assertEquals(List.of(), InfoSections.location(metadata), "0, 0 is a phone with no fix, not a place");
    }

    @Test
    void aVideosFormatsAndTracksComeFromThePlayer() {
        VideoPlayer.Info info = new VideoPlayer.Info(83_000_000, 1920, 1080, 90, true, true, 29.97,
                List.of(new Track(1, "eng", "", true, false), new Track(2, "hin", "Commentary", false, false)),
                List.of(new Track(3, "eng", "", false, true), new Track(4, "fre", "", false, false)), 1, "h264",
                "aac");

        List<InfoSections.Section> sections = InfoSections.build(video(1920, 1080, 83), ZoneOffset.UTC, null, info);

        assertEquals(List.of("Details", "Video", "Path"), titles(sections));
        assertTrue(sections.getFirst().lines().stream().noneMatch(l -> l.label().equals("Dimensions")),
                "a video's size is its Resolution, in the Video section only");
        assertEquals(List.of(
                new Line("Length", "1:23"),
                new Line("Resolution", "1,080 × 1,920 pixels"),
                new Line("Frame rate", "29.97 fps"),
                new Line("Video format", "H.264 (AVC)"),
                new Line("Sound format", "AAC"),
                new Line("Sound tracks", "English\nCommentary · Hindi"),
                new Line("Subtitle tracks", "English\nFrench (picture subtitles: can't be shown)")),
                sections.get(1).lines(), "a phone's sideways video is shown upright: 1080 wide");
    }

    @Test
    void beforeThePlayerHasOpenedAVideoTheIndexsLengthAndSizeShow() {
        assertEquals(List.of(new Line("Length", "0:42"), new Line("Resolution", "640 × 360 pixels")),
                InfoSections.videoLines(video(640, 360, 42), null));
        assertEquals(List.of(), InfoSections.videoLines(video(0, 0, 0), null), "nothing known: nothing shown");

        VideoPlayer.Info silent = new VideoPlayer.Info(5_000_000, 320, 240, 0, true, false, 25, List.of(), List.of(),
                -1, "mpeg4", "");
        assertEquals(List.of(new Line("Length", "0:05"), new Line("Resolution", "320 × 240 pixels"),
                new Line("Frame rate", "25 fps"), new Line("Video format", "MPEG-4 Part 2")),
                InfoSections.videoLines(video(0, 0, 0), silent), "no sound: no sound lines");
    }

    @Test
    void wording() {
        assertEquals("1/250 s", InfoSections.exposure(0.004));
        assertEquals("1/2 s", InfoSections.exposure(0.5));
        assertEquals("2 s", InfoSections.exposure(2));
        assertEquals("1.5 s", InfoSections.exposure(1.5));
        assertEquals("Fired", InfoSections.flash(0x19));
        assertEquals("Did not fire", InfoSections.flash(0x10));
        assertEquals("No flash", InfoSections.flash(0x20));
        assertEquals("H.265 (HEVC)", InfoSections.format("hevc"));
        assertEquals("PCM", InfoSections.format("pcm_s16le"));
        assertEquals("WMV3", InfoSections.format("wmv3"), "unknown names in capitals");
        assertEquals("Canon EF 50mm", InfoSections.lens("Canon", "Canon EF 50mm"), "the make isn't repeated");
        assertEquals("Sony FE 85mm", InfoSections.lens("Sony", "FE 85mm"));
        assertEquals("", InfoSections.lens("Sony", null));
        assertEquals("23.976", InfoSections.number(23.976, 3));
        assertEquals("25", InfoSections.number(25, 3));
        assertEquals("10", InfoSections.number(10, 1));
        assertTrue(InfoSections.build(video(0, 0, 0), ZoneOffset.UTC, null, null).stream()
                .noneMatch(s -> s.title().equals("Video")), "a video with nothing known has no Video section");
    }

    @Test
    void onlyAValueAboveZeroCountsAsKnown() {
        assertEquals(0.004, InfoSections.positive(new Rational(1, 250)), 1e-12);
        assertEquals(0, InfoSections.positive(new Rational(-1, 250)), "a negative value (a broken tag) isn't shown");
        assertEquals(0, InfoSections.positive(new Rational(0, 1)));
        assertEquals(0, InfoSections.positive(new Rational(0, 0)), "0/0 means not recorded");
        assertEquals(0, InfoSections.positive(null));
    }
}
