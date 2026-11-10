package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BackdropStillTest {

    @Test
    void morePlainlyDifferentColoursScoreHigher() {
        int[] grey = new int[100];
        Arrays.fill(grey, 0xff808080);
        int[] oneColour = new int[100];
        Arrays.fill(oneColour, 0xffe43bba);
        int[] three = new int[99];
        Arrays.fill(three, 0, 33, 0xff1a59ee);
        Arrays.fill(three, 33, 66, 0xffe43bba);
        Arrays.fill(three, 66, 99, 0xffffd23f);
        double g = Backdrop.colourfulness(grey), o = Backdrop.colourfulness(oneColour), t = Backdrop.colourfulness(three);
        assertTrue(g < 1, "grey has no hue at all: " + g);
        assertTrue(o >= 1 && o < 2, "one hue: " + o);
        assertTrue(t >= 3 && t < 4, "three hues: " + t);
    }

    @Test
    void theStillPictureIsTheMostColourfulMomentAndAlwaysTheSame() {
        double chosen = Backdrop.mostColourful(112, 70);
        assertEquals(chosen, Backdrop.mostColourful(112, 70), "the same still every time (main window and Settings)");
        double best = Backdrop.colourfulness(Backdrop.render(48, 30, chosen, true, 1.0));
        for (int k = 0; k < Backdrop.STILL_CANDIDATES; k++) {
            double other = Backdrop.colourfulness(Backdrop.render(48, 30, k * Backdrop.STILL_STEP, true, 1.0));
            assertTrue(best >= other, "moment " + k + " shows more colours than the chosen one");
        }
    }
}
