package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.ui.TimelineItem;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.util.Duration;

/**
 * The viewer's slideshow: a fixed 3 s per picture, in Timeline order, skipping videos and files the app can't
 * show, stopping at the end. The timer is a JavaFX {@link PauseTransition}, so ticks arrive on the UI thread.
 */
final class Slideshow {

    static final Duration PERIOD = Duration.seconds(3);

    private final PauseTransition timer = new PauseTransition(PERIOD);
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);

    /** @param onTick runs every {@link #PERIOD} while running; it moves on (and calls {@link #restart}) or stops */
    Slideshow(Runnable onTick) {
        timer.setOnFinished(_ -> onTick.run());
    }

    void start() {
        running.set(true);
        timer.playFromStart();
    }

    void stop() {
        running.set(false);
        timer.stop();
    }

    /** A fresh 3 s for the picture now on screen (after a tick, or when the user steps manually). */
    void restart() {
        if (running.get()) {
            timer.playFromStart();
        }
    }

    boolean isRunning() {
        return running.get();
    }

    ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    /** The next item after {@code from} the slideshow shows, or -1 at the end. Pure, so it's unit-tested. */
    static int next(List<TimelineItem> items, int from) {
        for (int i = from + 1; i < items.size(); i++) {
            if (ViewMode.of(items.get(i).ref().extension()).inSlideshow()) {
                return i;
            }
        }
        return -1;
    }
}
