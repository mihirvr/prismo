package io.github.mihirvr.prismo.thumbs;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.platform.ExternalProgram;
import io.github.mihirvr.prismo.video.VideoNatives;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Photos ImageIO can't read itself (P7, rows 9 and 10), turned into a picture for thumbnails and the viewer.
 *
 * <ul>
 *   <li><b>HEIC / HEIF</b> (iPhones, many Android phones): decoded by the bundled {@code ffmpeg} program in a separate
 *       process, like video thumbnails (a crash in a native decoder ends only that run). The HEVC decoder and the HEIF
 *       reading are part of the LGPL FFmpeg build Prismo already ships, so nothing new is bundled. ffmpeg turns the
 *       picture upright itself (the HEIF {@code irot} box), so no EXIF rotation is applied on top.</li>
 *   <li><b>RAW</b> (CR2, CR3, NEF, ARW, DNG, ORF, RW2, RAF …): not developed from the sensor data (that needs a RAW
 *       converter like LibRaw, a big native library); instead the JPEG preview every camera stores inside the file is
 *       used: the largest one, usually full size or close to it. It is found by walking JPEG markers, so it works the
 *       same for every maker's container (TIFF-based, ISO media, Fuji's own). Its rotation comes from the RAW file's
 *       own EXIF.</li>
 * </ul>
 * Safe on any thread; disk and process work, so never on the UI thread.
 */
public final class OtherStills {

    private static final Set<String> HEIF = Set.of("heic", "heif", "hif");
    private static final long TIMEOUT_SECONDS = 60;
    /** RAW files bigger than this aren't read for a preview (a whole file is read into memory). */
    static final long MAX_RAW_BYTES = 300L * 1024 * 1024;

    /** Finds the {@code ffmpeg} program (tests pass their own). */
    @FunctionalInterface
    public interface Program {
        Path path() throws IOException;
    }

    private OtherStills() {
    }

    /** True for the extensions handled here (not by ImageIO): HEIF and RAW. */
    public static boolean handles(String extension) {
        String ext = extension.toLowerCase(Locale.ROOT);
        return HEIF.contains(ext) || MediaType.fromExtension(ext).orElse(null) == MediaType.RAW;
    }

    static boolean isHeif(String extension) {
        return HEIF.contains(extension.toLowerCase(Locale.ROOT));
    }

    /** The picture of {@code file}, the right way up for HEIF (see {@link #needsExifRotation}). */
    public static BufferedImage decode(Path file) throws IOException {
        return decode(file, VideoNatives::ffmpegExecutable);
    }

    static BufferedImage decode(Path file, Program ffmpeg) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }
        String ext = MediaType.extensionOf(file.getFileName().toString());
        byte[] picture = isHeif(ext) ? heifAsPng(file, ffmpeg) : largestJpeg(file);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(picture));
        if (image == null) {
            throw new IOException("Prismo can't read the picture inside " + file.getFileName());
        }
        return image;
    }

    /** False for HEIF (ffmpeg already turned it upright); true for RAW previews (the RAW's EXIF says how). */
    public static boolean needsExifRotation(String extension) {
        return !isHeif(extension);
    }

    // --- HEIF ---

    /** {@code ffmpeg} writes the picture to a temporary PNG file, which is read back and deleted. */
    private static byte[] heifAsPng(Path file, Program ffmpeg) throws IOException {
        Path out = Files.createTempFile("prismo-heif-", ".png");
        try {
            String error = ExternalProgram.run(List.of(ffmpeg.path().toString(), "-nostdin", "-hide_banner",
                    "-loglevel", "error", "-threads", "1", "-i", file.toString(), "-frames:v", "1", "-an",
                    "-f", "image2", "-c:v", "png", "-y", out.toString()), TIMEOUT_SECONDS);
            if (Files.size(out) == 0) {
                throw new IOException("ffmpeg made no picture" + (error.isBlank() ? "" : ": " + error.strip()));
            }
            return Files.readAllBytes(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    // --- RAW: the embedded JPEG preview ---

    /** The largest baseline or progressive JPEG stored inside {@code file} (by pixel count). */
    static byte[] largestJpeg(Path file) throws IOException {
        if (Files.size(file) > MAX_RAW_BYTES) {
            throw new IOException("too big to look for a preview (" + Files.size(file) / (1024 * 1024) + " MB)");
        }
        byte[] data;
        try (InputStream in = Files.newInputStream(file)) { // a plain stream: the file is never locked
            data = in.readAllBytes();
        }
        return largestJpeg(data).orElseThrow(() -> new IOException("no preview picture inside "
                + file.getFileName()));
    }

    static Optional<byte[]> largestJpeg(byte[] data) {
        List<int[]> found = new ArrayList<>(); // {start, end, pixels}
        int i = 0;
        while (i + 3 < data.length) {
            if ((data[i] & 0xff) == 0xFF && (data[i + 1] & 0xff) == 0xD8 && (data[i + 2] & 0xff) == 0xFF) {
                int[] jpeg = jpegAt(data, i);
                if (jpeg != null) {
                    found.add(jpeg);
                    i = jpeg[1]; // a small thumbnail inside its APP1 block is part of this one: skip it
                    continue;
                }
            }
            i++;
        }
        return found.stream().max((a, b) -> Long.compare(a[2], b[2]))
                .map(jpeg -> Arrays.copyOfRange(data, jpeg[0], jpeg[1]));
    }

    /**
     * Walks the JPEG starting at {@code start} marker by marker: {start, end (after EOI), width × height}, or null if
     * it isn't a whole baseline / progressive JPEG (RAW sensor data is often lossless JPEG, SOF3, which ImageIO can't
     * read: skipped).
     */
    static int[] jpegAt(byte[] d, int start) {
        int pos = start + 2;
        long pixels = -1;
        while (pos + 1 < d.length) {
            if ((d[pos] & 0xff) != 0xFF) {
                return null;
            }
            int marker = d[pos + 1] & 0xff;
            if (marker == 0xFF) {
                pos++; // fill byte
                continue;
            }
            if (marker == 0xD9) { // end of image
                return pixels > 0 ? new int[] {start, pos + 2, (int) Math.min(Integer.MAX_VALUE, pixels)} : null;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
                continue;
            }
            if (pos + 3 >= d.length) {
                return null;
            }
            int length = ((d[pos + 2] & 0xff) << 8) | (d[pos + 3] & 0xff);
            if (length < 2 || pos + 2 + length > d.length) {
                return null;
            }
            boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (frame) {
                if (marker != 0xC0 && marker != 0xC1 && marker != 0xC2 || length < 8) {
                    return null; // lossless, hierarchical or arithmetic: not a preview ImageIO can show
                }
                int height = ((d[pos + 5] & 0xff) << 8) | (d[pos + 6] & 0xff);
                int width = ((d[pos + 7] & 0xff) << 8) | (d[pos + 8] & 0xff);
                pixels = (long) width * height;
            }
            pos += 2 + length;
            if (marker == 0xDA) { // start of scan: entropy-coded data up to the next real marker
                while (pos + 1 < d.length) {
                    if ((d[pos] & 0xff) == 0xFF) {
                        int next = d[pos + 1] & 0xff;
                        if (next != 0x00 && !(next >= 0xD0 && next <= 0xD7)) {
                            break;
                        }
                    }
                    pos++;
                }
            }
        }
        return null;
    }
}
