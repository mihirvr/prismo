package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
