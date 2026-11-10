package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrostTest {

    @Test
    void noExtraTintWhenWhatIsBehindIsAboutAsBrightAsTheTint() {
        assertEquals(0, Frost.extraTint(0.20, 0.08), 1e-9, "a dark menu over the dark background (the drop-down)");
        assertEquals(0, Frost.extraTint(0.85, 0.95), 1e-9, "a light menu over light colours");
    }

    @Test
    void moreTintAsTheyGrowApartUpToTheMost() {
        double some = Frost.extraTint(0.50, 0.08);
        double more = Frost.extraTint(0.70, 0.08);
        assertTrue(some > 0 && more > some, some + " then " + more);
        assertEquals(Frost.MAX_EXTRA, Frost.extraTint(0.95, 0.08), 1e-9, "a dark menu over white photos");
        assertEquals(Frost.MAX_EXTRA, Frost.extraTint(0.02, 0.97), 1e-9, "a light menu over black");
    }
}
