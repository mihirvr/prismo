package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeekPreviewsTest {

    @Test
    void aboutOnePicturePerSecondUpToAHundred() {
        assertEquals(1, SeekPreviews.slots(0.2), "a very short video still gets one");
        assertEquals(12, SeekPreviews.slots(12));
        assertEquals(100, SeekPreviews.slots(100));
        assertEquals(100, SeekPreviews.slots(3500), "an hour-long video: 100, one every 35 s");
    }

    @Test
    void eachTimeFallsInItsSlot() {
        assertEquals(0, SeekPreviews.slotOf(0, 600, 100));
        assertEquals(50, SeekPreviews.slotOf(300, 600, 100));
        assertEquals(99, SeekPreviews.slotOf(600, 600, 100), "the very end is the last slot");
        assertEquals(0, SeekPreviews.slotOf(-5, 600, 100), "never out of range");
        assertEquals(0, SeekPreviews.slotOf(10, 0, 1), "no length: slot 0");
    }

    @Test
    void aSlotsPictureIsTakenInItsMiddle() {
        assertEquals(3, SeekPreviews.timeOf(0, 600, 100), 1e-9);
        assertEquals(597, SeekPreviews.timeOf(99, 600, 100), 1e-9);
        for (int slot = 0; slot < 100; slot++) {
            assertEquals(slot, SeekPreviews.slotOf(SeekPreviews.timeOf(slot, 3500, 100), 3500, 100));
        }
    }

    @Test
    void orderVisitsEverySlotOnceCoarseFirst() {
        for (int slots : new int[] {1, 2, 3, 7, 12, 64, 99, 100}) {
            List<Integer> order = SeekPreviews.order(slots);
            assertEquals(slots, order.size(), slots + " slots");
            assertEquals(slots, new HashSet<>(order).size(), "no slot twice for " + slots);
        }
        List<Integer> order = SeekPreviews.order(100);
        assertEquals(50, order.get(0), "the middle first");
        assertEquals(List.of(25, 75), order.subList(1, 3), "then the quarters");
        // After the first 8 the whole bar has a picture within 1/8 of its length.
        List<Integer> first = order.subList(0, 8);
        for (int slot = 0; slot < 100; slot++) {
            int at = slot;
            int nearest = first.stream().mapToInt(s -> Math.abs(s - at)).min().orElseThrow();
            assertTrue(nearest <= 13, "slot " + slot + " is " + nearest + " slots from a picture");
        }
    }

    @Test
    void noPicturesForNoVideo() {
        assertNull(SeekPreviews.none().at(5));
    }

    @Test
    void commandReadsLittleAndDecodesOnlyKeyFrames() {
        List<String> command = SeekPreviews.command(Path.of("ffmpeg"), Path.of("clip.mkv"), Path.of("out.jpg"), 75.5);
        int input = command.indexOf("-i");
        assertTrue(command.indexOf("-skip_frame") < input && command.get(command.indexOf("-skip_frame") + 1)
                .equals("nokey"), "key frames only (an input option)");
        assertTrue(command.indexOf("-ss") < input, "seeks before reading (an input option)");
        assertEquals("75.500", command.get(command.indexOf("-ss") + 1));
        assertEquals("0:V:0", command.get(command.indexOf("-map") + 1), "the real video, never a cover picture");
        assertEquals("scale=" + SeekPreviews.WIDTH + ":-2", command.get(command.indexOf("-vf") + 1));
        assertEquals("1", command.get(command.indexOf("-frames:v") + 1));
        assertEquals("out.jpg", command.getLast());
    }
}
