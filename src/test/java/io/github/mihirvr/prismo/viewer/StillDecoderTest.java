package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.testing.TestMedia;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StillDecoderTest {

    @TempDir
    Path tmp;

    @Test
    void exifRotationIsBakedIntoThePixels() throws Exception {
        // Stored landscape 400 × 200 with a red top-left quarter; orientation 6 = turn 90° clockwise,
        // so the red corner must end up top-RIGHT of a 200 × 400 portrait.
        BufferedImage stored = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = stored.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 400, 200);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        Path file = TestMedia.jpegWithExif(tmp.resolve("r.jpg"), stored, "Apple", "iPhone", 6, "2024:01:01 10:00:00");

        StillDecoder.Pixels pixels = StillDecoder.decode(file, StillDecoder.MAX_PIXELS);

        assertEquals(200, pixels.width());
        assertEquals(400, pixels.height());
        assertEquals(200, pixels.fullWidth());
        assertEquals(400, pixels.fullHeight());
        assertTrue(isRed(at(pixels, 150, 50)), "top-right is red");
        assertTrue(isBlue(at(pixels, 50, 50)), "top-left is blue");
        assertTrue(isBlue(at(pixels, 150, 350)), "bottom-right is blue");
    }

    @Test
    void bigImagesAreDecodedSubsampledButReportTheirRealSize() throws Exception {
        Path file = TestMedia.png(tmp.resolve("big.png"), 400, 300);

        StillDecoder.Pixels pixels = StillDecoder.decode(file, 40_000); // 120,000 px > 40,000 → step 2

        assertEquals(200, pixels.width());
        assertEquals(150, pixels.height());
        assertEquals(400, pixels.fullWidth());
        assertEquals(300, pixels.fullHeight());
        assertEquals(200 * 150, pixels.argb().length);
    }

    @Test
    void aBigRawPreviewIsDecodedSubsampledToo() throws Exception {
        // P7: the RAW / HEIC path decoded its picture whole (a 60 MP ARW preview: ~580 MB); it now takes the same cap.
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        raw.write(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0});
        raw.write(new byte[3000]);
        ImageIO.write(new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), "jpg", raw);
        raw.write(new byte[3000]);
        Path file = Files.write(tmp.resolve("DSC_0001.NEF"), raw.toByteArray());

        StillDecoder.Pixels pixels = StillDecoder.decode(file, 40_000);

        assertEquals(200, pixels.width());
        assertEquals(150, pixels.height());
        assertEquals(400, pixels.fullWidth());
        assertEquals(300, pixels.fullHeight());
    }

    @Test
    void subsampleStepIsTheSmallestThatFitsTheCap() {
        assertEquals(1, StillDecoder.subsampleStep(4000, 3000, StillDecoder.MAX_PIXELS));  // 12 MP
        assertEquals(1, StillDecoder.subsampleStep(6000, 4000, StillDecoder.MAX_PIXELS));  // exactly 24 MP
        assertEquals(2, StillDecoder.subsampleStep(8000, 6000, StillDecoder.MAX_PIXELS));  // 48 MP → 12 MP
        assertEquals(3, StillDecoder.subsampleStep(12000, 9000, StillDecoder.MAX_PIXELS)); // 108 MP → 12 MP
        // Odd sizes round up: 5 px at step 2 keeps pixels 0, 2, 4 = 3.
        assertEquals(2, StillDecoder.subsampleStep(5, 5, 9));
        assertEquals(3, StillDecoder.subsampleStep(5, 5, 8));
    }

    @Test
    void transparencyIsKept() throws Exception {
        BufferedImage clear = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB); // all transparent
        Path file = tmp.resolve("clear.png");
        ImageIO.write(clear, "png", file.toFile());

        StillDecoder.Pixels pixels = StillDecoder.decode(file, StillDecoder.MAX_PIXELS);

        assertEquals(0, pixels.argb()[0] >>> 24);
    }

    @Test
    void missingAndBrokenFilesFailWithAnIoException() throws Exception {
        assertThrows(NoSuchFileException.class, () -> StillDecoder.decode(tmp.resolve("gone.jpg"), 100));

        Path broken = Files.write(tmp.resolve("broken.jpg"), new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        assertThrows(IOException.class, () -> StillDecoder.decode(broken, 100));

        Path text = Files.writeString(tmp.resolve("text.png"), "not an image");
        assertThrows(IOException.class, () -> StillDecoder.decode(text, 100));
    }

    private static int at(StillDecoder.Pixels p, int x, int y) {
        return p.argb()[y * p.width() + x];
    }

    private static boolean isRed(int argb) {
        Color c = new Color(argb);
        return c.getRed() > 180 && c.getBlue() < 90;
    }

    private static boolean isBlue(int argb) {
        Color c = new Color(argb);
        return c.getBlue() > 180 && c.getRed() < 90;
    }
}
