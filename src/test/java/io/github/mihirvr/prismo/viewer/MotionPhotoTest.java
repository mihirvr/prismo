package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MotionPhotoTest {

    @TempDir
    Path tmp;

    /** The start of an MP4: a 24-byte ftyp box, then some "video". */
    private static final byte[] MP4 = concat(new byte[] {0, 0, 0, 24}, ascii("ftypmp42"), new byte[12],
            ascii("...the clip..."));

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** A "JPEG": start marker, an XMP text, some picture bytes, end marker. */
    private static byte[] jpeg(String xmp) {
        return concat(new byte[] {(byte) 0xFF, (byte) 0xD8}, ascii(xmp), new byte[300], new byte[] {(byte) 0xFF,
            (byte) 0xD9});
    }

    @Test
    void googleContainerItems() {
        String xmp = "<x:xmpmeta><rdf:Description GCamera:MotionPhoto=\"1\"><Container:Directory><rdf:Seq>"
                + "<rdf:li><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\"/></rdf:li>"
                + "<rdf:li><Container:Item Item:Length=\"" + MP4.length + "\" Item:Mime=\"video/mp4\""
                + " Item:Semantic=\"MotionPhoto\"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description>";
        byte[] photo = jpeg(xmp);
        byte[] file = concat(photo, MP4);
        assertEquals(photo.length, MotionPhoto.videoStart(file));
    }

    @Test
    void olderGoogleMicroVideo() {
        byte[] photo = jpeg("<rdf:Description GCamera:MicroVideo=\"1\" GCamera:MicroVideoOffset=\"" + MP4.length
                + "\"/>");
        assertEquals(photo.length, MotionPhoto.videoStart(concat(photo, MP4)));
    }

    @Test
    void samsungMarker() {
        byte[] photo = jpeg("no xmp here");
        byte[] file = concat(photo, ascii("MotionPhoto_Data"), MP4);
        assertEquals(photo.length + 16, MotionPhoto.videoStart(file));
    }

    @Test
    void aPlainPhotoOrAWrongOffsetHasNone() {
        assertEquals(-1, MotionPhoto.videoStart(jpeg("just a photo")));
        // The tag says so, but no MP4 starts there: not trusted.
        byte[] photo = jpeg("<rdf:Description GCamera:MicroVideoOffset=\"40\"/>");
        assertEquals(-1, MotionPhoto.videoStart(concat(photo, new byte[60])));
        assertFalse(MotionPhoto.isMp4At(MP4, 0), "0 is the picture's own start");
        assertTrue(MotionPhoto.isMp4At(concat(new byte[1], MP4), 1));
    }

    @Test
    void onlyJpegs() {
        assertTrue(MotionPhoto.mayHaveOne("JPG"));
        assertTrue(MotionPhoto.mayHaveOne("jpeg"));
        assertFalse(MotionPhoto.mayHaveOne("png"));
        assertFalse(MotionPhoto.mayHaveOne("mp4"));
    }

    @Test
    void theClipIsCopiedOutAndThePhotoIsLeftAsItWas() throws Exception {
        byte[] photo = jpeg("x");
        byte[] file = concat(photo, ascii("MotionPhoto_Data"), MP4);
        Path original = tmp.resolve("PXL_1.jpg");
        Files.write(original, file);
        Path clip = MotionPhoto.extract(original, photo.length + 16, tmp.resolve("cache"));
        assertArrayEquals(MP4, Files.readAllBytes(clip));
        assertArrayEquals(file, Files.readAllBytes(original), "the photo is only read");
        assertEquals(clip, MotionPhoto.extract(original, photo.length + 16, tmp.resolve("cache")), "made once");
        assertTrue(Arrays.equals(MP4, Files.readAllBytes(clip)));
    }
}
