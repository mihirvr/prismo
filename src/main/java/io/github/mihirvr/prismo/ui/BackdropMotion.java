package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.platform.PowerStatus;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts and stops the main window's colour background (step 3, 2026-11-10): still first, moving only when
 * {@link MotionRules} says the app is idle and the computer can take it. Window and app changes (minimised, another
 * window active, a photo opened, work starting) apply at once; the computer's CPU use and battery are read every
 * {@link #CHECK_SECONDS} seconds on a plain timer thread (never a JavaFX animation, which would wake JavaFX 60 times
 * a second). Runs on the JavaFX thread apart from that reading.
 */
public final class BackdropMotion {

    private static final Logger log = LoggerFactory.getLogger(BackdropMotion.class);
    static final int CHECK_SECONDS = 2;

    private final Backdrop backdrop;
    private final Stage stage;
    private final BooleanProperty wanted = new SimpleBooleanProperty(true);
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final BooleanProperty viewerOpen = new SimpleBooleanProperty();
    private final ReadOnlyObjectWrapper<MotionRules.Reason> reason =
            new ReadOnlyObjectWrapper<>(MotionRules.Reason.SETTLING);
    private final int cores = Runtime.getRuntime().availableProcessors();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backdrop-motion");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Since when nothing has been busy (the app's start counts as busy, so it settles first). */
    private long quietSince = System.nanoTime();
    private volatile double cpuLoad = -1;
    private volatile boolean onBattery;
    /**
     * The user turned movement on themselves (Undo on the slowdown note, or "Keep it static" unticked): for the
     * rest of the session the timings no longer stop it.
     */
    private boolean userInsists;
    /** Told when the moving colours slow Prismo down (once a session): the app ticks "Keep it static" and says so. */
    private Runnable onSlow = () -> { };
    private boolean slowTold;

    public BackdropMotion(Backdrop backdrop, Stage stage) {
        this.backdrop = backdrop;
        this.stage = stage;
        wanted.addListener((_, _, on) -> {
            if (on) {
                userInsists = true; // a change to "move" is always the user's (the saved value is bound before)
                backdrop.resetTiming();
            }
            update();
        });
        viewerOpen.addListener((_, _, _) -> update());
        busy.addListener((_, _, now) -> {
            if (!now) {
                quietSince = System.nanoTime();
            }
            update();
        });
        stage.iconifiedProperty().addListener((_, _, _) -> update());
        stage.showingProperty().addListener((_, _, _) -> update());
        stage.focusedProperty().addListener((_, _, _) -> Platform.runLater(this::update)); // after the other window
        timer.scheduleWithFixedDelay(this::measure, 0, CHECK_SECONDS, TimeUnit.SECONDS);
    }

    /** True unless Settings' "Keep it static" is ticked (moving is the default; kept by the app). */
    public BooleanProperty wantedProperty() {
        return wanted;
    }

    /** Indexing or thumbnails running now. */
    public BooleanProperty busyProperty() {
        return busy;
    }

    /** A photo or video covers the window. */
    public BooleanProperty viewerOpenProperty() {
        return viewerOpen;
    }

    /** Why it is still now (or that it moves): shown under the switch in Settings. */
    public ReadOnlyObjectProperty<MotionRules.Reason> reasonProperty() {
        return reason.getReadOnlyProperty();
    }

    /** What to do when the moving colours slow Prismo down: tick "Keep it static" and tell the user. */
    public void setOnSlow(Runnable onSlow) {
        this.onSlow = onSlow;
    }

    /** Follows a value that says whether work runs, e.g. the status note's progress. */
    public void followBusy(ObservableValue<Boolean> working) {
        busy.bind(working);
    }

    /** Re-decides now (the look or the Colours switch changed). */
    public void update() {
        double frame = backdrop.averageFrameMs();
        if (backdrop.isMoving() && !userInsists && !slowTold
                && MotionRules.struggling(frame, backdrop.averageLagMs())) {
            slowTold = true;
            log.info("The moving colours slow Prismo down (picture {} ms, lag {} ms): switched to static",
                    Math.round(frame), Math.round(backdrop.averageLagMs()));
            backdrop.setMoving(false);
            onSlow.run(); // ticks "Keep it static": wanted turns false and update() runs again
            return;
        }
        MotionRules.Inputs in = new MotionRules.Inputs(wanted.get(), backdrop.hasColours(),
                stage.isIconified() || !stage.isShowing(), appFocused(), viewerOpen.get(), busy.get(),
                busy.get() ? 0 : System.nanoTime() - quietSince, onBattery, cores, cpuLoad,
                userInsists ? 0 : frame, backdrop.isMoving());
        MotionRules.Reason now = MotionRules.decide(in);
        boolean move = now == MotionRules.Reason.MOVING;
        if (move != backdrop.isMoving()) {
            log.debug("Colour background {}: {}", move ? "moving" : "still", now);
        }
        backdrop.setMoving(move);
        reason.set(now);
    }

    /** Stops the timer (the app is closing). */
    public void dispose() {
        timer.shutdownNow();
        backdrop.setMoving(false);
    }

    /** True when one of the app's windows is the active one (a dialog of it counts). */
    private static boolean appFocused() {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.isFocused()) {
                return true;
            }
        }
        return false;
    }

    // Timer thread: the slow readings, then a decision on the JavaFX thread.
    private void measure() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                double load = os.getCpuLoad();
                cpuLoad = load >= 0 ? load : -1;
            }
        } catch (Throwable e) {
            cpuLoad = -1; // not known: the CPU rule is skipped
        }
        onBattery = PowerStatus.onBattery();
        Platform.runLater(this::update);
    }
}
