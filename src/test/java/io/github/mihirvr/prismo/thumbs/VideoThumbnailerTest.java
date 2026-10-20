package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.testing.TestMedia;
import io.github.mihirvr.prismo.video.VideoNatives;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        List<String> command = thumbnailer.command(Path.of("ffmpeg"), Path.of("in.mp4"), Path.of("out.tmp"), true);

        assertTrue(command.indexOf("-ss") >= 0 && command.indexOf("-ss") < command.indexOf("-i"), command.toString());
        assertEquals(VideoThumbnailer.FRAME_AT, command.get(command.indexOf("-ss") + 1));
        assertEquals("out.tmp", command.getLast());
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
