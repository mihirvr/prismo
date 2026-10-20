package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

class DialogPlacementTest {

    /** A 1536 × 816 usable area (1920 × 1080 at 125%, less the taskbar). */
    private static final Rectangle2D SCREEN = new Rectangle2D(0, 0, 1536, 816);
    private static final double M = DialogPlacement.MARGIN;

    @Test
    void aDialogThatFitsIsCentredOnItsOwner() {
        Rectangle2D owner = new Rectangle2D(100, 100, 1000, 600);

        assertEquals(new Rectangle2D(350, 200, 500, 400), DialogPlacement.place(500, 400, owner, SCREEN));
    }

    @Test
    void aDialogTallerThanTheScreenIsShrunkAndItsTitleBarStaysOnScreen() {
        Rectangle2D owner = new Rectangle2D(0, 0, 1536, 816); // maximised

        Rectangle2D placed = DialogPlacement.place(640, 2400, owner, SCREEN);

        assertEquals(816 - 2 * M, placed.getHeight(), "it scrolls inside instead");
        assertEquals(M, placed.getMinY(), "the title bar is reachable");
        assertEquals((1536 - 640) / 2.0, placed.getMinX());
    }

    @Test
    void aDialogNearAScreenEdgeMovesFullyOntoIt() {
        Rectangle2D owner = new Rectangle2D(1300, 600, 400, 300); // a small window hanging off the bottom right

        Rectangle2D placed = DialogPlacement.place(600, 500, owner, SCREEN);

        assertEquals(1536 - M - 600, placed.getMinX());
        assertEquals(816 - M - 500, placed.getMinY());
    }

    @Test
    void aSecondScreenIsUsedWhereItIs() {
        Rectangle2D right = new Rectangle2D(1536, 0, 1280, 984);
        Rectangle2D owner = new Rectangle2D(1600, 50, 900, 700);

        assertEquals(new Rectangle2D(1850, 150, 400, 500), DialogPlacement.place(400, 500, owner, right));
    }
}
