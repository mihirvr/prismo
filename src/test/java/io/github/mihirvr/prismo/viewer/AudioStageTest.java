package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AudioStageTest {

    /** A pure tone shows up in the one frequency bin it belongs to (the bars are built on this). */
    @Test
    void theFftFindsAToneInItsBin() {
        int size = 2048;
        double[] window = AudioStage.hann(size);
        for (int bin : new int[] {5, 93, 700}) {
            double[] re = new double[size];
            double[] im = new double[size];
            for (int i = 0; i < size; i++) {
                re[i] = Math.sin(2 * Math.PI * bin * i / size) * window[i];
            }

            AudioStage.fft(re, im);

            int loudest = 0;
            for (int i = 1; i < size / 2; i++) {
                if (Math.hypot(re[i], im[i]) > Math.hypot(re[loudest], im[loudest])) {
                    loudest = i;
                }
            }
            assertEquals(bin, loudest);
        }
    }

    @Test
    void theWindowIsZeroAtTheEdgesAndOneInTheMiddle() {
        double[] window = AudioStage.hann(9);
        assertEquals(0, window[0], 1e-12);
        assertEquals(1, window[4], 1e-12);
        assertEquals(0, window[8], 1e-12);
    }
}
