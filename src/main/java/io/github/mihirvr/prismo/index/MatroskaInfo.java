package io.github.mihirvr.prismo.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a Matroska (MKV) or WebM file's length from its header (1.2.0). The metadata library Prismo uses reads MP4 and
 * QuickTime only, so MKV tiles had no length badge. Plain Java, a few KB read, no ffmpeg run.
 *
 * <p>Matroska is EBML: elements of [ID, size, data], IDs and sizes as variable-length numbers whose first byte's
 * leading zero bits give the length. The length is in {@code Segment › Info}: {@code Duration} (a float, in ticks) times
 * {@code TimestampScale} (nanoseconds per tick, 1,000,000 when absent). Info normally comes right after the
 * {@code SeekHead} at the start; if a {@code Cluster} (the video data) comes first, the SeekHead's entry for Info says
 * where it is. A file written as a live stream may have no Duration at all: then the length stays unknown.
 * Specification: the Matroska element list (matroska.org, IETF RFC 9559).
 */
public final class MatroskaInfo {

    private static final Set<String> EXTENSIONS = Set.of("mkv", "webm");

    private static final long EBML = 0x1A45DFA3L;
    private static final long SEGMENT = 0x18538067L;
    private static final long SEEK_HEAD = 0x114D9B74L;
    private static final long SEEK = 0x4DBBL;
    private static final long SEEK_ID = 0x53ABL;
    private static final long SEEK_POSITION = 0x53ACL;
    private static final long INFO = 0x1549A966L;
    private static final long CLUSTER = 0x1F43B675L;
    private static final long TIMESTAMP_SCALE = 0x2AD7B1L;
    private static final long DURATION = 0x4489L;
    private static final long DEFAULT_SCALE = 1_000_000L;
    /** Gives up after this many top-level elements in the Segment, or this far into the file (a damaged file). */
    private static final int MAX_ELEMENTS = 64;
    private static final long MAX_OFFSET = 64L * 1024 * 1024;
    /** A size of all ones: "unknown" (a live stream). */
    private static final long UNKNOWN = -1;

    private MatroskaInfo() {
    }

    /** True for the file types this reads. */
    public static boolean handles(String extension) {
        return extension != null && EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    /** The length in seconds, or empty when the file says none or can't be read. Never throws. */
    public static Optional<Double> durationSeconds(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return durationSeconds(channel);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<Double> durationSeconds(FileChannel channel) throws IOException {
        Reader in = new Reader(channel);
        long size = channel.size();
        // The EBML header first, then the Segment.
        Element header = in.element(0);
        if (header == null || header.id != EBML || header.size == UNKNOWN) {
            return Optional.empty();
        }
        Element segment = in.element(header.end());
        if (segment == null || segment.id != SEGMENT) {
            return Optional.empty();
        }
        long segmentEnd = segment.size == UNKNOWN ? size : Math.min(size, segment.end());
        long infoAt = -1;
        long at = segment.dataStart;
        for (int i = 0; i < MAX_ELEMENTS && at < segmentEnd && at < MAX_OFFSET; i++) {
            Element child = in.element(at);
            if (child == null) {
                break;
            }
            if (child.id == INFO) {
                return info(in, child);
            }
            if (child.id == SEEK_HEAD && child.size != UNKNOWN) {
                long seek = seekPosition(in, child, INFO);
                if (seek >= 0) {
                    infoAt = segment.dataStart + seek;
                }
            }
            if (child.id == CLUSTER || child.size == UNKNOWN) {
                break; // the video data: Info, if anywhere, is where the SeekHead says
            }
            at = child.end();
        }
        if (infoAt >= 0 && infoAt < size) {
            Element info = in.element(infoAt);
            if (info != null && info.id == INFO) {
                return info(in, info);
            }
        }
        return Optional.empty();
    }

    /** Duration × TimestampScale, in seconds. */
    private static Optional<Double> info(Reader in, Element info) throws IOException {
        if (info.size == UNKNOWN) {
            return Optional.empty();
        }
        long scale = DEFAULT_SCALE;
        Double ticks = null;
        long at = info.dataStart;
        while (at < info.end()) {
            Element child = in.element(at);
            if (child == null || child.size == UNKNOWN) {
                break;
            }
            if (child.id == TIMESTAMP_SCALE) {
                scale = in.unsigned(child.dataStart, (int) child.size);
            } else if (child.id == DURATION) {
                ticks = in.floating(child.dataStart, (int) child.size);
            }
            at = child.end();
        }
        if (ticks == null || !(ticks > 0) || scale <= 0) {
            return Optional.empty();
        }
        double seconds = ticks * scale / 1e9;
        return Double.isFinite(seconds) && seconds > 0 ? Optional.of(seconds) : Optional.empty();
    }

    /** The position (from the Segment's data start) the SeekHead gives for element {@code wanted}, or -1. */
    private static long seekPosition(Reader in, Element seekHead, long wanted) throws IOException {
        long at = seekHead.dataStart;
        while (at < seekHead.end()) {
            Element seek = in.element(at);
            if (seek == null || seek.size == UNKNOWN) {
                return -1;
            }
            if (seek.id == SEEK) {
                long id = -1;
                long position = -1;
                long inner = seek.dataStart;
                while (inner < seek.end()) {
                    Element field = in.element(inner);
                    if (field == null || field.size == UNKNOWN) {
                        break;
                    }
                    if (field.id == SEEK_ID) {
                        id = in.unsigned(field.dataStart, (int) field.size);
                    } else if (field.id == SEEK_POSITION) {
                        position = in.unsigned(field.dataStart, (int) field.size);
                    }
                    inner = field.end();
                }
                if (id == wanted && position >= 0) {
                    return position;
                }
            }
            at = seek.end();
        }
        return -1;
    }

    /** One element: its ID, where its data starts, its data size ({@link #UNKNOWN} = unknown). */
    private record Element(long id, long dataStart, long size) {
        long end() {
            return size == UNKNOWN ? Long.MAX_VALUE : dataStart + size;
        }
    }

    /** Positional reads from the file. */
    private static final class Reader {
        private final FileChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocate(16);

        Reader(FileChannel channel) {
            this.channel = channel;
        }

        /** The element starting at {@code at}, or null at the end of the file or on a malformed number. */
        Element element(long at) throws IOException {
            byte[] bytes = read(at, 12);
            if (bytes.length < 2) {
                return null;
            }
            int idLength = length(bytes[0]);
            if (idLength == 0 || idLength > 4 || idLength >= bytes.length) {
                return null;
            }
            long id = 0;
            for (int i = 0; i < idLength; i++) {
                id = id << 8 | (bytes[i] & 0xFF); // an ID keeps its length marker
            }
            int sizeLength = length(bytes[idLength]);
            if (sizeLength == 0 || idLength + sizeLength > bytes.length) {
                return null;
            }
            long size = bytes[idLength] & (0xFF >> sizeLength);
            boolean allOnes = size == (0xFF >> sizeLength);
            for (int i = 1; i < sizeLength; i++) {
                int b = bytes[idLength + i] & 0xFF;
                allOnes &= b == 0xFF;
                size = size << 8 | b;
            }
            return new Element(id, at + idLength + sizeLength, allOnes ? UNKNOWN : size);
        }

        /** A big-endian unsigned number of 1 to 8 bytes. */
        long unsigned(long at, int length) throws IOException {
            if (length < 1 || length > 8) {
                return 0;
            }
            long value = 0;
            for (byte b : read(at, length)) {
                value = value << 8 | (b & 0xFF);
            }
            return value;
        }

        /** A 4- or 8-byte IEEE float; null otherwise. */
        Double floating(long at, int length) throws IOException {
            byte[] bytes = read(at, length);
            if (length == 4 && bytes.length == 4) {
                return (double) ByteBuffer.wrap(bytes).getFloat();
            }
            if (length == 8 && bytes.length == 8) {
                return ByteBuffer.wrap(bytes).getDouble();
            }
            return null;
        }

        /** Up to {@code length} bytes at {@code at} (fewer at the end of the file). */
        private byte[] read(long at, int length) throws IOException {
            buffer.clear().limit(Math.min(length, buffer.capacity()));
            int total = 0;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, at + total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
            byte[] bytes = new byte[total];
            buffer.flip().get(bytes);
            return bytes;
        }

        /** How many bytes a variable-length number takes, from its first byte (leading zeros + 1); 0 if invalid. */
        private static int length(byte first) {
            int b = first & 0xFF;
            for (int i = 0; i < 8; i++) {
                if ((b & (0x80 >> i)) != 0) {
                    return i + 1;
                }
            }
            return 0;
        }
    }
}
