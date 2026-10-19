package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import io.github.mihirvr.prismo.ui.TimelineItem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlideshowTest {

    private static List<TimelineItem> items(String... extensions) {
        List<TimelineItem> items = new ArrayList<>();
        for (int i = 0; i < extensions.length; i++) {
            items.add(new TimelineItem(new MediaRef(i, Path.of(i + "." + extensions[i]), extensions[i], 0), 0, 0, 0,
                    false, false, false));
        }
        return items;
    }

    @Test
    void stepsThroughPicturesSkippingVideosAndUnshowableFiles() {
        //                                  0      1      2      3      4      5      6
        List<TimelineItem> items = items("jpg", "mp4", "mov", "heic", "gif", "png", "mkv");

        assertEquals(4, Slideshow.next(items, 0)); // skips mp4, mov, heic
        assertEquals(5, Slideshow.next(items, 4));
        assertEquals(0, Slideshow.next(items, -1)); // starting before the first
    }

    @Test
    void stopsAtTheEnd() {
        List<TimelineItem> items = items("jpg", "png", "mp4");

        assertEquals(-1, Slideshow.next(items, 1)); // only a video left
        assertEquals(-1, Slideshow.next(items, 2));
        assertEquals(-1, Slideshow.next(List.of(), -1));
    }
}
