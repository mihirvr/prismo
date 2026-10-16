package io.github.mihirvr.prismo.thumbs;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.geom.AffineTransform;
import java.nio.file.Path;

/**
 * EXIF orientation 1–8: how the stored pixels must be turned / mirrored to be the right way up.
 * Phones often store a portrait photo as landscape pixels plus "rotate 90°" (6 or 8).
 *
 * <p>Two forms of the same 8 mappings: an {@link AffineTransform} for Java2D drawing (the thumbnailer
 * scales and turns in one draw), and {@link #apply}, a plain pixel copy for full-size images in the viewer,
 * where a rotated Java2D draw was measured 7–10× slower than an unrotated one.
 */
public final class ExifOrientation {

    private ExifOrientation() {
    }

    /** The file's EXIF orientation, or 1 ("as stored") if it has none or it can't be read. */
    public static int read(Path file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            Integer value = ifd0 == null ? null : ifd0.getInteger(ExifDirectoryBase.TAG_ORIENTATION);
            return value == null || value < 1 || value > 8 ? 1 : value;
        } catch (Exception e) {
            return 1; // no or unreadable EXIF: show as stored
        }
    }

    /** Orientations 5–8 turn the image a quarter, so width and height swap. */
    public static boolean swapsSides(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    /**
     * Maps the stored pixels (w × h) onto the display canvas for EXIF orientation 1–8.
     * {@code new AffineTransform(m00, m10, m01, m11, m02, m12)} means x' = m00·x + m01·y + m02 and
     * y' = m10·x + m11·y + m12.
     */
    public static AffineTransform transform(int orientation, int w, int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirror horizontally
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // rotate 180°
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirror vertically
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // transpose (mirror across the diagonal)
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // rotate 90° clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // transverse
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);   // rotate 90° counter-clockwise
            default -> new AffineTransform();                    // 1 or unknown: as stored
        };
    }

    /**
     * The same mapping as {@link #transform}, on a row-major pixel array (w × h). Returns {@code pixels}
     * itself for orientation 1, else a new array of (h × w) for 5–8 or (w × h) for 2–4.
     *
     * <p>Pixel (x, y) covers the square [x, x+1) × [y, y+1), so the matrix's "w − x" becomes "w − 1 − x".
     */
    public static int[] apply(int[] pixels, int w, int h, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return pixels;
        }
        int outWidth = swapsSides(orientation) ? h : w;
        int[] out = new int[pixels.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int tx;
                int ty;
                switch (orientation) {
                    case 2 -> { tx = w - 1 - x; ty = y; }
                    case 3 -> { tx = w - 1 - x; ty = h - 1 - y; }
                    case 4 -> { tx = x; ty = h - 1 - y; }
                    case 5 -> { tx = y; ty = x; }
                    case 6 -> { tx = h - 1 - y; ty = x; }
                    case 7 -> { tx = h - 1 - y; ty = w - 1 - x; }
                    default -> { tx = y; ty = w - 1 - x; } // 8
                }
                out[ty * outWidth + tx] = pixels[row + x];
            }
        }
        return out;
    }
}
