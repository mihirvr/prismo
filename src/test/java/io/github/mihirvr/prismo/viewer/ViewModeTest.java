package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewModeTest {

    @Test
    void eachExtensionGetsTheRightMode() {
        for (String still : new String[] {"jpg", "JPEG", "png", "webp", "bmp"}) {
            assertEquals(ViewMode.STILL, ViewMode.of(still), still);
        }
        assertEquals(ViewMode.ANIMATED, ViewMode.of("gif"));
        assertEquals(ViewMode.VIDEO, ViewMode.of("mp4"));
        assertEquals(ViewMode.VIDEO, ViewMode.of("M4V"));
        for (String external : new String[] {"mov", "mkv", "webm", "avi", "heic", "nef", "cr2"}) {
            assertEquals(ViewMode.EXTERNAL, ViewMode.of(external), external);
        }
    }

    @Test
    void slideshowShowsOnlyPictures() {
        assertTrue(ViewMode.STILL.inSlideshow());
        assertTrue(ViewMode.ANIMATED.inSlideshow());
        assertFalse(ViewMode.VIDEO.inSlideshow());
        assertFalse(ViewMode.EXTERNAL.inSlideshow());
    }
}
