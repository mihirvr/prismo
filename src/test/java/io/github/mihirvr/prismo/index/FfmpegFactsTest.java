package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import io.github.mihirvr.prismo.video.VideoNatives;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegFactsTest {

    @TempDir
    Path tmp;

    /** Made with the bundled ffmpeg: types the metadata library can't read, a file that isn't media in between. */
    @Test
    void readsLengthAndSizeOfEachFileAndGoesOnAfterOneItCantOpen() throws Exception {
        Path wmv = clip("a.wmv", "-c:v", "wmv2", "-c:a", "wmav2");
        Path code = Files.writeString(tmp.resolve("b.ts"), "export const x: number = 1;\n");
        Path flv = clip("c.flv", "-c:v", "flv", "-c:a", "libmp3lame", "-ar", "44100");
        Path ts = clip("d.ts", "-c:v", "mpeg2video", "-c:a", "mp2", "-f", "mpegts");
        Path gone = tmp.resolve("e.mpg");
        Path mpg = clip("f.mpg", "-c:v", "mpeg2video", "-c:a", "mp2", "-f", "vob");

        List<FfmpegFacts.Facts> facts = new FfmpegFacts(VideoNatives::ffmpegExecutable)
                .read(List.of(wmv, code, flv, ts, gone, mpg));

        assertEquals(6, facts.size());
        for (int i : new int[] {0, 2, 3, 5}) {
            FfmpegFacts.Facts one = facts.get(i);
            assertTrue(one.seconds() != null && Math.abs(one.seconds() - 2) < 0.2, i + ": " + one);
            assertEquals(320, one.width(), i + ": " + one);
            assertEquals(180, one.height(), i + ": " + one);
        }
        assertEquals(FfmpegFacts.Facts.NONE, facts.get(1), "TypeScript code");
        assertEquals(FfmpegFacts.Facts.NONE, facts.get(4), "missing file");
    }

    /** The indexer's step: what the metadata library read stays, the gaps are filled from ffmpeg. */
    @Test
    void theMetadataReaderFillsInWhatTheLibraryCouldNotRead() throws Exception {
        Path wmv = clip("a.wmv", "-c:v", "wmv2", "-c:a", "wmav2");
        Path mkv = clip("b.mkv", "-c:v", "mpeg4", "-c:a", "mp2", "-metadata", "creation_time=2024-05-07T10:11:12Z");
        Path png = tmp.resolve("c.png");
        io.github.mihirvr.prismo.testing.TestMedia.png(png, 40, 30);
        MetadataReader reader = new MetadataReader(java.time.ZoneOffset.UTC,
                new FfmpegFacts(VideoNatives::ffmpegExecutable));
        List<MediaRecord> read = new ArrayList<>();
        for (Path file : List.of(wmv, mkv, png)) {
            read.add(reader.read(file, MediaType.ofFile(file).orElseThrow(), Files.size(file), 0));
        }
        assertNull(read.get(0).durationSeconds(), "the metadata library doesn't read WMV");
        assertNull(read.get(1).width(), "nor an MKV's size");

        List<MediaRecord> complete = reader.withFfmpegFacts(read);

        assertTrue(Math.abs(complete.get(0).durationSeconds() - 2) < 0.2, complete.get(0).toString());
        assertEquals(320, complete.get(0).width());
        assertEquals(180, complete.get(0).height());
        assertEquals(read.get(1).durationSeconds(), complete.get(1).durationSeconds(), "MKV length from its header");
        assertEquals(320, complete.get(1).width());
        assertEquals(Instant.parse("2024-05-07T10:11:12Z").toEpochMilli(), complete.get(1).dateTaken());
        assertEquals(read.get(2), complete.get(2), "a PNG needs nothing");
        assertEquals(read, new MetadataReader(java.time.ZoneOffset.UTC).withFfmpegFacts(read), "no ffmpeg: as read");
    }

    /** 2.0.0: an audio file gets its length from ffmpeg, and never a size (not even its cover's). */
    @Test
    void anAudioFileGetsItsLengthButNoSize() throws Exception {
        Path cover = tmp.resolve("cover.png");
        io.github.mihirvr.prismo.testing.TestMedia.png(cover, 300, 300);
        Path song = tmp.resolve("song.mp3");
        List<String> command = new ArrayList<>(List.of(VideoNatives.ffmpegExecutable().toString(), "-nostdin",
                "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-i", cover.toString(), "-map", "0:a", "-map", "1", "-c:a", "libmp3lame", "-c:v", "png",
                "-disposition:v", "attached_pic", song.toString()));
        ExternalProgram.run(command, 60);
        MetadataReader reader = new MetadataReader(java.time.ZoneOffset.UTC,
                new FfmpegFacts(VideoNatives::ffmpegExecutable));
        MediaRecord read = reader.read(song, MediaType.AUDIO, Files.size(song), 0);

        MediaRecord complete = reader.withFfmpegFacts(List.of(read)).getFirst();

        assertTrue(Math.abs(complete.durationSeconds() - 3) < 0.2, complete.toString());
        assertNull(complete.width(), "the cover's 300 x 300 isn't the song's size");
        assertNull(complete.height());
        assertNull(complete.dateTaken());
    }

    @Test
    void manyFilesGoInSeveralRuns() throws Exception {
        Path wmv = clip("a.wmv", "-c:v", "wmv2", "-c:a", "wmav2");
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < FfmpegFacts.BATCH + 3; i++) {
            files.add(wmv);
        }

        List<FfmpegFacts.Facts> facts = new FfmpegFacts(VideoNatives::ffmpegExecutable).read(files);

        assertEquals(files.size(), facts.size());
        assertTrue(facts.stream().allMatch(one -> one.width() != null && one.width() == 320), facts.toString());
    }

    @Test
    void noFfmpegMeansNoFacts() {
        List<FfmpegFacts.Facts> facts = new FfmpegFacts(() -> {
            throw new IOException("not unpacked");
        }).read(List.of(tmp.resolve("a.wmv")));

        assertEquals(List.of(FfmpegFacts.Facts.NONE), facts);
    }

    /** ffmpeg 8's own wording (a phone video turned a quarter, an MP3 with a cover, a TS without a length). */
    @Test
    void parsesTurnedPicturesCoversTimesAndFailures() {
        String output = """
                Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'phone.mp4':
                  Metadata:
                    creation_time   : 2024-05-07T10:11:12.000000Z
                  Duration: 00:01:02.50, start: 0.000000, bitrate: 17000 kb/s
                  Stream #0:0[0x1](und): Video: hevc (Main) (hvc1 / 0x31637668), yuv420p(tv, bt709), 1920x1080, 16000 kb/s, 30 fps
                      Side data:
                        displaymatrix: rotation of -90.00 degrees
                  Stream #0:1[0x2](und): Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 256 kb/s
                Input #1, mp3, from 'song.mp3':
                  Duration: 00:03:30.00, start: 0.025057, bitrate: 320 kb/s
                  Stream #1:0: Audio: mp3 (mp3float), 44100 Hz, stereo, fltp, 320 kb/s
                  Stream #1:1: Video: mjpeg (Baseline), yuvj420p(pc, bt470bg/unknown/unknown), 600x600, 90k tbr, 90k tbn (attached pic)
                Input #2, mpegts, from 'tv.ts':
                  Duration: N/A, start: 1.400000, bitrate: N/A
                  Stream #2:0[0x1011]: Video: h264 (High) ([27][0][0][0] / 0x001B), yuv420p(progressive), 1280x720, 25 fps
                [in#3 @ 000001fb718ad640] Error opening input: Invalid data found when processing input
                Error opening input file notes.ts.
                """;

        FfmpegFacts.Run run = FfmpegFacts.parse(output, 5);

        assertEquals(new FfmpegFacts.Facts(62.5, 1080, 1920, Instant.parse("2024-05-07T10:11:12Z")),
                run.facts().get(0), "upright size of a turned phone video");
        assertEquals(new FfmpegFacts.Facts(210.0, null, null, null), run.facts().get(1), "a cover isn't a picture size");
        assertEquals(new FfmpegFacts.Facts(null, 1280, 720, null), run.facts().get(2), "0x001B is not a size");
        assertNull(run.facts().get(3));
        assertEquals(3, run.failedAt());
    }

    private Path clip(String name, String... codec) throws IOException {
        Path file = tmp.resolve(name);
        List<String> command = new ArrayList<>(List.of(VideoNatives.ffmpegExecutable().toString(), "-nostdin",
                "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2"));
        command.addAll(List.of(codec));
        command.add(file.toString());
        String output = ExternalProgram.run(command, 60);
        assertTrue(Files.isRegularFile(file) && Files.size(file) > 0, output);
        return file;
    }
}
