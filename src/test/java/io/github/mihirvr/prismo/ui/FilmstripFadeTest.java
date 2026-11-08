package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The filmstrip's edge fade (1.2.0): plain arithmetic, no JavaFX toolkit needed. */
class FilmstripFadeTest {

    @Test
    void tilesFadeOnlyNearTheEdges() {
        double width = 1000;
        assertEquals(1, Filmstrip.edgeOpacity(width / 2, width), 1e-9, "the middle tile");
        assertEquals(1, Filmstrip.edgeOpacity(Filmstrip.FADE_WIDTH, width), 1e-9, "where the fade starts");
        assertEquals(0, Filmstrip.edgeOpacity(0, width), 1e-9, "at the left edge");
        assertEquals(0, Filmstrip.edgeOpacity(width, width), 1e-9, "at the right edge");
        assertEquals(0, Filmstrip.edgeOpacity(-30, width), 1e-9, "past the edge (a cut-off tile)");
        assertEquals(0.5, Filmstrip.edgeOpacity(Filmstrip.FADE_WIDTH / 2, width), 1e-9, "half way: half");
        assertEquals(Filmstrip.edgeOpacity(100, width), Filmstrip.edgeOpacity(width - 100, width), 1e-9, "both sides alike");
    }

    @Test
    void theFadeGetsStrongerTowardsTheEdge() {
        double previous = -1;
        for (double x = 0; x <= Filmstrip.FADE_WIDTH; x += 8) {
            double opacity = Filmstrip.edgeOpacity(x, 1000);
            assertTrue(opacity >= previous, "at " + x);
            previous = opacity;
        }
    }

    @Test
    void aNarrowStripKeepsItsMiddleTileVisible() {
        double width = Filmstrip.FADE_WIDTH; // narrower than the two fades together
        assertEquals(1, Filmstrip.edgeOpacity(width / 2, width), 1e-9);
        assertEquals(1, Filmstrip.edgeOpacity(10, 0), 1e-9, "no width yet: nothing faded");
    }
}
