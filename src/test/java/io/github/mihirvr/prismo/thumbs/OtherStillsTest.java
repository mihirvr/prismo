package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.video.VideoNatives;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OtherStillsTest {

    @TempDir
    Path tmp;

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** Like a RAW file: sensor-ish bytes with a small and a big JPEG preview inside. */
    private static byte[] fakeRaw() throws IOException {
        Random random = new Random(7);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] noise = new byte[5000];
        random.nextBytes(noise);
        raw.write(new byte[] {'I', 'I', 42, 0});
        raw.write(noise);
        raw.write(jpeg(160, 120)); // the small thumbnail
        raw.write(noise);
        raw.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC3, 0, 11, 8, 0, 9, 0, 9, 1, 1, 0x11, 0});
        raw.write(noise); // a lossless JPEG start (SOF3), as RAW sensor data is often stored: not a preview
        raw.write(jpeg(1200, 800)); // the big preview
        raw.write(noise);
        return raw.toByteArray();
    }

    @Test
    void theLargestPreviewInsideARawFileIsUsed() throws Exception {
        Path nef = Files.write(tmp.resolve("DSC_0001.NEF"), fakeRaw());

        BufferedImage picture = OtherStills.decode(nef, () -> {
            throw new IOException("ffmpeg is not needed for RAW");
        });
        assertEquals(1200, picture.getWidth());
        assertEquals(800, picture.getHeight());
    }

    @Test
    void ffmpegWritesAnUncompressedBmpScaledOnlyWhenAskedTo() {
        Path ffmpeg = Path.of("ffmpeg.exe");
        Path in = Path.of("IMG_1.HEIC");
        Path out = Path.of("out.bmp");

        java.util.List<String> full = OtherStills.heifCommand(ffmpeg, in, 0, out);
        java.util.List<String> thumb = OtherStills.heifCommand(ffmpeg, in, 640, out);

        assertTrue(String.join(" ", full).contains("-c:v bmp"), "BMP: PNG compression was ~90% of the time");
        assertFalse(full.contains("-filter_complex"), "the viewer gets the full picture");
        // Not -vf: ffmpeg joins a tiled HEIC in a filter graph of its own, and refuses a -vf on top of that.
        assertFalse(thumb.contains("-vf"));
        assertTrue(String.join(" ", thumb).contains(
                "-filter_complex scale=w=640:h=640:force_original_aspect_ratio=decrease"));
        assertEquals(out.toString(), thumb.getLast());
    }

    /**
     * Most phone HEICs are a grid of tiles that ffmpeg joins in a filter graph of its own; 1.0.0 added a {@code -vf}
     * on top, which ffmpeg refuses, so every such photo was a failed thumbnail. tiled-grid.avif is a made-up 2 x 2 grid
     * (red, green / blue, yellow; 128 x 96) in the same container layout; how it was made is in make_tiled_grid.py.
     */
    @Test
    void aTiledPhotoGetsAThumbnailWithItsTilesInPlace() throws Exception {
        Path heic = tmp.resolve("IMG_tiled.heic");
        try (InputStream in = getClass().getResourceAsStream("/thumbs/tiled-grid.avif")) {
            Files.copy(in, heic);
        }

        BufferedImage scaled = ImageIO.read(new java.io.ByteArrayInputStream(
                OtherStills.encoded(heic, VideoNatives::ffmpegExecutable, 64)));
        assertEquals(64, scaled.getWidth());
        assertEquals(48, scaled.getHeight());
        assertColour(0xFF0000, scaled.getRGB(8, 8), "top left");
        assertColour(0x00FF00, scaled.getRGB(56, 8), "top right");
        assertColour(0x0000FF, scaled.getRGB(8, 40), "bottom left");
        assertColour(0xFFFF00, scaled.getRGB(56, 40), "bottom right");

        Path thumbnail = tmp.resolve("thumb.jpg");
        new ThumbnailGenerator(64).generate(heic, thumbnail);
        BufferedImage jpeg = ImageIO.read(thumbnail.toFile());
        assertEquals(64, jpeg.getWidth());
        assertColour(0xFFFF00, jpeg.getRGB(56, 40), "the whole grid, not one tile");
    }

    private static void assertColour(int expected, int argb, String where) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int want = (expected >> shift) & 0xFF;
            int got = (argb >> shift) & 0xFF;
            assertTrue(Math.abs(want - got) < 40, where + ": expected %06x, got %06x".formatted(expected,
                    argb & 0xFFFFFF));
        }
    }

    @Test
    void aBigLosslessJpegIsNeverTakenForThePreview() throws Exception {
        // RAW sensor data stored as lossless JPEG (SOF3) says it is 4000 x 3000: ImageIO can't read it, so the small
        // real preview must win even though it has fewer pixels.
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(new byte[] {'I', 'I', 42, 0});
        // A whole lossless JPEG: start, SOF3 frame (3000 x 4000), start of scan, a few bytes, end.
        raw.write(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC3, 0, 11, 8, 0x0B, (byte) 0xB8, 0x0F,
                (byte) 0xA0, 1, 1, 0x11, 0, (byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 0x3F, 0, 0x12, 0x34, 0x56,
                (byte) 0xFF, (byte) 0xD9});
        raw.write(new byte[2000]);
        raw.write(jpeg(160, 120));
        Path cr2 = Files.write(tmp.resolve("IMG_0001.CR2"), raw.toByteArray());

        BufferedImage picture = OtherStills.decode(cr2, () -> null);

        assertEquals(160, picture.getWidth());
    }

    @Test
    void theRawPreviewBytesAreHandedOutForASubsampledRead() throws Exception {
        Path nef = Files.write(tmp.resolve("DSC_0002.NEF"), fakeRaw());

        byte[] picture = OtherStills.encoded(nef, () -> null, 0);

        BufferedImage preview = ImageIO.read(new java.io.ByteArrayInputStream(picture));
        assertEquals(1200, preview.getWidth(), "the big preview, still encoded");
    }

    @Test
    void aFileWithoutAPreviewIsAnError() throws Exception {
        byte[] noise = new byte[20_000];
        new Random(1).nextBytes(noise);
        noise[100] = (byte) 0xFF; // a start mark that leads nowhere
        noise[101] = (byte) 0xD8;
        noise[102] = (byte) 0xFF;
        Path raw = Files.write(tmp.resolve("broken.cr2"), noise);

        IOException error = assertThrows(IOException.class, () -> OtherStills.decode(raw, () -> null));
        assertTrue(error.getMessage().contains("no preview picture"), error.getMessage());
        assertTrue(OtherStills.largestJpeg(jpeg(10, 10)).isPresent(), "a plain JPEG is found too");
    }

    @Test
    void heifAndRawAreHandledHereOtherImagesAreNot() {
        for (String ext : new String[] {"heic", "HEIF", "hif", "cr2", "cr3", "nef", "arw", "dng", "orf", "rw2", "raf"}) {
            assertTrue(OtherStills.handles(ext), ext);
        }
        for (String ext : new String[] {"jpg", "png", "mp4", "txt"}) {
            assertFalse(OtherStills.handles(ext), ext);
        }
        assertFalse(OtherStills.needsExifRotation("heic"), "ffmpeg turns HEIF upright itself");
        assertTrue(OtherStills.needsExifRotation("nef"), "a RAW preview follows the RAW's EXIF");
        assertTrue(ThumbnailGenerator.canMake("heic") && ThumbnailGenerator.canMake("dng"));
    }

    @Test
    void aMissingFileSaysSo() {
        assertThrows(java.nio.file.NoSuchFileException.class, () -> OtherStills.decode(tmp.resolve("gone.heic"),
                () -> null));
    }
}
