package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.platform.FileReads;
import io.github.mihirvr.prismo.thumbs.ExifOrientation;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Iterator;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Decodes a full image for the viewer: ImageIO (so WEBP and CMYK JPEGs work through the TwelveMonkeys plugins,
 * which JavaFX's own {@code Image} can't read), EXIF rotation baked into the pixels (JavaFX's {@code Image}
 * ignores it), then copied into a JavaFX image with a {@code PixelWriter} ({@code javafx.swing} isn't a
 * dependency).
 *
 * <p><b>Size cap.</b> Images over {@link #MAX_PIXELS} are decoded subsampled (every 2nd or 3rd pixel and row).
 * Measured on this project's machine: JavaFX showed images up to 20,000 px wide at full detail, so the GPU
 * isn't the limit; memory is. Each decoded pixel costs 4 bytes, and the viewer keeps three images (previous,
 * current, next). 24 MP = 96 MB per image keeps every 12–24 MP camera at full size and turns a 48 MP photo
 * into 12 MP. Subsampling saves memory more than time: a 48 MP JPEG took ~1.0 s at full size, ~0.86 s at step 2.
 *
 * <p>{@link #decode} is plain Java (no JavaFX), safe on any thread, and tested directly.
 */
final class StillDecoder {

    static final long MAX_PIXELS = 24_000_000;

    /**
     * Decoded pixels, the right way up.
     *
     * @param argb       premultiplied ARGB, row-major, {@code width × height}
     * @param fullWidth  the image's real width (after orientation), before any subsampling
     * @param fullHeight the same for height
     */
    record Pixels(int[] argb, int width, int height, int fullWidth, int fullHeight) {
    }

    private StillDecoder() {
    }

    static Pixels decode(Path file, long maxPixels) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }
        BufferedImage decoded;
        int fullWidth;
        int fullHeight;
        try (ImageInputStream in = FileReads.image(file)) {
            if (in == null) {
                throw new IOException("Cannot open " + file);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("Not an image format Prismo can read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true); // forward-only, skip metadata: faster
                fullWidth = reader.getWidth(0);
                fullHeight = reader.getHeight(0);
                int step = subsampleStep(fullWidth, fullHeight, maxPixels);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                decoded = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            // Some readers throw unchecked exceptions on corrupt data; for the viewer that's just "can't read".
            throw new IOException("Corrupt image data: " + e, e);
        }

        int w = decoded.getWidth();
        int h = decoded.getHeight();
        // One Java2D draw turns any layout (BGR bytes, grey, palette, alpha) into premultiplied ARGB ints.
        BufferedImage argbImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = argbImage.createGraphics();
        try {
            g.drawImage(decoded, 0, 0, null);
        } finally {
            g.dispose();
        }
        int[] argb = ((DataBufferInt) argbImage.getRaster().getDataBuffer()).getData();

        int orientation = ExifOrientation.read(file);
        boolean swap = ExifOrientation.swapsSides(orientation);
        return new Pixels(ExifOrientation.apply(argb, w, h, orientation),
                swap ? h : w, swap ? w : h,
                swap ? fullHeight : fullWidth, swap ? fullWidth : fullHeight);
    }

    /** The smallest step n (≥ 1) so that reading every nth pixel and row gives at most {@code maxPixels}. */
    static int subsampleStep(int width, int height, long maxPixels) {
        int step = 1;
        // ImageIO keeps pixels 0, n, 2n…, so a side of w becomes ceil(w / n).
        while ((long) ceilDiv(width, step) * ceilDiv(height, step) > maxPixels) {
            step++;
        }
        return step;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** Copies the pixels into a JavaFX image. Allowed on any thread while the image isn't on screen yet. */
    static WritableImage toImage(Pixels pixels) {
        WritableImage image = new WritableImage(pixels.width(), pixels.height());
        image.getPixelWriter().setPixels(0, 0, pixels.width(), pixels.height(),
                PixelFormat.getIntArgbPreInstance(), pixels.argb(), 0, pixels.width());
        return image;
    }
}
