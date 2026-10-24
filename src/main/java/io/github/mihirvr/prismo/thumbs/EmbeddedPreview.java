package io.github.mihirvr.prismo.thumbs;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;

/**
 * The small preview picture most cameras and phones store in a JPEG's EXIF (1.0.1), read from the first bytes of the
 * file only.
 *
 * <p>Why: a thumbnail made from the whole photo means reading the whole file, and on a spinning disk that is the slow
 * part, not the decoding. Measured on a real 40,000-photo library on a laptop hard disk: about 9 camera photos (4 MB)
 * a second read whole, against 31-38 a second when only the first 128 KB are read. 95% of those photos carried a
 * 320 x 240 preview, the size of Prismo's thumbnails.
 *
 * <p>The preview is only used when it is as big as the thumbnail and has the photo's shape (see {@link #fits}); a
 * preview with black bars, or an old one left behind by an editor that cropped or turned the photo, has another shape.
 *
 * <p>Layout read here: the JPEG's segments up to the frame header (SOF, the photo's real size); in the EXIF segment
 * (APP1), a TIFF block whose first directory (IFD0) holds the orientation and whose second (IFD1) points at the preview,
 * itself a complete JPEG inside the same segment.
 */
final class EmbeddedPreview {

    /** How much of the file is read: the EXIF segment is at most 64 KB, and the frame header follows it. */
    static final int HEADER_BYTES = 256 * 1024;
    /** How far the preview's width : height may be from the photo's, as a share of it (rounding to whole pixels). */
    private static final double SHAPE_TOLERANCE = 0.02;

    /** A preview (still encoded), the photo's own size as stored, and its EXIF orientation (1 if none). */
    record Found(byte[] jpeg, int photoWidth, int photoHeight, int orientation) {
    }

    private EmbeddedPreview() {
    }

    /** The preview in {@code header} (the first bytes of a JPEG file), if it has one and its frame header. */
    static Optional<Found> find(byte[] header) {
        try {
            return Optional.ofNullable(read(header));
        } catch (IndexOutOfBoundsException | BufferUnderflowException e) {
            return Optional.empty(); // cut off, or offsets pointing outside: not usable
        }
    }

    /**
     * True if a preview of {@code width} x {@code height} can stand in for the photo at thumbnail size {@code size}:
     * its long edge is at least {@code size}, and its shape matches the photo's (both as stored, before turning).
     */
    static boolean fits(int width, int height, Found found, int size) {
        if (Math.max(width, height) < size || height <= 0 || found.photoHeight() <= 0) {
            return false;
        }
        double photo = (double) found.photoWidth() / found.photoHeight();
        double preview = (double) width / height;
        return Math.abs(preview - photo) <= SHAPE_TOLERANCE * photo;
    }

    private static Found read(byte[] bytes) {
        ByteBuffer file = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN); // JPEG segments are big-endian
        if (file.getShort(0) != (short) 0xFFD8) {
            return null;
        }
        int exifStart = -1;
        int exifLength = 0;
        int position = 2;
        while (true) {
            if ((file.get(position) & 0xFF) != 0xFF) {
                return null; // not a segment: damaged, or not where it should be
            }
            int marker = file.get(position + 1) & 0xFF;
            if (marker == 0xFF) {
                position++; // fill byte
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return null; // image data (or the end) before any frame header
            }
            int length = file.getShort(position + 2) & 0xFFFF;
            if (marker == 0xE1 && exifStart < 0 && length > 8 && startsWithExif(bytes, position + 4)) {
                exifStart = position + 10; // after the length and "Exif\0\0": the TIFF block
                exifLength = length - 8;
            }
            if (isFrameHeader(marker)) {
                int height = file.getShort(position + 5) & 0xFFFF;
                int width = file.getShort(position + 7) & 0xFFFF;
                return exifStart < 0 ? null : preview(bytes, exifStart, exifLength, width, height);
            }
            position += 2 + length;
        }
    }

    /** SOF0-SOF15, except DHT (C4), JPG (C8) and DAC (CC), which share the range. */
    private static boolean isFrameHeader(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static boolean startsWithExif(byte[] bytes, int at) {
        return Arrays.equals(bytes, at, at + 6, new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, 6);
    }

    private static Found preview(byte[] bytes, int start, int length, int photoWidth, int photoHeight) {
        ByteBuffer tiff = ByteBuffer.wrap(bytes, start, length).slice();
        tiff.order(tiff.getShort(0) == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN); // "II" or "MM"
        int ifd0 = tiff.getInt(4);
        int entries = tiff.getShort(ifd0) & 0xFFFF;
        int orientation = 1;
        for (int i = 0; i < entries; i++) {
            int entry = ifd0 + 2 + i * 12;
            if ((tiff.getShort(entry) & 0xFFFF) == 0x0112) {
                int value = tiff.getShort(entry + 8) & 0xFFFF; // a SHORT sits at the start of the value field
                orientation = value >= 1 && value <= 8 ? value : 1;
            }
        }
        int ifd1 = tiff.getInt(ifd0 + 2 + entries * 12);
        if (ifd1 <= 0) {
            return null; // no second directory: no preview
        }
        int offset = -1;
        int size = -1;
        int count = tiff.getShort(ifd1) & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int entry = ifd1 + 2 + i * 12;
            int tag = tiff.getShort(entry) & 0xFFFF;
            if (tag == 0x0201) {
                offset = tiff.getInt(entry + 8);
            } else if (tag == 0x0202) {
                size = tiff.getInt(entry + 8);
            }
        }
        if (offset <= 0 || size <= 0 || offset + size > length) {
            return null;
        }
        byte[] jpeg = Arrays.copyOfRange(bytes, start + offset, start + offset + size);
        if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return null; // an uncompressed (TIFF) preview, which Prismo doesn't read
        }
        return new Found(jpeg, photoWidth, photoHeight, orientation);
    }
}
