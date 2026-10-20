package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The video controls' plain rules (the pane itself needs a running JavaFX; the probe covers it). */
class VideoPaneTest {

    @Test
    void speedsStepAlongTheListAndStopAtTheEnds() {
        assertEquals(1.25, VideoPane.nextSpeed(1, 1));
        assertEquals(0.75, VideoPane.nextSpeed(1, -1));
        assertEquals(2, VideoPane.nextSpeed(2, 1), "fastest stays fastest");
        assertEquals(0.5, VideoPane.nextSpeed(0.5, -1));
        assertEquals(1.5, VideoPane.nextSpeed(1.3, 1), "an odd saved speed steps from the nearest one");
        assertEquals(1, VideoPane.nextSpeed(1, 0));
    }

    @Test
    void aNarrowerBarLeavesOutTheVolumeSliderThenTheSkipsLoopSpeedAndSoundTrack() {
        double[] room = VideoPane.ROOM_FOR;
        assertEquals(0, VideoPane.controlsLeftOut(room[0] + 200), "wide: everything");
        assertEquals(0, VideoPane.controlsLeftOut(room[0]));
        assertEquals(1, VideoPane.controlsLeftOut(room[0] - 1), "no volume slider");
        assertEquals(1, VideoPane.controlsLeftOut(room[1]));
        assertEquals(2, VideoPane.controlsLeftOut(room[1] - 1), "no ±10 s buttons either");
        assertEquals(2, VideoPane.controlsLeftOut(room[2]));
        assertEquals(3, VideoPane.controlsLeftOut(room[2] - 1), "no loop button either");
        assertEquals(4, VideoPane.controlsLeftOut(room[3] - 1), "no speed button either");
        assertEquals(5, VideoPane.controlsLeftOut(room[4] - 1), "no sound-track button either");
        assertEquals(5, VideoPane.controlsLeftOut(0));
    }

    @Test
    void speedAndDelayTexts() {
        assertEquals("1×", VideoPane.speedText(1));
        assertEquals("1.25×", VideoPane.speedText(1.25));
        assertEquals("0.5×", VideoPane.speedText(0.5));
        assertEquals("2×", VideoPane.speedText(2));
        assertEquals("0.0 s", VideoPane.delayText(0));
        assertEquals("+0.3 s", VideoPane.delayText(300_000));
        assertEquals("-1.2 s", VideoPane.delayText(-1_200_000));
    }
}
