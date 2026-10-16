package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlaybackTimeTest {

    @Test
    void minutesAndSeconds() {
        assertEquals("0:00 / 0:07", PlaybackTime.format(0, 7.9));
        assertEquals("1:05 / 12:30", PlaybackTime.format(65.4, 750));
    }

    @Test
    void hoursOnceTheVideoIsAnHourLong() {
        assertEquals("0:01:05 / 1:02:03", PlaybackTime.format(65, 3723));
    }

    @Test
    void unknownTimesShowZero() {
        assertEquals("0:00 / 0:00", PlaybackTime.format(Double.NaN, Double.POSITIVE_INFINITY));
        assertEquals("0:00 / 0:00", PlaybackTime.format(-3, 0));
    }
}
