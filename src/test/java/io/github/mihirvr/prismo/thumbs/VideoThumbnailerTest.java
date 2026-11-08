package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import io.github.mihirvr.prismo.testing.TestMedia;
import io.github.mihirvr.prismo.video.VideoNatives;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class VideoThumbnailerTest {

    private static final int FPS = 25;

    @TempDir
    Path tmp;

    private final VideoThumbnailer thumbnailer = new VideoThumbnailer(VideoNatives::ffmpegExecutable, 320);

    @Test
    void takesTheFrameAtOneSecondScaledToTheLongEdge() throws Exception {
        Path video = TestMedia.video(tmp.resolve("clip.mp4"), 640, 480, 2 * FPS, FPS, true, 0);

        BufferedImage thumb = generate(video);

        assertEquals(320, thumb.getWidth());
        assertEquals(240, thumb.getHeight());
        assertEquals(TestMedia.frameRed(FPS), centreRed(thumb), 15, "the frame at 1 s, not the first one");
    }

    @Test
    void aVideoShorterThanOneSecondGetsItsFirstFrame() throws Exception {
        Path video = TestMedia.video(tmp.resolve("short.mp4"), 320, 240, 10, FPS, false, 0);

        BufferedImage thumb = generate(video);

        assertEquals(TestMedia.frameRed(0), centreRed(thumb), 15);
    }

    @Test
    void smallVideosAreNotEnlarged() throws Exception {
        Path video = TestMedia.video(tmp.resolve("small.mkv"), 160, 120, 5, FPS, false, 0);

        BufferedImage thumb = generate(video);

        assertEquals(160, thumb.getWidth());
        assertEquals(120, thumb.getHeight());
    }

    @Test
    void phoneVideosAreTurnedUpright() throws Exception {
        // Stored 320×240 with "turn 90° clockwise" in its tag: the white corner, top-left in the stored
        // picture, ends up top-right once turned.
        Path video = TestMedia.video(tmp.resolve("turned.mov"), 320, 240, 5, FPS, false, 90);

        BufferedImage thumb = generate(video);

        assertEquals(240, thumb.getWidth());
        assertEquals(320, thumb.getHeight());
        assertTrue(isWhite(thumb.getRGB(thumb.getWidth() - 10, 10)), "white corner top-right");
        assertTrue(!isWhite(thumb.getRGB(10, 10)), "not top-left");
    }

    @Test
    void aBrokenFileFails() throws Exception {
        Path broken = Files.write(tmp.resolve("broken.mp4"), new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 1, 2, 3});

        assertThrows(IOException.class, () -> thumbnailer.generate(broken, Files.createFile(tmp.resolve("out.tmp"))));
    }

    @Test
    void aMissingFfmpegFails() throws Exception {
        Path video = TestMedia.video(tmp.resolve("clip.mp4"), 160, 120, 5, FPS, false, 0);
        VideoThumbnailer noProgram = new VideoThumbnailer(() -> tmp.resolve("no-ffmpeg.exe"), 320);

        assertThrows(IOException.class, () -> noProgram.generate(video, Files.createFile(tmp.resolve("out.tmp"))));
    }

    @Test
    void seeksBeforeOpeningTheInput() {
        List<String> command = thumbnailer.frameCommand(Path.of("ffmpeg"), Path.of("in.mp4"), Path.of("out.tmp"), 2.5,
                true);

        assertTrue(command.indexOf("-ss") >= 0 && command.indexOf("-ss") < command.indexOf("-i"), command.toString());
        assertEquals("2.500", command.get(command.indexOf("-ss") + 1));
        assertEquals("0:V:0", command.get(command.indexOf("-map") + 1), "the real video, never the cover");
        int skip = command.indexOf("-skip_frame");
        assertTrue(skip >= 0 && skip < command.indexOf("-i"), "key frames only, an input option: " + command);
        assertEquals("nokey", command.get(skip + 1));
        assertFalse(thumbnailer.frameCommand(Path.of("ffmpeg"), Path.of("in.mp4"), Path.of("out.tmp"), 2.5, false)
                .contains("-skip_frame"), "the exact frame: every frame decoded");
        assertEquals("out.tmp", command.getLast());
        assertFalse(thumbnailer.frameCommand(Path.of("ffmpeg"), Path.of("in.mp4"), Path.of("out.tmp"), 0, true)
                .contains("-ss"), "0 = the first frame");
    }

    @Test
    void theFirstTryIsAtATenthOfTheLengthBetweenOneSecondAndOneMinute() {
        assertEquals(1, VideoThumbnailer.firstFrameAt(0), "unknown length: 1 s, as before 1.1.0");
        assertEquals(0, VideoThumbnailer.firstFrameAt(0.6), "shorter than 1 s: the first frame");
        assertEquals(1, VideoThumbnailer.firstFrameAt(5));
        assertEquals(4, VideoThumbnailer.firstFrameAt(40), 1e-9);
        assertEquals(60, VideoThumbnailer.firstFrameAt(2 * 3600), "a film: at most a minute in");
    }

    @Test
    void readsTheLengthFromFfmpegsListing() {
        // As the bundled ffmpeg 8.1 prints them (an MP4 with cover art; an MKV with cover.jpg attached: both ignored).
        String mp4 = """
                  Duration: 00:01:40.50, start: 0.000000, bitrate: 593 kb/s
                  Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(progressive), 640x360
                  Stream #0:1[0x0]: Video: mjpeg (Baseline), yuvj420p(pc), 600x600 [SAR 1:1 DAR 1:1], 90k tbn (attached pic)
                """;
        assertEquals(new VideoThumbnailer.Facts(100.5), VideoThumbnailer.Facts.parse(mp4));
        String mkv = """
                  Duration: 01:02:03.00, start: 0.000000, bitrate: 9 kb/s
                  Stream #0:0: Video: h264 (High), yuv420p(tv, progressive), 640x360 [SAR 1:1 DAR 16:9], 25 fps
                  Stream #0:1: Audio: aac (LC), 48000 Hz, stereo, fltp (default)
                  Stream #0:2: Video: mjpeg (Baseline), yuvj420p(pc), 500x500, 90k tbn (attached pic)
                      filename        : cover.jpg
                """;
        assertEquals(new VideoThumbnailer.Facts(3723), VideoThumbnailer.Facts.parse(mkv));
        assertEquals(new VideoThumbnailer.Facts(0), VideoThumbnailer.Facts.parse("  Duration: N/A, bitrate: N/A"));
    }

    @Test
    void blackWhiteAndFlatFramesAreBadAndAGoodFrameWins() {
        VideoThumbnailer.Look black = VideoThumbnailer.Look.of(filled(0x000000));
        VideoThumbnailer.Look white = VideoThumbnailer.Look.of(filled(0xFFFFFF));
        VideoThumbnailer.Look grey = VideoThumbnailer.Look.of(filled(0x808080));
        BufferedImage bars = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 36; y++) {
                bars.setRGB(x, y, x < 32 ? 0x202020 : 0xE0E0E0);
            }
        }
        VideoThumbnailer.Look good = VideoThumbnailer.Look.of(bars);

        assertFalse(black.good());
        assertFalse(white.good());
        assertFalse(grey.good(), "one flat colour");
        assertTrue(good.good(), good.toString());
        assertTrue(good.betterThan(grey));
        assertFalse(black.betterThan(good));
        assertTrue(grey.betterThan(black) || black.betterThan(grey) || grey.contrast() == black.contrast());
    }

    @Test
    void aVideoThatStartsDarkGetsALaterFrame() throws Exception {
        // 12 s black, then 8 s of test pattern: 10 %, 25 % and 50 % are black, 75 % (15 s) is the pattern.
        Path video = tmp.resolve("dark start.mp4");
        ffmpeg("-f", "lavfi", "-i", "color=black:size=320x180:rate=10:duration=12", "-f", "lavfi", "-i",
                "testsrc2=size=320x180:rate=10:duration=8", "-filter_complex", "[0][1]concat=n=2:v=1[v]",
                "-map", "[v]", "-c:v", "mpeg4", "-g", "10", video.toString());

        BufferedImage thumb = generate(video);

        assertTrue(VideoThumbnailer.Look.of(thumb).good(), VideoThumbnailer.Look.of(thumb).toString());
        try (var left = Files.list(tmp)) {
            assertEquals(List.of(), left.filter(p -> p.toString().endsWith(".jpg")).toList(), "no tries left behind");
        }
    }

    @Test
    void aVideosOwnCoverPictureIsNeverUsed() throws Exception {
        // 1.2.0: downloaded series carry the encoder group's logo as their cover; the thumbnail is always a frame.
        Path cover = tmp.resolve("cover.jpg");
        ffmpeg("-f", "lavfi", "-i", "smptebars=size=200x200", "-frames:v", "1", cover.toString());
        Path video = tmp.resolve("with cover.mp4");
        ffmpeg("-f", "lavfi", "-i", "color=black:size=320x180:rate=10:duration=6", "-i", cover.toString(),
                "-map", "0", "-map", "1", "-c:v:0", "mpeg4", "-c:v:1", "mjpeg", "-disposition:v:1", "attached_pic",
                video.toString());

        BufferedImage thumb = generate(video);

        assertEquals(320, thumb.getWidth(), "a 16:9 frame, not the square cover");
        assertEquals(180, thumb.getHeight());
    }

    private static void ffmpeg(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(VideoNatives.ffmpegExecutable().toString(), "-nostdin",
                "-hide_banner", "-loglevel", "error", "-y"));
        command.addAll(List.of(arguments));
        String output = ExternalProgram.run(command, 60);
        assertTrue(Files.size(Path.of(arguments[arguments.length - 1])) > 0, output);
    }

    private static BufferedImage filled(int rgb) {
        BufferedImage image = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 36; y++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private BufferedImage generate(Path video) throws IOException {
        Path target = Files.createFile(tmp.resolve(video.getFileName() + ".tmp")); // like ThumbnailService's temp file
        thumbnailer.generate(video, target);
        return ImageIO.read(target.toFile());
    }

    private static int centreRed(BufferedImage image) {
        return image.getRGB(image.getWidth() / 2, image.getHeight() / 2) >> 16 & 0xFF;
    }

    private static boolean isWhite(int rgb) {
        return (rgb >> 16 & 0xFF) > 220 && (rgb >> 8 & 0xFF) > 220 && (rgb & 0xFF) > 220;
    }
}
