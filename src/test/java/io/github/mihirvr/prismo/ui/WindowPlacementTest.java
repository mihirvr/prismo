package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import javafx.geometry.Dimension2D;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

class WindowPlacementTest {

    /** A 1920 × 1080 screen less a 48 px taskbar, and a second one to its right. */
    private static final Rectangle2D LEFT = new Rectangle2D(0, 0, 1920, 1032);
    private static final Rectangle2D RIGHT = new Rectangle2D(1920, 0, 1280, 984);

    @Test
    void theSavedTextReadsBack() {
        WindowPlacement place = new WindowPlacement(120, 80.5, 1200, 800, true);

        assertEquals("120.0,80.5,1200.0,800.0,true", place.format());
        assertEquals(Optional.of(place), WindowPlacement.parse(place.format()));
        assertEquals(Optional.of(new WindowPlacement(-8, -8, 1200, 800, false)),
                WindowPlacement.parse(" -8.0, -8.0 ,1200,800, false"), "spaces and negative positions are fine");
    }

    @Test
    void damagedOrTooSmallTextIsIgnored() {
        for (String bad : new String[] {null, "", "1,2,3,4", "1,2,1200,800,yes", "a,2,1200,800,false",
                "0,0,100,800,false", "0,0,1200,100,false", "NaN,0,1200,800,false", "0,0,Infinity,800,false",
                "0,0,1200,800,false,extra"}) {
            assertEquals(Optional.empty(), WindowPlacement.parse(bad), String.valueOf(bad));
        }
    }

    @Test
    void aPlaceOnAScreenThatIsStillThereIsKept() {
        WindowPlacement place = new WindowPlacement(100, 50, 1200, 800, false);

        assertEquals(Optional.of(place), place.fitTo(List.of(LEFT, RIGHT)));
    }

    @Test
    void aWindowHangingOffTheEdgeMovesBackOntoTheScreenItMostlyOverlaps() {
        WindowPlacement place = new WindowPlacement(1500, 400, 1200, 800, false); // 420 px on LEFT, 780 on RIGHT

        assertEquals(Optional.of(new WindowPlacement(1920, 184, 1200, 800, false)), place.fitTo(List.of(LEFT, RIGHT)));
    }

    @Test
    void aWindowBiggerThanItsScreenShrinksToIt() {
        WindowPlacement place = new WindowPlacement(1950, 10, 2000, 1400, true);

        assertEquals(Optional.of(new WindowPlacement(1920, 0, 1280, 984, true)), place.fitTo(List.of(LEFT, RIGHT)),
                "maximised stays maximised");
    }

    @Test
    void aWindowOnAScreenThatIsGoneIsNotUsed() {
        WindowPlacement place = new WindowPlacement(2200, 100, 1000, 700, false); // only on the unplugged RIGHT

        assertEquals(Optional.empty(), place.fitTo(List.of(LEFT)));
        assertEquals(Optional.empty(), place.fitTo(List.of()));
    }

    @Test
    void theFirstWindowFitsASmallScreenWithRoomForItsTitleBar() {
        // This PC at 125%: 1536 × 816 usable. 800 high plus the frame wouldn't fit.
        Dimension2D laptop = WindowPlacement.firstSize(1200, 800, new Rectangle2D(0, 0, 1536, 816));
        assertEquals(1200, laptop.getWidth());
        assertEquals(816 - WindowPlacement.FRAME_ROOM, laptop.getHeight());

        Dimension2D big = WindowPlacement.firstSize(1200, 800, new Rectangle2D(0, 0, 2560, 1400));
        assertEquals(new Dimension2D(1200, 800), big, "a big screen gets the default as it is");

        Dimension2D tiny = WindowPlacement.firstSize(1200, 800, new Rectangle2D(0, 0, 800, 500));
        assertEquals(new Dimension2D(WindowPlacement.MIN_WIDTH, WindowPlacement.MIN_HEIGHT), tiny,
                "never below the minimum");
    }
}
