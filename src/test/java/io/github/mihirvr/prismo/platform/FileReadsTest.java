package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drew.metadata.exif.ExifIFD0Directory;
import io.github.mihirvr.prismo.testing.TestMedia;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A read bug can spin forever (seen in a mutation check) without ever checking for interrupts, so the time
 * limit runs the test in a separate thread: it fails after 10 s even if the stuck thread never stops.
 */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FileReadsTest {

    @TempDir
    Path tmp;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFileBeingReadCanStillBeRenamedAndDeleted() throws Exception {
        Path file = Files.write(tmp.resolve("a.jpg"), new byte[1000]);
        try (ImageInputStream in = FileReads.image(file)) {
            in.read();
            Path renamed = Files.move(file, tmp.resolve("b.jpg"));
            Files.delete(renamed);
            assertTrue(Files.notExists(renamed));
        }

        // Why this class exists: the java.io way refuses both while the file is open.
        Path locked = Files.write(tmp.resolve("c.jpg"), new byte[1000]);
        try (RandomAccessFile old = new RandomAccessFile(locked.toFile(), "r")) {
            assertThrows(IOException.class, () -> Files.delete(locked));
            assertTrue(old.length() > 0);
        }
    }

    @Test
    void everyByteMatchesTheFileAcrossBufferEdgesAndSeeks() throws Exception {
        byte[] data = new byte[200_000]; // three 64 KiB buffers and a bit
        new Random(7).nextBytes(data);
        Path file = Files.write(tmp.resolve("data.bin"), data);
        try (ImageInputStream in = FileReads.image(file)) {
            assertEquals(data.length, in.length());

            byte[] all = new byte[data.length];
            in.readFully(all);
            assertArrayEquals(data, all, "sequential read through every buffer refill");
            assertEquals(-1, in.read(), "end of file");

            for (long position : new long[] {0, 65_535, 65_536, 65_537, 131_000, 199_999, 10}) {
                in.seek(position);
                assertEquals(data[(int) position] & 0xff, in.read(), "byte at " + position);
            }
            in.seek(65_530); // a read that straddles the end of one buffer
            byte[] piece = new byte[20];
            in.readFully(piece);
            assertArrayEquals(Arrays.copyOfRange(data, 65_530, 65_550), piece);

            in.seek(100);
            in.mark();
            in.skipBytes(70_000);
            in.reset();
            assertEquals(100, in.getStreamPosition());
            assertEquals(data[100] & 0xff, in.read());
            in.seek(3);
            assertEquals((data[3] & 0xff) >>> 7, in.readBit(), "bit reads come from ImageInputStreamImpl");
            assertEquals(1, in.getBitOffset());
            assertEquals(data[3] & 0xff, in.read(), "a byte read after a bit read gives the whole byte");
            assertEquals(0, in.getBitOffset(), "and starts the next bit read at the next byte's first bit");
        }
    }

    @Test
    void imagesDecodeToTheSamePixelsAsBefore() throws Exception {
        BufferedImage pixels = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(3);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 300; x++) {
                pixels.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        Path jpeg = TestMedia.jpegWithExif(tmp.resolve("p.jpg"), pixels, "Canon", "EOS R6", 6, "2024:01:01 10:00:00");
        Path png = tmp.resolve("p.png");
        ImageIO.write(pixels, "png", png.toFile());

        for (Path file : new Path[] {jpeg, png}) {
            BufferedImage before = ImageIO.read(file.toFile());
            BufferedImage after;
            try (ImageInputStream in = FileReads.image(file)) {
                ImageReader reader = ImageIO.getImageReaders(in).next();
                reader.setInput(in, true, true);
                after = reader.read(0);
                reader.dispose();
            }
            assertEquals(before.getWidth(), after.getWidth());
            assertArrayEquals(before.getRGB(0, 0, 300, 200, null, 0, 300), after.getRGB(0, 0, 300, 200, null, 0, 300),
                    file.getFileName().toString());
        }
    }

    @Test
    void metadataIsReadAsBefore() throws Exception {
        Path jpeg = TestMedia.jpegWithExif(tmp.resolve("m.jpg"), 40, 30, "Canon", "EOS R6", 6, "2023:07:14 18:30:05");

        ExifIFD0Directory ifd0 = FileReads.metadata(jpeg).getFirstDirectoryOfType(ExifIFD0Directory.class);

        assertEquals("Canon", ifd0.getString(ExifIFD0Directory.TAG_MAKE));
        assertEquals(6, ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION));
    }
}
