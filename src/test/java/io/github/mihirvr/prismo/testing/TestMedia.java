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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import io.github.mihirvr.prismo.video.VideoNatives;

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

    /**
     * A JPEG with a camera's full EXIF block, as a phone writes it: Make "Apple", Model "iPhone 15 Pro",
     * DateTimeOriginal 2024:03:09 17:45:12, exposure 1/250 s, f/1.8, ISO 200, focal length 6.9 mm (24 mm in
     * 35 mm terms), lens "iPhone 15 Pro back camera", flash did not fire (16), and GPS 18°31'13.548" N,
     * 73°51'24.276" E (= 18.520430, 73.856743).
     */
    public static Path jpegWithCameraExif(Path file, int width, int height) throws IOException {
        return jpegWithCameraExif(file, new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
                "2024:03:09 17:45:12");
    }

    /** Same, with given pixels and date taken ("yyyy:MM:dd HH:mm:ss"). */
    public static Path jpegWithCameraExif(Path file, BufferedImage pixels, String dateTimeOriginal) throws IOException {
        List<Tag> ifd0 = List.of(Tag.ascii(0x010F, "Apple"), Tag.ascii(0x0110, "iPhone 15 Pro"),
                Tag.shortValue(0x0112, 1));
        List<Tag> exif = List.of(
                Tag.rationals(0x829A, 1, 250),           // ExposureTime
                Tag.rationals(0x829D, 18, 10),           // FNumber
                Tag.shortValue(0x8827, 200),             // ISO
                Tag.ascii(0x9003, dateTimeOriginal),
                Tag.shortValue(0x9209, 16),              // Flash: off, did not fire
                Tag.rationals(0x920A, 69, 10),           // FocalLength
                Tag.shortValue(0xA405, 24),              // FocalLengthIn35mmFilm
                Tag.ascii(0xA433, "Apple"),              // LensMake
                Tag.ascii(0xA434, "iPhone 15 Pro back camera"));
        List<Tag> gps = List.of(
                Tag.ascii(0x0001, "N"), Tag.rationals(0x0002, 18, 1, 31, 1, 13548, 1000),
                Tag.ascii(0x0003, "E"), Tag.rationals(0x0004, 73, 1, 51, 1, 24276, 1000));
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(pixels, "jpg", plain);
        byte[] jpeg = plain.toByteArray();
        byte[] tiff = tiff(ifd0, exif, gps);
        byte[] header = ascii("Exif\0\0");
        int length = 2 + header.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(ByteBuffer.allocate(4).put((byte) 0xFF).put((byte) 0xE1).putShort((short) length).array());
        out.write(header);
        out.write(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        Files.write(file, out.toByteArray());
        return file;
    }

    /** One TIFF / EXIF entry: its tag, type (1 byte, 2 ASCII, 3 short, 4 long, 5 rational), count and value bytes. */
    private record Tag(int tag, int type, int count, byte[] value) {
        static Tag ascii(int tag, String text) {
            byte[] bytes = asciiZ(text);
            return new Tag(tag, 2, bytes.length, bytes);
        }

        static Tag shortValue(int tag, int value) {
            return new Tag(tag, 3, 1, ByteBuffer.allocate(2).putShort((short) value).array());
        }

        static Tag longValue(int tag, long value) {
            return new Tag(tag, 4, 1, ByteBuffer.allocate(4).putInt((int) value).array());
        }

        /** Numerator, denominator pairs. */
        static Tag rationals(int tag, long... parts) {
            ByteBuffer bytes = ByteBuffer.allocate(parts.length * 4);
            for (long part : parts) {
                bytes.putInt((int) part);
            }
            return new Tag(tag, 5, parts.length / 2, bytes.array());
        }
    }

    /**
     * A big-endian TIFF block: IFD0 (with pointers to the Exif and GPS IFDs added), then the Exif IFD, then the GPS
     * IFD, each followed by its values that don't fit in an entry's 4 bytes.
     */
    private static byte[] tiff(List<Tag> ifd0, List<Tag> exif, List<Tag> gps) {
        List<Tag> first = new ArrayList<>(ifd0);
        first.add(Tag.longValue(0x8769, 0));
        first.add(Tag.longValue(0x8825, 0));
        int firstAt = 8;
        int exifAt = firstAt + ifdSize(first);
        int gpsAt = exifAt + ifdSize(exif);
        first.set(first.size() - 2, Tag.longValue(0x8769, exifAt));
        first.set(first.size() - 1, Tag.longValue(0x8825, gpsAt));
        ByteBuffer tiff = ByteBuffer.allocate(gpsAt + ifdSize(gps));
        tiff.put(ascii("MM")).putShort((short) 42).putInt(firstAt);
        writeIfd(tiff, first, firstAt);
        writeIfd(tiff, exif, exifAt);
        writeIfd(tiff, gps, gpsAt);
        return tiff.array();
    }

    private static int ifdSize(List<Tag> tags) {
        int size = 2 + tags.size() * 12 + 4;
        for (Tag tag : tags) {
            if (tag.value().length > 4) {
                size += tag.value().length + tag.value().length % 2; // values start on an even offset
            }
        }
        return size;
    }

    private static void writeIfd(ByteBuffer tiff, List<Tag> tags, int at) {
        List<Tag> sorted = tags.stream().sorted(Comparator.comparingInt(Tag::tag)).toList(); // TIFF wants tag order
        int data = at + 2 + sorted.size() * 12 + 4;
        tiff.position(at);
        tiff.putShort((short) sorted.size());
        for (Tag tag : sorted) {
            tiff.putShort((short) tag.tag()).putShort((short) tag.type()).putInt(tag.count());
            if (tag.value().length <= 4) {
                tiff.put(tag.value()).put(new byte[4 - tag.value().length]);
            } else {
                tiff.putInt(data);
                tiff.put(data, tag.value());
                data += tag.value().length + tag.value().length % 2;
            }
        }
        tiff.putInt(0); // no next IFD
    }

    /** The subtitles inside {@link #withTracks}: two lines, at 0.5–1.5 s and 2.0–2.8 s. */
    public static final String SUBTITLES_SRT = """
            1
            00:00:00,500 --> 00:00:01,500
            Hello <i>world</i>

            2
            00:00:02,000 --> 00:00:02,800
            Second line
            """;

    /**
     * A 3-second MKV with one picture, two sound tracks and one SRT subtitle track, made by the bundled
     * {@code ffmpeg} program: sound 1 = 440 Hz, English; sound 2 = 1000 Hz, Hindi, titled "Commentary" and
     * marked as the default track; subtitles {@link #SUBTITLES_SRT}, English.
     */
    public static Path withTracks(Path file) throws IOException, InterruptedException {
        Path srt = file.resolveSibling(file.getFileName() + ".input.srt");
        Files.writeString(srt, SUBTITLES_SRT, StandardCharsets.UTF_8);
        Process ffmpeg = new ProcessBuilder(VideoNatives.ffmpegExecutable().toString(), "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=s=320x240:r=25:d=3",
                "-f", "lavfi", "-i", "sine=f=440:d=3",
                "-f", "lavfi", "-i", "sine=f=1000:d=3",
                "-i", srt.toString(),
                "-map", "0", "-map", "1", "-map", "2", "-map", "3",
                "-c:v", "mpeg4", "-c:a", "aac", "-c:s", "srt",
                "-metadata:s:a:0", "language=eng",
                "-metadata:s:a:1", "language=hin", "-metadata:s:a:1", "title=Commentary",
                "-metadata:s:s:0", "language=eng",
                "-disposition:a:0", "0", "-disposition:a:1", "default",
                file.toString()).redirectErrorStream(true).start();
        String output = new String(ffmpeg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (ffmpeg.waitFor() != 0) {
            throw new IOException("ffmpeg failed: " + output);
        }
        Files.delete(srt);
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
