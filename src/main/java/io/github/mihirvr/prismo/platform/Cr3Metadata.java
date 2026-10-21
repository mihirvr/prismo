package io.github.mihirvr.prismo.platform;

import com.drew.lang.ByteArrayReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The EXIF of a Canon CR3 file (P7). A CR3 is an ISO base media file (the MP4 family); metadata-extractor 2.21 reads
 * only its file type, so the date taken, the camera and the exposure were missing.
 *
 * <p>Canon keeps the EXIF in small boxes near the start of the file, each holding a plain TIFF block: {@code CMT1} =
 * IFD0 (make, model, orientation), {@code CMT2} = the Exif IFD (date taken, exposure, lens), {@code CMT4} = GPS. Each
 * block is read with metadata-extractor's own TIFF reader, which files the first IFD of a block as IFD0, so the tags of
 * CMT2 and CMT4 are moved to an Exif SubIFD and a GPS directory, where the rest of Prismo looks for them.
 */
final class Cr3Metadata {

    /** The CMT boxes sit in the {@code moov} box, a few KB into the file; 1 MB is far more than needed. */
    static final int HEAD_BYTES = 1 << 20;

    private Cr3Metadata() {
    }

    /** Adds the directories found in {@code head} (the start of a CR3 file) to {@code metadata}. */
    static void addTo(Metadata metadata, byte[] head) {
        byte[] ifd0 = box(head, "CMT1");
        if (ifd0 != null) {
            new ExifReader().extract(new ByteArrayReader(ifd0), metadata);
        }
        moveFirstIfd(box(head, "CMT2"), metadata, new ExifSubIFDDirectory());
        moveFirstIfd(box(head, "CMT4"), metadata, new GpsDirectory());
    }

    /** The TIFF block inside the first box of {@code type} ("II*\0" or "MM\0*" after the 8-byte box header), or null. */
    static byte[] box(byte[] data, String type) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        for (int i = 4; i + 8 <= data.length; i++) {
            if (data[i] != name[0] || data[i + 1] != name[1] || data[i + 2] != name[2] || data[i + 3] != name[3]) {
                continue;
            }
            int start = i - 4;
            long size = ((data[start] & 0xFFL) << 24) | ((data[start + 1] & 0xFF) << 16)
                    | ((data[start + 2] & 0xFF) << 8) | (data[start + 3] & 0xFF);
            if (size < 16 || start + size > data.length || !isTiff(data, i + 4)) {
                continue; // the four letters by chance, not a box
            }
            return Arrays.copyOfRange(data, i + 4, (int) (start + size));
        }
        return null;
    }

    private static boolean isTiff(byte[] d, int at) {
        return (d[at] == 'I' && d[at + 1] == 'I' && d[at + 2] == 42 && d[at + 3] == 0)
                || (d[at] == 'M' && d[at + 1] == 'M' && d[at + 2] == 0 && d[at + 3] == 42);
    }

    private static void moveFirstIfd(byte[] tiff, Metadata metadata, Directory target) {
        if (tiff == null) {
            return;
        }
        Metadata block = new Metadata();
        new ExifReader().extract(new ByteArrayReader(tiff), block);
        ExifIFD0Directory first = block.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (first == null) {
            return;
        }
        for (Tag tag : first.getTags()) {
            target.setObject(tag.getTagType(), first.getObject(tag.getTagType()));
        }
        if (target.getTagCount() > 0) {
            metadata.addDirectory(target);
        }
    }
}
