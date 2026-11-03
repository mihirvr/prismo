package io.github.mihirvr.prismo.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.util.Duration;

/**
 * The small note at the bottom of the main window (1.1.0; it replaced a status bar that always showed counts). It is
 * there only while there is something to say:
 * <ul>
 *   <li>work that runs a while (indexing, making thumbnails): shown once it has run {@link #PROGRESS_DELAY}, so a
 *       quick rescan shows nothing, and gone when the work is done;</li>
 *   <li>a message (what an action did, or a problem): shown at once, gone after {@link #MESSAGE_TIME}.</li>
 * </ul>
 * It never takes room from the grid: it floats over its bottom-left corner and lets clicks through. Runs on the
 * JavaFX Application Thread; the rules themselves are in {@link State}, which tests check without JavaFX.
 */
public final class StatusNote extends Label {

    static final Duration PROGRESS_DELAY = Duration.seconds(1.5);
    static final Duration MESSAGE_TIME = Duration.seconds(6);

    private final State state = new State();
    private final PauseTransition showProgress = new PauseTransition(PROGRESS_DELAY);
    private final PauseTransition endMessage = new PauseTransition(MESSAGE_TIME);

    public StatusNote() {
        getStyleClass().add("status-note");
        setMouseTransparent(true);
        setManaged(true);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE); // its own size in the corner, not the whole grid
        setAlignment(Pos.CENTER_LEFT);
        showProgress.setOnFinished(_ -> {
            state.delayPassed();
            show();
        });
        endMessage.setOnFinished(_ -> {
            state.messageEnded();
            show();
        });
        show();
    }

    /** Work running now, or null when it is done. */
    public void progress(String text) {
        if (state.progress(text)) {
            showProgress.playFromStart();
        } else if (text == null) {
            showProgress.stop();
        }
        show();
    }

    /** What an action did, or a problem: shown at once, for {@link #MESSAGE_TIME}. */
    public void say(String text) {
        state.say(text);
        endMessage.playFromStart();
        show();
    }

    private void show() {
        String text = state.text();
        setText(text);
        setVisible(!text.isEmpty());
    }

    /** "Looking for photos and videos… 1,234 found". */
    public static String indexing(int found) {
        return State.indexing(found);
    }

    /**
     * "Making thumbnails… 120 of 266"; during a big first setup also that photos may open slowly meanwhile (1.0.1:
     * indexing and thumbnails keep the disk busy; seen on a 40,000-item library on a hard disk).
     */
    public static String thumbnails(int done, int total, boolean bigSetup) {
        return State.thumbnails(done, total, bigSetup);
    }

    /** The rules and texts, without JavaFX (loading StatusNote itself needs it): what the note says now. */
    static final class State {

        static String indexing(int found) {
            return "Looking for photos and videos… %,d found".formatted(found);
        }

        static String thumbnails(int done, int total, boolean bigSetup) {
            return "Making thumbnails… %,d of %,d".formatted(done, total)
                    + (bigSetup ? "  ·  photos may open slowly until this finishes" : "");
        }

        private String progress;
        private boolean delayPassed;
        private String message;

        /** Returns true if this starts new work, so the delay starts now. */
        boolean progress(String text) {
            boolean starts = progress == null && text != null;
            progress = text;
            if (text == null) {
                delayPassed = false;
            }
            return starts;
        }

        void delayPassed() {
            delayPassed = progress != null;
        }

        void say(String text) {
            message = text == null || text.isBlank() ? null : text.strip();
        }

        void messageEnded() {
            message = null;
        }

        /** The text on screen; empty = the note is hidden. */
        String text() {
            if (message != null) {
                return message;
            }
            return delayPassed && progress != null ? progress : "";
        }
    }
}
