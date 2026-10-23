package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.video.TrackChoice.Kind;
import io.github.mihirvr.prismo.video.TrackChoice.SubtitlePick;
import io.github.mihirvr.prismo.video.TrackChoice.SubtitleStart;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which sound track and subtitles a video starts with (P8): pure rules, nothing is read from disk here. */
class TrackChoiceTest {

    @TempDir
    Path dir;

    private static Track sound(int stream, String language, boolean isDefault) {
        return new Track(stream, language, "", isDefault, false);
    }

    private static final List<Track> SOUND = List.of(sound(1, "eng", true), sound(2, "hin", false),
            sound(3, "hin", false), sound(4, "", false));

    // --- Sound ---

    @Test
    void theSoundTrackPickedForThisVideoWinsWhileTheFileHasIt() {
        assertEquals(4, TrackChoice.audioStream(SOUND, 1, 4, "Hindi"), "picked for this video beats the language");
        assertEquals(2, TrackChoice.audioStream(SOUND, 1, 9, "Hindi"), "stream 9 is gone: the language rule");
        assertEquals(1, TrackChoice.audioStream(SOUND, 1, 9, null), "gone and no language: the file's default");
    }

    @Test
    void otherwiseTheLanguagePickedLastThenTheFilesDefault() {
        assertEquals(2, TrackChoice.audioStream(SOUND, 1, -1, "Hindi"), "the first Hindi track");
        assertEquals(2, TrackChoice.audioStream(SOUND, 1, -1, "hindi"), "any case");
        assertEquals(1, TrackChoice.audioStream(SOUND, 1, -1, "French"), "no French track: the default");
        assertEquals(1, TrackChoice.audioStream(SOUND, 1, -1, null), "nothing picked ever: as before P8");
        List<Track> twoEnglish = List.of(sound(1, "eng", false), sound(5, "eng", true));
        assertEquals(5, TrackChoice.audioStream(twoEnglish, 5, -1, "English"),
                "several in the language: the one the file marks default");
        assertEquals(-1, TrackChoice.audioStream(List.of(), -1, 3, "English"), "no sound at all");
    }

    // --- Subtitles ---

    private Path video() {
        return dir.resolve("Holiday.mkv");
    }

    /** A file next to the video in English, an unknown-language file, a Hindi track, a picture track (French). */
    private List<SubtitleSources.Source> sources() {
        return List.of(
                new SubtitleSources.Source("English (file)", dir.resolve("Holiday.en.srt"), -1, true, "English"),
                new SubtitleSources.Source("Holiday.srt", dir.resolve("Holiday.srt"), -1, true, null),
                new SubtitleSources.Source("Hindi", null, 3, true, "Hindi"),
                new SubtitleSources.Source("French (picture …)", null, 4, false, "French"));
    }

    private SubtitleStart start(SubtitlePick remembered, String language, Set<Path> existing) {
        return TrackChoice.subtitles(video(), sources(), remembered, language, existing::contains);
    }

    @Test
    void subtitlesPickedForThisVideoWinWhileTheyStillExist() {
        assertEquals(2, start(SubtitlePick.track(3), "English", Set.of()).choice(), "the Hindi track picked here");
        assertEquals(-1, start(SubtitlePick.SUBTITLES_OFF, "English", Set.of()).choice(), "Off picked here");
        SubtitlePick file = SubtitlePick.file(video(), dir.resolve("Holiday.srt"));
        assertEquals(1, start(file, "Hindi", Set.of(dir.resolve("Holiday.srt"))).choice());
    }

    @Test
    void aGoneTrackOrFileFallsBackQuietlyToTheLanguageRule() {
        assertEquals(2, start(SubtitlePick.track(9), "Hindi", Set.of()).choice(), "no stream 9 any more");
        assertEquals(0, start(SubtitlePick.track(4), "English", Set.of()).choice(),
                "a picture track can't be shown: the language rule");
        SubtitlePick gone = SubtitlePick.file(video(), dir.resolve("deleted.srt"));
        assertEquals(2, start(gone, "Hindi", Set.of()).choice(), "the file is gone");
        assertEquals(0, start(gone, null, Set.of()).choice(), "gone and no language: as before P8 (a file next to it)");
    }

    @Test
    void aRememberedFileFromElsewhereIsAddedToTheChoices() {
        Path elsewhere = dir.resolve("subs").resolve("Holiday.Hindi.srt");
        SubtitlePick pick = SubtitlePick.file(video(), elsewhere);

        SubtitleStart start = start(pick, null, Set.of(elsewhere.toAbsolutePath().normalize()));

        assertEquals(4, start.choice(), "added after the four choices");
        assertEquals(elsewhere.toAbsolutePath().normalize(), start.sources().get(4).file());
        assertEquals("Hindi", start.sources().get(4).language(), "its language from its name");
    }

    @Test
    void withNothingPickedTheLanguagePickedLastThenAFileNextToTheVideo() {
        assertEquals(2, start(null, "Hindi", Set.of()).choice());
        assertEquals(0, start(null, "English", Set.of()).choice(), "files come first, as in the menu");
        assertEquals(-1, start(null, TrackChoice.OFF, Set.of()).choice(), "Off picked last: stays off");
        assertEquals(0, start(null, "French", Set.of()).choice(), "only a picture track in French: the default");
        assertEquals(0, start(null, null, Set.of()).choice(), "never picked: a file next to it is on (as before)");
        List<SubtitleSources.Source> tracksOnly = sources().subList(2, 4);
        assertEquals(-1, TrackChoice.subtitles(video(), tracksOnly, null, null, _ -> false).choice(),
                "only tracks inside: off until picked (as before)");
        assertEquals(-1, TrackChoice.subtitles(video(), List.of(), null, "English", _ -> false).choice());
    }

    @Test
    void theLanguageSettingFollowsWhatTheUserPicks() {
        List<SubtitleSources.Source> all = sources();
        assertEquals(TrackChoice.OFF, TrackChoice.subtitleLanguageAfter(null, "English"));
        assertEquals("Hindi", TrackChoice.subtitleLanguageAfter(all.get(2), "English"));
        assertEquals("English", TrackChoice.subtitleLanguageAfter(all.get(1), "English"),
                "a file that doesn't say its language leaves the setting as it was");
        assertEquals("Hindi", TrackChoice.audioLanguageAfter(sound(2, "hin", false), "English"));
        assertEquals("English", TrackChoice.audioLanguageAfter(sound(4, "und", false), "English"));
        assertNull(TrackChoice.audioLanguageAfter(sound(4, "", false), null));
    }

    // --- What is saved ---

    @Test
    void aFileNextToTheVideoIsSavedByNameSoItFollowsAMove() {
        SubtitlePick next = SubtitlePick.file(video(), dir.resolve("Holiday.en.srt"));
        assertEquals("Holiday.en.srt", next.file());
        Path moved = dir.resolve("moved").resolve("Holiday.mkv");
        assertEquals(dir.resolve("moved").resolve("Holiday.en.srt").toAbsolutePath().normalize(), next.fileFor(moved));

        Path elsewhere = dir.resolve("subs").resolve("x.srt");
        SubtitlePick far = SubtitlePick.file(video(), elsewhere);
        assertTrue(Path.of(far.file()).isAbsolute(), "a file from another folder keeps its full path");
        assertEquals(elsewhere.toAbsolutePath().normalize(), far.fileFor(moved));
    }

    @Test
    void picksAreCompleteAndTheirWordsRoundTrip() {
        for (Kind kind : Kind.values()) {
            assertEquals(kind, Kind.fromWord(kind.word()));
        }
        assertNull(Kind.fromWord("maybe"));
        assertNull(Kind.fromWord(null));
        assertThrows(IllegalArgumentException.class, () -> new SubtitlePick(Kind.TRACK, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new SubtitlePick(Kind.FILE, -1, " "));
        TrackChoice.Picked picked = TrackChoice.Picked.NOTHING.withAudio(2).withDelay(300_000);
        assertEquals(new TrackChoice.Picked(2, null, 300_000), picked);
        assertTrue(TrackChoice.Picked.NOTHING.isEmpty());
    }
}
