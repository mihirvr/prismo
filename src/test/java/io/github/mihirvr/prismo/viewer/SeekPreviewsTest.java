package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeekPreviewsTest {

    @Test
    void aboutOnePerSecondThenOnePerTenSecondsUpToFourHundred() {
        assertEquals(1, SeekPreviews.slots(0.2), "a very short video still gets one");
        assertEquals(12, SeekPreviews.slots(12));
        assertEquals(100, SeekPreviews.slots(100));
        assertEquals(100, SeekPreviews.slots(600), "10 minutes: still 100 (more than one per 10 s)");
        assertEquals(350, SeekPreviews.slots(3500), "an hour: one per 10 s");
        assertEquals(400, SeekPreviews.slots(5 * 3600), "five hours: capped");
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
        for (int slot = 0; slot < 350; slot++) {
            assertEquals(slot, SeekPreviews.slotOf(SeekPreviews.timeOf(slot, 3500, 350), 3500, 350));
        }
    }

    @Test
    void orderVisitsEverySlotOnceCoarseFirst() {
        for (int slots : new int[] {1, 2, 3, 7, 12, 64, 99, 100, 350, 400}) {
            List<Integer> order = SeekPreviews.order(slots);
            assertEquals(slots, order.size(), slots + " slots");
            assertEquals(slots, new HashSet<>(order).size(), "no slot twice for " + slots);
        }
        List<Integer> order = SeekPreviews.order(100);
        assertEquals(50, order.get(0), "the middle first");
        assertEquals(List.of(25, 75), order.subList(1, 3), "then the quarters");
        List<Integer> first = order.subList(0, 8);
        for (int slot = 0; slot < 100; slot++) {
            int at = slot;
            int nearest = first.stream().mapToInt(s -> Math.abs(s - at)).min().orElseThrow();
            assertTrue(nearest <= 13, "slot " + slot + " is " + nearest + " slots from a picture");
        }
    }

    @Test
    void theSlotUnderTheMouseGoesFirstOnce() {
        List<Integer> order = SeekPreviews.order(10);
        boolean[] tried = new boolean[10];
        int[] cursor = {0};
        assertEquals(7, SeekPreviews.next(7, tried, order, cursor), "wanted first");
        assertEquals(0, cursor[0], "the coarse order didn't move");
        tried[7] = true;
        int next = SeekPreviews.next(7, tried, order, cursor);
        assertEquals(order.getFirst(), next, "wanted already tried: the coarse order goes on");
        for (int slot = 0; slot < 10; slot++) {
            tried[slot] = true;
        }
        assertEquals(-1, SeekPreviews.next(3, tried, order, cursor), "all tried: done");
        assertFalse(SeekPreviews.next(-1, new boolean[3], SeekPreviews.order(3), new int[] {0}) < 0);
    }

    @Test
    void picturesAreCappedByShape() {
        assertArrayEquals(new double[] {160, 90}, SeekPreviews.pictureSize(1920, 1080), 1e-9, "wide");
        assertArrayEquals(new double[] {67.5, 120}, SeekPreviews.pictureSize(1080, 1920), 1e-9, "tall phone video");
        assertArrayEquals(new double[] {120, 120}, SeekPreviews.pictureSize(500, 500), 1e-9, "square");
        assertArrayEquals(new double[] {120, 90}, SeekPreviews.pictureSize(640, 480), 1e-9, "4:3 fits the wide box");
        assertArrayEquals(new double[] {160, 90}, SeekPreviews.pictureSize(0, 0), 1e-9, "unknown: wide box");
    }

    @Test
    void noPicturesForNoVideo() {
        assertNull(SeekPreviews.none().at(5));
    }

    @Test
    void longVideosDecodeOnlyKeyFramesShortOnesTheExactFrame() {
        List<String> fast = SeekPreviews.command(Path.of("ffmpeg"), Path.of("clip.mkv"), Path.of("out.jpg"), 75.5, true);
        int input = fast.indexOf("-i");
        assertTrue(fast.indexOf("-skip_frame") < input && fast.get(fast.indexOf("-skip_frame") + 1).equals("nokey"),
                "key frames only (an input option)");
        assertTrue(fast.indexOf("-ss") < input, "seeks before reading (an input option)");
        assertEquals("75.500", fast.get(fast.indexOf("-ss") + 1));
        assertEquals("0:V:0", fast.get(fast.indexOf("-map") + 1), "the real video, never a cover picture");
        assertEquals("scale=160:120:force_original_aspect_ratio=decrease", fast.get(fast.indexOf("-vf") + 1));
        assertEquals("1", fast.get(fast.indexOf("-frames:v") + 1));
        assertEquals("out.jpg", fast.getLast());

        List<String> exact = SeekPreviews.command(Path.of("ffmpeg"), Path.of("clip.mp4"), Path.of("out.jpg"), 3, false);
        assertFalse(exact.contains("-skip_frame"), "short videos: the exact frame");
        assertTrue(exact.indexOf("-ss") < exact.indexOf("-i"));
        assertTrue(SeekPreviews.EXACT_UNDER_SECONDS >= 60);
    }
}
