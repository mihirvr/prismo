package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.platform.ExternalProgram;
import io.github.mihirvr.prismo.video.VideoNatives;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The MKV / WebM length reader (1.2.0): hand-built headers, and a real MKV from the bundled ffmpeg. */
class MatroskaInfoTest {

    @TempDir
    Path tmp;

    @Test
    void readsDurationTimesTheTimestampScale() throws Exception {
        byte[] info = element(0x1549A966, concat(element(0x2AD7B1, bytes(0x0F, 0x42, 0x40)), // 1,000,000 ns per tick
                element(0x4489, ByteBuffer.allocate(8).putDouble(3_169_500.0).array()))); // 52:49.5 in ms
        Path file = write(concat(ebmlHeader(), segmentUnknownSize(info)));
        assertEquals(3169.5, MatroskaInfo.durationSeconds(file).orElseThrow(), 1e-6);
    }

    @Test
    void aFourByteDurationAndTheDefaultScaleWork() throws Exception {
        byte[] info = element(0x1549A966, element(0x4489, ByteBuffer.allocate(4).putFloat(90_000f).array()));
        Path file = write(concat(ebmlHeader(), segmentUnknownSize(concat(element(0xEC, new byte[5]), info))));
        assertEquals(90.0, MatroskaInfo.durationSeconds(file).orElseThrow(), 1e-6, "a Void element first is skipped");
    }

    @Test
    void infoAfterTheVideoDataIsFoundThroughTheSeekHead() throws Exception {
        byte[] info = element(0x1549A966, element(0x4489, ByteBuffer.allocate(8).putDouble(5_000.0).array()));
        byte[] cluster = element(0x1F43B675, new byte[20]);
        // SeekHead with one Seek: Info at (seekHead + cluster) bytes from the Segment's data start.
        byte[] seekHeadDraft = seekHead(0);
        long infoPosition = seekHeadDraft.length + cluster.length;
        byte[] seekHead = seekHead(infoPosition);
        assertEquals(seekHeadDraft.length, seekHead.length, "same size, so the position stays right");
        byte[] segment = segmentUnknownSize(concat(seekHead, cluster, info));
        Path file = write(concat(ebmlHeader(), segment));
        assertEquals(5.0, MatroskaInfo.durationSeconds(file).orElseThrow(), 1e-6);
    }

    @Test
    void noDurationOrNotMatroskaGivesNothing() throws Exception {
        byte[] info = element(0x1549A966, element(0x2AD7B1, bytes(0x0F, 0x42, 0x40)));
        assertEquals(Optional.empty(), MatroskaInfo.durationSeconds(write(concat(ebmlHeader(), segmentUnknownSize(info)))),
                "a live stream without Duration");
        assertEquals(Optional.empty(), MatroskaInfo.durationSeconds(write(bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p'))),
                "an MP4");
        assertEquals(Optional.empty(), MatroskaInfo.durationSeconds(write(new byte[0])), "empty");
        assertEquals(Optional.empty(), MatroskaInfo.durationSeconds(tmp.resolve("gone.mkv")), "missing");
        assertTrue(MatroskaInfo.handles("MKV"));
        assertTrue(MatroskaInfo.handles("webm"));
        assertFalse(MatroskaInfo.handles("mp4"));
    }

    @Test
    void aRealMkvFromFfmpeg() throws Exception {
        Path video = tmp.resolve("clip.mkv");
        ExternalProgram.run(List.of(VideoNatives.ffmpegExecutable().toString(), "-nostdin", "-hide_banner",
                "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=10:duration=7",
                "-c:v", "mpeg4", video.toString()), 60);
        assertTrue(Files.size(video) > 0);
        assertEquals(7.0, MatroskaInfo.durationSeconds(video).orElseThrow(), 0.2);
    }

    // --- Building EBML by hand ---

    private Path write(byte[] content) throws Exception {
        Path file = Files.createTempFile(tmp, "test", ".mkv");
        Files.write(file, content);
        return file;
    }

    private static byte[] ebmlHeader() {
        return element(0x1A45DFA3, element(0x4282, "matroska".getBytes())); // DocType
    }

    private static byte[] segmentUnknownSize(byte[] data) {
        return concat(bytes(0x18, 0x53, 0x80, 0x67), bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), data);
    }

    /** A SeekHead with one Seek pointing at Info, the position written in 4 bytes (so its size never changes). */
    private static byte[] seekHead(long infoPosition) {
        byte[] position = ByteBuffer.allocate(4).putInt((int) infoPosition).array();
        byte[] seek = element(0x4DBB, concat(element(0x53AB, bytes(0x15, 0x49, 0xA9, 0x66)), element(0x53AC, position)));
        return element(0x114D9B74, seek);
    }

    /** ID (its own bytes, marker included) + an 8-byte size + data. */
    private static byte[] element(long id, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int idLength = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
        for (int i = idLength - 1; i >= 0; i--) {
            out.write((int) (id >> (8 * i)) & 0xFF);
        }
        out.write(0x01); // an 8-byte size: marker 0x01, then 7 bytes
        for (int i = 6; i >= 0; i--) {
            out.write((int) ((long) data.length >> (8 * i)) & 0xFF);
        }
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
