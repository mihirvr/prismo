package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GridLayoutTest {

    @Test
    void asManyColumnsAsFitThenStretchedToFill() {
        GridLayout layout = GridLayout.fit(1000, 180, 4);

        assertEquals(5, layout.columns()); // 6 × 180 + 5 × 4 = 1100 > 1000
        assertEquals(196, layout.cellSize()); // (1000 − 4 × 4) / 5 = 196.8, rounded down
        double used = layout.columns() * layout.cellSize() + 4 * (layout.columns() - 1);
        assertTrue(used <= 1000 && used > 1000 - layout.columns(), "fills the row to within a pixel per column");
    }

    @Test
    void cellsAreNeverSmallerThanTheChosenSize() {
        for (double width = 100; width <= 4000; width += 37) {
            GridLayout layout = GridLayout.fit(width, 150, 4);
            assertTrue(layout.cellSize() >= 150 || layout.columns() == 1, "width " + width + ": " + layout);
        }
    }

    @Test
    void biggerThumbnailsMeanFewerColumns() {
        assertEquals(10, GridLayout.fit(1000, 96, 4).columns());
        assertEquals(3, GridLayout.fit(1000, 320, 4).columns());
    }

    @Test
    void aNarrowWindowStillShowsOneColumn() {
        assertEquals(new GridLayout(1, 50), GridLayout.fit(50, 180, 4));
    }

    @Test
    void beforeTheFirstLayoutUsesTheChosenSize() {
        assertEquals(new GridLayout(1, 180), GridLayout.fit(0, 180, 4));
    }
}
