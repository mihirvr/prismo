package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaTypeTest {

    @Test
    void mapsExtensionsCaseInsensitively() {
        assertEquals(Optional.of(MediaType.PHOTO), MediaType.fromExtension("JPG"));
        assertEquals(Optional.of(MediaType.PHOTO), MediaType.fromExtension("heic"));
        assertEquals(Optional.of(MediaType.GIF), MediaType.fromExtension("gif"));
        assertEquals(Optional.of(MediaType.VIDEO), MediaType.fromExtension("MOV"));
        assertEquals(Optional.of(MediaType.RAW), MediaType.fromExtension("nef"));
        assertEquals(Optional.empty(), MediaType.fromExtension("txt"));
        assertEquals(Optional.empty(), MediaType.fromExtension(""));
    }

    @Test
    void knowsTheTypesAddedIn150() {
        for (String photo : new String[] {"jpe", "jfif", "avif", "hif"}) {
            assertEquals(Optional.of(MediaType.PHOTO), MediaType.fromExtension(photo), photo);
        }
        for (String video : new String[] {"3gp", "mts", "m2ts", "ts", "wmv", "flv", "mpg", "mpeg", "ogv"}) {
            assertEquals(Optional.of(MediaType.VIDEO), MediaType.fromExtension(video), video);
        }
    }

    /** .ts is also TypeScript: only a file that starts like an MPEG transport stream is a video. */
    @Test
    void aTsFileCountsOnlyIfItIsATransportStream(@TempDir Path dir) throws Exception {
        byte[] stream = new byte[188 * 4];
        byte[] blueRay = new byte[192 * 4];
        for (int i = 0; i < 4; i++) {
            stream[i * 188] = 0x47;
            blueRay[4 + i * 192] = 0x47;
        }
        Path video = Files.write(dir.resolve("show.ts"), stream);
        Path m2tsStyle = Files.write(dir.resolve("disc.TS"), blueRay);
        Path code = Files.writeString(dir.resolve("index.ts"), "import { x } from './x';\n".repeat(40));
        Path tiny = Files.write(dir.resolve("tiny.ts"), new byte[] {0x47});
        Path mts = Files.writeString(dir.resolve("clip.mts"), "not checked");

        assertEquals(Optional.of(MediaType.VIDEO), MediaType.ofFile(video));
        assertEquals(Optional.of(MediaType.VIDEO), MediaType.ofFile(m2tsStyle));
        assertEquals(Optional.empty(), MediaType.ofFile(code));
        assertEquals(Optional.empty(), MediaType.ofFile(tiny));
        assertEquals(Optional.empty(), MediaType.ofFile(dir.resolve("missing.ts")));
        assertEquals(Optional.of(MediaType.VIDEO), MediaType.ofFile(mts), "only .ts is checked by content");
        assertEquals(Optional.empty(), MediaType.ofFile(dir.resolve("notes.txt")));
    }

    @Test
    void extractsExtension() {
        assertEquals("jpg", MediaType.extensionOf("IMG_0001.JPG"));
        assertEquals("gz", MediaType.extensionOf("archive.tar.gz"));
        assertEquals("", MediaType.extensionOf("README"));
        assertEquals("", MediaType.extensionOf(".hidden"));
    }
}
