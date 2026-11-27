package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ShortBuffer;
import org.junit.jupiter.api.Test;

class SoundTapTest {

    private static final int RATE = 1000; // one sample a millisecond keeps the numbers readable

    /** A block of {@code frames} stereo samples whose left and right are both {@code value}. */
    private static ShortBuffer block(int frames, short value) {
        short[] samples = new short[frames * 2];
        java.util.Arrays.fill(samples, value);
        return ShortBuffer.wrap(samples);
    }

    @Test
    void handsOutTheSamplesThatEndAtThePlayingPosition() {
        SoundTap tap = new SoundTap();
        tap.add(block(100, (short) 16384), 200, 0, RATE);        // 0 to 100 ms: half loud
        tap.add(block(100, (short) -32768), 200, 100_000, RATE); // 100 to 200 ms: full, negative
        float[] out = new float[50];

        assertTrue(tap.copy(100_000, out));
        assertEquals(0.5f, out[0], 1e-4);
        assertEquals(0.5f, out[49], 1e-4, "ends just before 100 ms");
        assertTrue(tap.copy(150_000, out));
        assertEquals(-1f, out[0], 1e-4);
        assertEquals(RATE, tap.rate());
    }

    @Test
    void nothingBeforeItIsDecodedOrAfterASeek() {
        SoundTap tap = new SoundTap();
        float[] out = new float[50];
        assertFalse(tap.copy(0, out), "empty");
        tap.add(block(100, (short) 1000), 200, 0, RATE);
        assertFalse(tap.copy(150_000, out), "not decoded yet");
        assertFalse(tap.copy(20_000, out), "starts before the first sample");

        tap.add(block(100, (short) 2000), 200, 5_000_000, RATE); // a seek to 5 s: a new run

        assertFalse(tap.copy(100_000, out), "the old run is gone");
        assertTrue(tap.copy(5_100_000, out));
        tap.clear();
        assertFalse(tap.copy(5_100_000, out));
        assertEquals(0, tap.rate());
    }

    @Test
    void mixesLeftAndRight() {
        SoundTap tap = new SoundTap();
        tap.add(ShortBuffer.wrap(new short[] {32767, -32767, 16384, 16384}), 4, 0, RATE);
        float[] out = new float[2];

        assertTrue(tap.copy(2_000, out));
        assertEquals(0f, out[0], 1e-4);
        assertEquals(0.5f, out[1], 1e-4);
    }
}
