package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

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
    void extractsExtension() {
        assertEquals("jpg", MediaType.extensionOf("IMG_0001.JPG"));
        assertEquals("gz", MediaType.extensionOf("archive.tar.gz"));
        assertEquals("", MediaType.extensionOf("README"));
        assertEquals("", MediaType.extensionOf(".hidden"));
    }
}
