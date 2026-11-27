package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewModeTest {

    @Test
    void eachExtensionGetsTheRightMode() {
        for (String still : new String[] {"jpg", "JPEG", "png", "webp", "bmp", "jpe", "jfif"}) {
            assertEquals(ViewMode.STILL, ViewMode.of(still), still);
        }
        assertEquals(ViewMode.ANIMATED, ViewMode.of("gif"));
        for (String video : new String[] {"mp4", "M4V", "mov", "mkv", "webm", "avi", "3gp", "mts", "m2ts", "ts",
                "wmv", "flv", "mpg", "mpeg", "ogv"}) {
            assertEquals(ViewMode.VIDEO, ViewMode.of(video), video); // all played in the app (FFmpeg)
        }
        for (String other : new String[] {"heic", "hif", "avif", "nef", "cr2"}) {
            assertEquals(ViewMode.STILL, ViewMode.of(other), other); // through OtherStills since P7
        }
        assertEquals(ViewMode.EXTERNAL, ViewMode.of("xyz"), "a type nothing can show");
    }

    @Test
    void slideshowShowsOnlyPictures() {
        assertTrue(ViewMode.STILL.inSlideshow());
        assertTrue(ViewMode.ANIMATED.inSlideshow());
        assertFalse(ViewMode.VIDEO.inSlideshow());
        assertFalse(ViewMode.EXTERNAL.inSlideshow());
    }
}
