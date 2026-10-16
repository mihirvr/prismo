package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.mihirvr.prismo.testing.TestMedia;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExifOrientationTest {

    @TempDir
    Path tmp;

    @Test
    void everyOrientationMapsTheImageExactlyOntoTheCanvas() {
        int w = 40;
        int h = 30;
        for (int orientation = 1; orientation <= 8; orientation++) {
            boolean swap = orientation >= 5;
            Rectangle canvas = new Rectangle(0, 0, swap ? h : w, swap ? w : h);
            Rectangle mapped = ExifOrientation.transform(orientation, w, h)
                    .createTransformedShape(new Rectangle(0, 0, w, h)).getBounds();
            assertEquals(canvas, mapped, "orientation " + orientation);
        }
    }

    /** The pixel copy must give exactly what a Java2D draw through the matrix gives, for all 8. */
    @Test
    void pixelCopyMatchesTheMatrixForEveryOrientation() {
        int w = 5;
        int h = 3;
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | (i * 0x10F0F); // every pixel different
        }
        BufferedImage source = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, w, h, pixels, 0, w);

        for (int orientation = 1; orientation <= 8; orientation++) {
            boolean swap = ExifOrientation.swapsSides(orientation);
            BufferedImage expected = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = expected.createGraphics();
            g.transform(ExifOrientation.transform(orientation, w, h));
            g.drawImage(source, 0, 0, null); // whole-pixel quarter turns: nearest neighbour is exact
            g.dispose();

            int[] actual = ExifOrientation.apply(pixels, w, h, orientation);

            assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0,
                    expected.getWidth()), actual, "orientation " + orientation);
        }
    }

    @Test
    void orientationOneAndUnknownValuesReturnThePixelsUntouched() {
        int[] pixels = {1, 2, 3, 4};
        assertSame(pixels, ExifOrientation.apply(pixels, 2, 2, 1));
        assertSame(pixels, ExifOrientation.apply(pixels, 2, 2, 0));
        assertSame(pixels, ExifOrientation.apply(pixels, 2, 2, 9));
    }

    @Test
    void readsTheTagAndFallsBackToOne() throws Exception {
        Path rotated = TestMedia.jpegWithExif(tmp.resolve("r.jpg"), 8, 4, "Apple", "iPhone", 6, "2024:01:01 10:00:00");
        Path plain = TestMedia.png(tmp.resolve("p.png"), 8, 4);
        Path garbage = Files.write(tmp.resolve("g.jpg"), new byte[] {1, 2, 3});

        assertEquals(6, ExifOrientation.read(rotated));
        assertEquals(1, ExifOrientation.read(plain));
        assertEquals(1, ExifOrientation.read(garbage));
        assertEquals(1, ExifOrientation.read(tmp.resolve("missing.jpg")));
    }
}
