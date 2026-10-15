package io.github.mihirvr.prismo.index;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.imageio.ImageIO;

/** Builds small but real media files byte by byte, so metadata reading is tested against actual formats. */
final class TestMedia {

    /** Seconds between the MP4 epoch (1904-01-01) and the Unix epoch (1970-01-01). */
    private static final long MP4_EPOCH_OFFSET = 2_082_844_800L;

    private TestMedia() {
    }

    /** A plain PNG (no EXIF) of the given size. */
    static Path png(Path file, int width, int height) throws IOException {
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    /**
     * A JPEG of {@code width}×{@code height} pixels with an EXIF block: Make, Model, Orientation and
     * DateTimeOriginal ("yyyy:MM:dd HH:mm:ss").
     */
    static Path jpegWithExif(Path file, int width, int height, String make, String model, int orientation,
            String dateTimeOriginal) throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", plain);
        byte[] jpeg = plain.toByteArray();
        byte[] exif = exifSegment(make, model, orientation, dateTimeOriginal);

        // Insert the APP1 (EXIF) segment straight after the 2-byte SOI marker.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        Files.write(file, out.toByteArray());
        return file;
    }

    /** A minimal MP4 (ftyp + moov/mvhd) with a creation time and a duration; no actual video track. */
    static Path mp4(Path file, Instant created, double durationSeconds) throws IOException {
        int timescale = 1000;
        ByteBuffer mvhd = ByteBuffer.allocate(108);
        mvhd.putInt(108).put(ascii("mvhd"));
        mvhd.putInt(0);                                              // version 0 + flags
        mvhd.putInt((int) (created.getEpochSecond() + MP4_EPOCH_OFFSET)); // creation time (unsigned 32-bit)
        mvhd.putInt((int) (created.getEpochSecond() + MP4_EPOCH_OFFSET)); // modification time
        mvhd.putInt(timescale);
        mvhd.putInt((int) Math.round(durationSeconds * timescale));
        mvhd.putInt(0x00010000).putShort((short) 0x0100).put(new byte[10]); // rate 1.0, volume 1.0, reserved
        int[] identity = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
        for (int value : identity) {
            mvhd.putInt(value);
        }
        mvhd.put(new byte[24]).putInt(1);                            // pre-defined, next track id

        ByteBuffer out = ByteBuffer.allocate(24 + 8 + 108);
        out.putInt(24).put(ascii("ftyp")).put(ascii("isom")).putInt(0x200).put(ascii("isom")).put(ascii("mp41"));
        out.putInt(8 + 108).put(ascii("moov")).put(mvhd.array());
        Files.write(file, out.array());
        return file;
    }

    /** APP1 segment: marker, length, "Exif\0\0", then a big-endian TIFF structure (IFD0 + Exif sub-IFD). */
    private static byte[] exifSegment(String make, String model, int orientation, String dateTimeOriginal) {
        byte[] makeBytes = asciiZ(make);
        byte[] modelBytes = asciiZ(model);
        byte[] dateBytes = asciiZ(dateTimeOriginal);

        int ifd0Offset = 8;
        int ifd0Size = 2 + 4 * 12 + 4;
        int makeOffset = ifd0Offset + ifd0Size;
        int modelOffset = makeOffset + makeBytes.length;
        int subIfdOffset = modelOffset + modelBytes.length;
        int subIfdSize = 2 + 12 + 4;
        int dateOffset = subIfdOffset + subIfdSize;
        int tiffSize = dateOffset + dateBytes.length;

        ByteBuffer tiff = ByteBuffer.allocate(tiffSize);       // big-endian by default
        tiff.put(ascii("MM")).putShort((short) 42).putInt(ifd0Offset);
        tiff.putShort((short) 4);                               // IFD0 entry count (sorted by tag)
        entry(tiff, 0x010F, 2, makeBytes.length, makeOffset);  // Make, ASCII
        entry(tiff, 0x0110, 2, modelBytes.length, modelOffset); // Model, ASCII
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1) // Orientation, SHORT, value inline
                .putShort((short) orientation).putShort((short) 0);
        entry(tiff, 0x8769, 4, 1, subIfdOffset);                // pointer to the Exif sub-IFD
        tiff.putInt(0);                                         // no next IFD
        tiff.put(makeBytes).put(modelBytes);
        tiff.putShort((short) 1);                               // sub-IFD entry count
        entry(tiff, 0x9003, 2, dateBytes.length, dateOffset);   // DateTimeOriginal, ASCII
        tiff.putInt(0);
        tiff.put(dateBytes);

        byte[] header = ascii("Exif\0\0");
        int length = 2 + header.length + tiffSize;               // segment length counts itself
        ByteBuffer segment = ByteBuffer.allocate(2 + length);
        segment.put((byte) 0xFF).put((byte) 0xE1).putShort((short) length).put(header).put(tiff.array());
        return segment.array();
    }

    private static void entry(ByteBuffer tiff, int tag, int type, int count, int valueOrOffset) {
        tiff.putShort((short) tag).putShort((short) type).putInt(count).putInt(valueOrOffset);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] asciiZ(String text) {
        return ascii(text + "\0");
    }
}
