package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.mihirvr.prismo.viewer.MediaViewer.Move;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

/** What the arrow and page keys do in the viewer, for a photo and for a video. */
class MoveTest {

    @Test
    void onAPhotoArrowsAndPageKeysMoveToAnotherItem() {
        assertEquals(Move.PREVIOUS, Move.forKey(KeyCode.LEFT, false));
        assertEquals(Move.NEXT, Move.forKey(KeyCode.RIGHT, false));
        assertEquals(Move.PREVIOUS, Move.forKey(KeyCode.PAGE_UP, false));
        assertEquals(Move.NEXT, Move.forKey(KeyCode.PAGE_DOWN, false));
    }

    @Test
    void inAVideoArrowsSkipAndPageKeysMoveToAnotherItem() {
        assertEquals(Move.BACK, Move.forKey(KeyCode.LEFT, true));
        assertEquals(Move.FORWARD, Move.forKey(KeyCode.RIGHT, true));
        assertEquals(Move.PREVIOUS, Move.forKey(KeyCode.PAGE_UP, true));
        assertEquals(Move.NEXT, Move.forKey(KeyCode.PAGE_DOWN, true));
        assertEquals(5_000_000, VideoPane.ARROW_SKIP_MICROS, "arrows skip 5 s, J / L 10 s");
        assertEquals(10_000_000, VideoPane.SKIP_MICROS);
    }

    @Test
    void otherKeysAreNotMoves() {
        for (KeyCode code : new KeyCode[] {KeyCode.UP, KeyCode.DOWN, KeyCode.SPACE, KeyCode.HOME, KeyCode.J}) {
            assertNull(Move.forKey(code, false), code + " on a photo");
            assertNull(Move.forKey(code, true), code + " in a video");
        }
    }
}
