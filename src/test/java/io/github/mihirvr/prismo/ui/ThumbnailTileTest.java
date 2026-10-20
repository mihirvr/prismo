package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ThumbnailTileTest {

    @Test
    void videoLengthsReadLikeAPlayersClock() {
        assertEquals("0:01", ThumbnailTile.lengthText(0.4), "a very short clip still shows a length");
        assertEquals("0:07", ThumbnailTile.lengthText(6.6), "rounded to the nearest second");
        assertEquals("1:05", ThumbnailTile.lengthText(65));
        assertEquals("12:30", ThumbnailTile.lengthText(750));
        assertEquals("59:59", ThumbnailTile.lengthText(3599));
        assertEquals("1:00:00", ThumbnailTile.lengthText(3600));
        assertEquals("1:02:03", ThumbnailTile.lengthText(3723));
    }

    @Test
    void typesWithoutThumbnailsShowTheirExtension() {
        assertEquals("HEIC", ThumbnailTile.unsupportedText("heic"));
        assertEquals("NEF", ThumbnailTile.unsupportedText("nef"));
    }
}
