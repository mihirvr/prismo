package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import io.github.mihirvr.prismo.index.MediaRecord;
import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.index.MetadataReader;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Cr3MetadataTest {

    @TempDir
    Path tmp;

    @Test
    void readsCameraAndDateFromTheCmtBoxes() {
        byte[] head = cr3Head();

        Metadata metadata = new Metadata();
        Cr3Metadata.addTo(metadata, head);

        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertNotNull(ifd0);
        assertEquals("Canon", ifd0.getString(ExifDirectoryBase.TAG_MAKE));
        assertEquals("Canon EOS M200", ifd0.getString(ExifDirectoryBase.TAG_MODEL));
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertNotNull(exif, "CMT2's tags are moved to an Exif SubIFD, where the rest of Prismo looks");
        assertEquals("2019:10:26 14:18:06", exif.getString(ExifDirectoryBase.TAG_DATETIME_ORIGINAL));
    }

    @Test
    void theIndexGetsTheDateAndCameraOfARealFileLayout() throws Exception {
        // A fake CR3: the ftyp box metadata-extractor recognises, then the head with the CMT boxes.
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(box("ftyp", "crx \0\0\0\1crx isom".getBytes(StandardCharsets.US_ASCII)));
        file.writeBytes(cr3Head());
        Path cr3 = Files.write(tmp.resolve("_MG_2231.CR3"), file.toByteArray());

        MediaRecord record = new MetadataReader(ZoneId.of("UTC")).read(cr3, MediaType.RAW, Files.size(cr3), 0);

        assertEquals(LocalDateTime.parse("2019-10-26T14:18:06").toInstant(ZoneOffset.UTC).toEpochMilli(),
                record.dateTaken());
        assertEquals("Canon", record.cameraMake());
        assertEquals("Canon EOS M200", record.cameraModel());
    }

    @Test
    void fourLettersThatAreNotABoxAreSkipped() {
        // "CMT1" inside other data, with no sensible size before it and no TIFF block after it.
        byte[] data = "xxxxCMT1notatiffatall-----------".getBytes(StandardCharsets.US_ASCII);

        assertNull(Cr3Metadata.box(data, "CMT1"));
        java.nio.ByteBuffer notTiff = java.nio.ByteBuffer.allocate(40).putInt(40).put("CMT2".getBytes(StandardCharsets.US_ASCII))
                .put("JPEG data, not a TIFF block....".getBytes(StandardCharsets.US_ASCII));
        assertNull(Cr3Metadata.box(notTiff.array(), "CMT2"), "the right size, but no TIFF block inside");
        Metadata metadata = new Metadata();
        Cr3Metadata.addTo(metadata, data);
        assertEquals(0, metadata.getDirectoryCount());
    }

    /** CMT1 (IFD0: make, model) and CMT2 (Exif IFD: date taken), as Canon writes them. */
    private static byte[] cr3Head() {
        Map<Integer, String> ifd0 = new LinkedHashMap<>();
        ifd0.put(ExifDirectoryBase.TAG_MAKE, "Canon");
        ifd0.put(ExifDirectoryBase.TAG_MODEL, "Canon EOS M200");
        Map<Integer, String> exif = Map.of(ExifDirectoryBase.TAG_DATETIME_ORIGINAL, "2019:10:26 14:18:06");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(box("CMT1", tiff(ifd0)));
        out.writeBytes(box("CMT2", tiff(exif)));
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] content) {
        ByteBuffer box = ByteBuffer.allocate(8 + content.length);
        box.putInt(8 + content.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(content);
        return box.array();
    }

    /** A little-endian TIFF block with one IFD of ASCII tags (tags in increasing order). */
    private static byte[] tiff(Map<Integer, String> tags) {
        int count = tags.size();
        int dataStart = 8 + 2 + count * 12 + 4;
        ByteArrayOutputStream values = new ByteArrayOutputStream();
        ByteBuffer ifd = ByteBuffer.allocate(dataStart).order(ByteOrder.LITTLE_ENDIAN);
        ifd.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) count);
        tags.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(tag -> {
            byte[] text = (tag.getValue() + "\0").getBytes(StandardCharsets.US_ASCII);
            ifd.putShort(tag.getKey().shortValue()).putShort((short) 2).putInt(text.length)
                    .putInt(dataStart + values.size());
            values.writeBytes(text);
        });
        ifd.putInt(0); // no next IFD
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(ifd.array());
        all.writeBytes(values.toByteArray());
        return all.toByteArray();
    }
}
