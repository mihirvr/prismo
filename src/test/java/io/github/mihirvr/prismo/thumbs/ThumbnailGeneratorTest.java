package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.testing.TestMedia;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailGeneratorTest {

    /** A 1×1 lossless WebP (the well-known WebP feature-detection image). */
    private static final String TINY_WEBP = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    @TempDir
    Path tmp;

    private final ThumbnailGenerator generator = new ThumbnailGenerator(320);

    @Test
    void largeImageIsScaledToTheLongEdgeKeepingAspect() throws Exception {
        Path source = tmp.resolve("big.jpg");
        ImageIO.write(new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB), "jpg", source.toFile());

        BufferedImage thumb = generate(source);

        assertEquals(320, thumb.getWidth());
        assertEquals(160, thumb.getHeight());
    }

    @Test
    void smallImageIsNotUpscaled() throws Exception {
        BufferedImage thumb = generate(TestMedia.png(tmp.resolve("small.png"), 100, 50));

        assertEquals(100, thumb.getWidth());
        assertEquals(50, thumb.getHeight());
    }

    @Test
    void exifOrientation6IsRotatedClockwise() throws Exception {
        // Stored landscape 400×200: red top-left quarter, blue elsewhere. Orientation 6 = rotate 90° CW,
        // so the red corner must end up top-RIGHT of a portrait thumbnail.
        BufferedImage pixels = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = pixels.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 400, 200);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        Path source = TestMedia.jpegWithExif(tmp.resolve("rotated.jpg"), pixels, "Apple", "iPhone", 6,
                "2024:01:01 10:00:00");

        BufferedImage thumb = generate(source);

        assertEquals(160, thumb.getWidth());
        assertEquals(320, thumb.getHeight());
        assertTrue(isRed(thumb.getRGB(thumb.getWidth() - 20, 20)), "top-right should be red");
        assertTrue(isBlue(thumb.getRGB(20, 20)), "top-left should be blue");
    }

    @Test
    void transparentPngBecomesOpaqueWhite() throws Exception {
        Path source = tmp.resolve("clear.png");
        ImageIO.write(new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB), "png", source.toFile());

        BufferedImage thumb = generate(source);

        Color c = new Color(thumb.getRGB(25, 25));
        assertTrue(c.getRed() > 240 && c.getGreen() > 240 && c.getBlue() > 240, "expected white, got " + c);
    }

    @Test
    void webpIsDecodedThroughTheTwelveMonkeysPlugin() throws Exception {
        Path source = Files.write(tmp.resolve("tiny.webp"), Base64.getDecoder().decode(TINY_WEBP));

        BufferedImage thumb = generate(source);

        assertEquals(1, thumb.getWidth());
        assertEquals(1, thumb.getHeight());
    }

    @Test
    void knowsWhichFormatsItCanDecode() {
        assertTrue(ThumbnailGenerator.canDecode("jpg"));
        assertTrue(ThumbnailGenerator.canDecode("png"));
        assertTrue(ThumbnailGenerator.canDecode("gif"));
        assertTrue(ThumbnailGenerator.canDecode("webp"));
        assertFalse(ThumbnailGenerator.canDecode("heic"));
        assertFalse(ThumbnailGenerator.canDecode("mp4"));
        assertFalse(ThumbnailGenerator.canDecode("nef"));
    }

    @Test
    void videosHeicAndRawGetThumbnailsTooButNotUnknownTypes() {
        assertTrue(ThumbnailGenerator.canMake("jpg"));
        assertTrue(ThumbnailGenerator.canMake("mp4"));
        assertTrue(ThumbnailGenerator.canMake("MOV"));
        assertTrue(ThumbnailGenerator.canMake("mkv"));
        assertTrue(ThumbnailGenerator.canMake("heic"), "through ffmpeg since P7");
        assertTrue(ThumbnailGenerator.canMake("nef"), "its JPEG preview since P7");
        assertFalse(ThumbnailGenerator.canMake("xyz"));
    }

    @Test
    void aVideoGoesToFfmpeg() throws Exception {
        Path source = TestMedia.video(tmp.resolve("clip.mp4"), 640, 480, 25, 25, false, 0);

        BufferedImage thumb = generate(source);

        assertEquals(320, thumb.getWidth());
        assertEquals(240, thumb.getHeight());
    }

    private BufferedImage generate(Path source) throws Exception {
        Path target = tmp.resolve("thumb-" + source.getFileName() + ".jpg");
        generator.generate(source, target);
        return ImageIO.read(target.toFile());
    }

    private static boolean isRed(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 180 && c.getBlue() < 90;
    }

    private static boolean isBlue(int rgb) {
        Color c = new Color(rgb);
        return c.getBlue() > 180 && c.getRed() < 90;
    }
}
