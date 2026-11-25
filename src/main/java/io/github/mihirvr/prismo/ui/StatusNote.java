package io.github.mihirvr.prismo.ui;

import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.util.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The small note at the bottom of the main window (1.1.0; it replaced a status bar that always showed counts). It is
 * there only while there is something to say:
 * <ul>
 *   <li>work that runs a while (indexing, making thumbnails, 1.4.1 the Duplicates check): shown once it has run
 *       {@link #PROGRESS_DELAY}, so a quick rescan shows nothing, and gone when the work is done. The Duplicates check
 *       has its own line, so it and indexing / thumbnails never hide each other;</li>
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
    private final ReadOnlyBooleanWrapper working = new ReadOnlyBooleanWrapper();

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

    /**
     * True while work runs (indexing, thumbnails), from its first report, before the note itself shows (2026-11-10:
     * the colour background stays still meanwhile).
     */
    public ReadOnlyBooleanProperty workingProperty() {
        return working.getReadOnlyProperty();
    }

    /** Work running now (indexing, thumbnails), or null when it is done. */
    public void progress(String text) {
        progress(State.Work.MAIN, text);
    }

    /** 1.4.1: the Duplicates check's own line, or null when it is done. */
    public void duplicatesProgress(String text) {
        progress(State.Work.DUPLICATES, text);
    }

    private void progress(State.Work work, String text) {
        if (state.progress(work, text)) {
            showProgress.playFromStart();
        } else if (!state.working()) {
            showProgress.stop();
        }
        working.set(state.working());
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
     * "Making thumbnails… 120 of 266", and (1.2.0) "· 2 couldn't be made" once any failed, so a count that stops
     * moving is told apart from files that can't be read; during a big first setup also that photos may open slowly
     * meanwhile (1.0.1: indexing and thumbnails keep the disk busy; seen on a 40,000-item library on a hard disk).
     */
    public static String thumbnails(int done, int total, int failed, boolean bigSetup) {
        return State.thumbnails(done, total, failed, bigSetup);
    }

    /**
     * 1.4.1: "Looking for copies… 120 of 800 files · 1.2 of 4.5 GB". The size moves while one big video is read, so
     * the note never looks stuck.
     */
    public static String duplicates(int done, int total, long bytesDone, long bytesTotal) {
        return State.duplicates(done, total, bytesDone, bytesTotal);
    }

    /** The rules and texts, without JavaFX (loading StatusNote itself needs it): what the note says now. */
    static final class State {

        /** The kinds of work that each have a line. */
        enum Work { MAIN, DUPLICATES }

        static String indexing(int found) {
            return "Looking for photos and videos… %,d found".formatted(found);
        }

        static String thumbnails(int done, int total, int failed, boolean bigSetup) {
            return "Making thumbnails… %,d of %,d".formatted(done, total)
                    + (failed > 0 ? "  ·  %,d couldn't be made".formatted(failed) : "")
                    + (bigSetup ? "  ·  photos may open slowly until this finishes" : "");
        }

        static String duplicates(int done, int total, long bytesDone, long bytesTotal) {
            // One unit for both sizes, picked by the total: "1.2 of 4.5 GB".
            String[] units = {"KB", "MB", "GB", "TB"};
            double divisor = 1024;
            int unit = 0;
            while (bytesTotal / divisor >= 1024 && unit < units.length - 1) {
                divisor *= 1024;
                unit++;
            }
            return "Looking for copies… %,d of %,d files  ·  %.1f of %.1f %s".formatted(done, total,
                    bytesDone / divisor, bytesTotal / divisor, units[unit]);
        }

        private final String[] progress = new String[Work.values().length];
        private boolean delayPassed;
        private String message;

        /** Returns true if this starts new work (nothing ran before), so the delay starts now. */
        boolean progress(String text) {
            return progress(Work.MAIN, text);
        }

        boolean progress(Work work, String text) {
            boolean starts = !working() && text != null;
            progress[work.ordinal()] = text;
            if (!working()) {
                delayPassed = false;
            }
            return starts;
        }

        boolean working() {
            for (String text : progress) {
                if (text != null) {
                    return true;
                }
            }
            return false;
        }

        void delayPassed() {
            delayPassed = working();
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
            if (!delayPassed) {
                return "";
            }
            List<String> lines = new ArrayList<>();
            for (String text : progress) {
                if (text != null) {
                    lines.add(text);
                }
            }
            return String.join("\n", lines);
        }
    }
}
