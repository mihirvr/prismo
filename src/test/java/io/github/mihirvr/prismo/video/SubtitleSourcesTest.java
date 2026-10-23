package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mihirvr.prismo.testing.TestMedia;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class SubtitleSourcesTest {

    @TempDir
    Path dir;

    @Test
    void findsTheSrtFilesNamedLikeTheVideo() throws IOException {
        Path video = Files.createFile(dir.resolve("Holiday.mp4"));
        for (String name : List.of("Holiday.srt", "holiday.en.srt", "Holiday.Hindi.srt", "Other.srt", "Holiday.txt",
                "Holidays.srt")) {
            Files.writeString(dir.resolve(name), TestMedia.SUBTITLES_SRT);
        }

        List<SubtitleSources.Source> found = SubtitleSources.forVideo(video, List.of());

        assertEquals(List.of("English (file)", "Hindi (file)", "Holiday.srt"),
                found.stream().map(SubtitleSources.Source::label).toList());
    }

    @Test
    void eachChoiceKnowsItsLanguageWhenTheNameOrTagSaysIt() throws IOException {
        Path video = Files.createFile(dir.resolve("Trip.mkv"));
        for (String name : List.of("Trip.srt", "Trip.en.forced.srt", "Trip.Hindi.srt")) {
            Files.writeString(dir.resolve(name), TestMedia.SUBTITLES_SRT);
        }

        List<SubtitleSources.Source> found = SubtitleSources.forVideo(video, List.of(
                new Track(2, "fre", "", false, true), new Track(3, "und", "", false, true)));
        List<SubtitleSources.Source> withPicked = SubtitleSources.withChosenFile(found,
                dir.resolve("elsewhere").resolve("Trip.de.srt"));

        assertEquals(java.util.Arrays.asList("English", "Hindi", null, "French", null, "German"),
                withPicked.stream().map(SubtitleSources.Source::language).toList(),
                "en.forced, Hindi, a plain Trip.srt, the tracks' tags, a picked file's name");
    }

    @Test
    void listsTheVideosOwnTracksAfterTheFilesAndPictureOnesAsNotShowable() throws IOException {
        Path video = Files.createFile(dir.resolve("film.mkv"));
        List<SubtitleSources.Source> found = SubtitleSources.forVideo(video, List.of(
                new Track(3, "eng", "", false, true), new Track(4, "fre", "", false, false)));

        assertEquals("English", found.get(0).label());
        assertEquals(3, found.get(0).stream());
        assertEquals("French (picture subtitles, can't be shown)", found.get(1).label());
        assertFalse(found.get(1).supported());
        assertThrows(IOException.class, () -> SubtitleSources.load(found.get(1), video, VideoNatives::ffmpegExecutable));
    }

    @Test
    void loadsAFileNextToTheVideo() throws IOException {
        Path video = Files.createFile(dir.resolve("clip.mp4"));
        Files.writeString(dir.resolve("clip.srt"), TestMedia.SUBTITLES_SRT);
        SubtitleSources.Source file = SubtitleSources.forVideo(video, List.of()).getFirst();

        Subtitles subtitles = SubtitleSources.load(file, video, () -> {
            throw new AssertionError("a file needs no ffmpeg");
        });

        assertEquals("Hello world", subtitles.at(1_000_000));
    }

    @Test
    void aChosenFileFromAnotherFolderIsAddedOnceAndLoads() throws IOException {
        Path video = Files.createFile(dir.resolve("Holiday.mp4"));
        Files.writeString(dir.resolve("Holiday.srt"), TestMedia.SUBTITLES_SRT);
        Path elsewhere = Files.createDirectories(dir.resolve("downloads")).resolve("some other name.srt");
        Files.writeString(elsewhere, TestMedia.SUBTITLES_SRT);
        List<SubtitleSources.Source> next = SubtitleSources.forVideo(video, List.of());

        List<SubtitleSources.Source> chosen = SubtitleSources.withChosenFile(next, elsewhere);

        assertEquals(List.of("Holiday.srt", "some other name.srt"),
                chosen.stream().map(SubtitleSources.Source::label).toList());
        assertEquals("Hello world", SubtitleSources.load(chosen.getLast(), video, () -> {
            throw new AssertionError("a file needs no ffmpeg");
        }).at(1_000_000));
        // Picking the same file again (also as a different spelling of the path) adds nothing.
        assertEquals(chosen, SubtitleSources.withChosenFile(chosen, dir.resolve("downloads/../downloads")
                .resolve("some other name.srt")));
        // The file already found next to the video isn't added twice either.
        assertEquals(chosen, SubtitleSources.withChosenFile(chosen, dir.resolve("Holiday.srt")));
    }

    @Test
    void loadsATrackInsideTheVideoWithFfmpeg() throws Exception {
        Path video = TestMedia.withTracks(dir.resolve("tracks.mkv"));
        // Stream 3: picture 0, sound 1 and 2, subtitles 3 (see TestMedia.withTracks).
        SubtitleSources.Source track = SubtitleSources.forVideo(video, List.of(new Track(3, "eng", "", false, true)))
                .getFirst();

        Subtitles subtitles = SubtitleSources.load(track, video, VideoNatives::ffmpegExecutable);

        assertEquals(2, subtitles.cues().size());
        assertEquals("Hello world", subtitles.at(600_000));
        assertEquals("Second line", subtitles.at(2_500_000));
        try (var left = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertFalse(left.anyMatch(p -> p.getFileName().toString().startsWith("prismo-subtitles-")
                    && p.toFile().lastModified() > System.currentTimeMillis() - 5_000), "the temp file is deleted");
        }
    }
}
