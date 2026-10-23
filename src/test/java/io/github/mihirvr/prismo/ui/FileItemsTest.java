package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.index.FileOperations;
import io.github.mihirvr.prismo.testing.TestPaths;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FileItemsTest {

    private static final Path TRIP = TestPaths.of("Photos", "Trip");

    @Test
    void theMoveMessageSaysWhatHappened() {
        assertEquals("Moved 1 item to Trip.", FileItems.moveText(outcome(1, 0, 0, 0, null), TRIP));
        assertEquals("Moved 3 items to Trip. 2 skipped (same name there, or already there).",
                FileItems.moveText(outcome(3, 2, 0, 0, null), TRIP));
        assertEquals("Moved 1 item to Trip. That folder isn't in your library, so Prismo no longer shows it.",
                FileItems.moveText(outcome(1, 0, 0, 1, null), TRIP));
        assertEquals("Moved 0 items to Trip. 1 not moved: it was gone",
                FileItems.moveText(outcome(0, 0, 1, 0, "it was gone"), TRIP));
    }

    private static FileOperations.Outcome outcome(int moved, int skipped, int failed, int left, String problem) {
        return new FileOperations.Outcome(moved, skipped, failed, left, Optional.ofNullable(problem), Map.of());
    }
}
