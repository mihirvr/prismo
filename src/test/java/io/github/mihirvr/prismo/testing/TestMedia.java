package io.github.mihirvr.prismo.testing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.imageio.ImageIO;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

/**
 * Builds small but real media files byte by byte, so metadata and thumbnail code is tested against
 * actual formats. Shared by the tests of several packages.
 */
public final class TestMedia {

    /** Seconds between the MP4 epoch (1904-01-01) and the Unix epoch (1970-01-01). */
    private static final long MP4_EPOCH_OFFSET = 2_082_844_800L;
    /** Sound sample rate of {@link #video}. */
    private static final int SAMPLE_RATE = 44_100;

    private TestMedia() {
    }

    /** A plain PNG (no EXIF) of the given size. */
    public static Path png(Path file, int width, int height) throws IOException {
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    /**
     * A JPEG of {@code width}×{@code height} pixels with an EXIF block: Make, Model, Orientation and
     * DateTimeOriginal ("yyyy:MM:dd HH:mm:ss").
     */
    public static Path jpegWithExif(Path file, int width, int height, String make, String model, int orientation,
            String dateTimeOriginal) throws IOException {
        return jpegWithExif(file, new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), make, model,
                orientation, dateTimeOriginal);
    }

    /** Same, with given pixels (e.g. a coloured corner to check rotation). */
    public static Path jpegWithExif(Path file, BufferedImage pixels, String make, String model, int orientation,
            String dateTimeOriginal) throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(pixels, "jpg", plain);
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
    public static Path mp4(Path file, Instant created, double durationSeconds) throws IOException {
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

    /**
     * A real, playable video made with FFmpeg (JavaCV's recorder): H.264 in the container the file name asks
     * for (.mp4, .mov, .mkv), {@code frames} frames at {@code fps}, plus a 440 Hz AAC tone if {@code sound}.
     *
     * <p>Frame i is one flat colour with red = {@link #frameRed}(i), so a test can tell which frame it got
     * (flat colours survive compression within a few levels), except the top-left quarter, which is white in
     * every frame (to check which way a picture is turned). {@code clockwiseRotation} adds the "display
     * rotation" tag phones write (0 for none).
     */
    public static Path video(Path file, int width, int height, int frames, int fps, boolean sound,
            double clockwiseRotation) throws IOException {
        String name = file.getFileName().toString();
        avutil.av_log_set_level(avutil.AV_LOG_ERROR); // the encoders print their settings and warnings otherwise
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file.toString(), width, height, sound ? 2 : 0)) {
            recorder.setFormat(switch (name.substring(name.lastIndexOf('.') + 1)) {
                case "mkv" -> "matroska";
                case "mov" -> "mov";
                default -> "mp4";
            });
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(fps);
            recorder.setVideoBitrate(500_000);
            if (clockwiseRotation != 0) {
                // JavaCV's angle is counter-clockwise (it hands FFmpeg's clockwise av_display_rotation_set -angle);
                // checked against the ffmpeg program's own auto-rotate in VideoThumbnailerTest.
                recorder.setDisplayRotation(-clockwiseRotation);
            }
            if (sound) {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                recorder.setSampleRate(SAMPLE_RATE);
                recorder.setAudioBitrate(96_000);
            }
            recorder.start();
            Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3); // BGR, 3 bytes per pixel
            ByteBuffer pixels = (ByteBuffer) frame.image[0];
            int samplesPerFrame = SAMPLE_RATE / fps;
            for (int i = 0; i < frames; i++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        boolean marker = x < width / 4 && y < height / 4;
                        int at = y * frame.imageStride + x * 3;
                        pixels.put(at, (byte) (marker ? 255 : 64));                 // blue
                        pixels.put(at + 1, (byte) (marker ? 255 : 128));            // green
                        pixels.put(at + 2, (byte) (marker ? 255 : frameRed(i)));    // red
                    }
                }
                recorder.record(frame);
                if (sound) {
                    ShortBuffer tone = ShortBuffer.allocate(samplesPerFrame * 2);
                    for (int s = 0; s < samplesPerFrame; s++) {
                        short value = (short) (8000 * Math.sin(2 * Math.PI * 440 * (i * samplesPerFrame + s) / SAMPLE_RATE));
                        tone.put(value).put(value);
                    }
                    recorder.recordSamples(SAMPLE_RATE, 2, tone.flip());
                }
            }
            recorder.stop();
        }
        return file;
    }

    /** The red level of frame {@code index} in {@link #video}. */
    public static int frameRed(int index) {
        return index * 5 % 256;
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
