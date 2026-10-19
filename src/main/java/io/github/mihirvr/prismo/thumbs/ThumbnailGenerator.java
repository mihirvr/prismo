package io.github.mihirvr.prismo.thumbs;

import io.github.mihirvr.prismo.platform.FileReads;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Turns one image file into a small JPEG: at most {@code size} pixels on the long edge, the right way up.
 *
 * <ol>
 *   <li><b>Subsampled decode:</b> ImageIO is asked to read only every Nth pixel and row, so a 24 MP
 *       photo is decoded at about twice the target size, never at full size. This is where most of the
 *       time and memory is saved.</li>
 *   <li><b>Scale + orient:</b> one bilinear draw from that ~2× image to the target size, through an
 *       affine transform for the EXIF orientation (rotate / flip), onto an opaque canvas.</li>
 *   <li><b>Encode:</b> JPEG at quality 0.85.</li>
 * </ol>
 *
 * <p>Pure CPU + file work with no shared state, so it's safe to call from many threads at once.
 */
public final class ThumbnailGenerator {

    private static final float JPEG_QUALITY = 0.85f;

    private final int size;

    public ThumbnailGenerator(int size) {
        this.size = size;
    }

    /** True if some installed ImageIO reader handles this extension (JDK readers + TwelveMonkeys plugins). */
    public static boolean canDecode(String extension) {
        return ImageIO.getImageReadersBySuffix(extension).hasNext();
    }

    public void generate(Path source, Path target) throws IOException {
        BufferedImage decoded = decodeSubsampled(source);
        BufferedImage thumbnail = scaleAndOrient(decoded, ExifOrientation.read(source));
        writeJpeg(thumbnail, target);
    }

    private BufferedImage decodeSubsampled(Path source) throws IOException {
        try (ImageInputStream in = FileReads.image(source)) {
            if (in == null) {
                throw new IOException("Cannot open " + source);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("No image reader recognises " + source);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true); // forward-only, skip metadata: faster
                int longEdge = Math.max(reader.getWidth(0), reader.getHeight(0));
                int step = Math.max(1, longEdge / (size * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    BufferedImage scaleAndOrient(BufferedImage image, int orientation) {
        double scale = Math.min(1.0, (double) size / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        boolean swap = ExifOrientation.swapsSides(orientation);

        BufferedImage canvas = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE); // transparent PNG/GIF areas become white (JPEG has no alpha)
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.transform(ExifOrientation.transform(orientation, w, h));
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static void writeJpeg(BufferedImage image, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
