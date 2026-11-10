package io.github.mihirvr.prismo.ui;

/**
 * Whether the colour background may move now, and if not, why (2026-11-10). The background starts still and only
 * moves once the app is idle and the computer can easily take it; it goes still again the moment that stops being
 * true. Pure rules, no JavaFX and no clock of its own, so tests check every case; {@link BackdropMotion} feeds them.
 *
 * <p>Starting asks for more than staying (a gap between the CPU limits), so the background doesn't flick on and off
 * around one value.
 */
public final class MotionRules {

    /** Logical processors needed: on fewer, the background stays still for good. */
    static final int MIN_CORES = 4;
    /** After indexing or thumbnails finish, this long with nothing running before moving. */
    static final long QUIET_NANOS = 10_000_000_000L;
    /** The whole computer's CPU use (0..1) under which the background may start moving. */
    static final double START_CPU = 0.50;
    /** ...and over which it goes still again (other programs need the CPU). */
    static final double STOP_CPU = 0.85;
    /** One moving picture may take this long on average (of the 100 ms between pictures); slower = still. */
    static final double SLOW_FRAME_MS = 50;
    /**
     * A moving picture may wait this long on average for the JavaFX thread to show it. Normally a few ms; much
     * more means the whole window is slow to respond (clicks, scrolling), so the colours give way.
     */
    static final double LAG_MS = 120;

    /** Why the background is still, or {@link #MOVING}. The text is shown under the setting in Settings. */
    public enum Reason {
        MOVING("Moving now."),
        OFF(""),
        NO_COLOURS(""),
        HIDDEN("Still while the window is minimised."),
        UNFOCUSED("Still while you work in another window."),
        VIEWER("Still while a photo or video is open."),
        BUSY("Still while photos are being found and thumbnails made."),
        SETTLING("Starts moving in a few seconds."),
        BATTERY("Still while the computer runs on battery or battery saver."),
        FEW_CORES("Still: this computer has few processor cores, so the colours stay still to keep it quick."),
        CPU_BUSY("Still while the computer is busy with other work."),
        TOO_SLOW("Still: moving pictures took too long to draw on this computer.");

        public final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    /**
     * Everything the decision looks at.
     *
     * @param wanted Settings' "Keep it static" is not ticked
     * @param colourful the background shows colours (Colours on, not AMOLED)
     * @param minimised the main window is minimised or hidden
     * @param focused the main window (or one of its dialogs) is the active window
     * @param viewerOpen a photo or video covers the window
     * @param busy indexing or thumbnails are running now
     * @param nanosSinceBusy how long ago the last busy period ended (Long.MAX_VALUE if never)
     * @param onBattery on battery or battery saver
     * @param cores logical processors
     * @param cpuLoad the whole computer's CPU use 0..1, or negative when not known
     * @param averageFrameMs the moving picture's average drawing time, or 0 before any was measured
     * @param movingNow the background moves at this moment (the higher bar is for starting)
     */
    public record Inputs(boolean wanted, boolean colourful, boolean minimised, boolean focused, boolean viewerOpen,
            boolean busy, long nanosSinceBusy, boolean onBattery, int cores, double cpuLoad, double averageFrameMs,
            boolean movingNow) {
    }

    private MotionRules() {
    }

    /**
     * True when the moving colours slow Prismo down on this computer (2026-11-10, Mihir: switch them to static and
     * say so): the pictures take too long to draw, or the window lags while they move.
     */
    public static boolean struggling(double averageFrameMs, double averageLagMs) {
        return averageFrameMs > SLOW_FRAME_MS || averageLagMs > LAG_MS;
    }

    /** The first reason to keep it still, in the order a user would care about; {@link Reason#MOVING} if none. */
    public static Reason decide(Inputs in) {
        if (!in.wanted()) {
            return Reason.OFF;
        }
        if (!in.colourful()) {
            return Reason.NO_COLOURS;
        }
        if (in.cores() < MIN_CORES) {
            return Reason.FEW_CORES;
        }
        if (in.averageFrameMs() > SLOW_FRAME_MS) {
            return Reason.TOO_SLOW;
        }
        if (in.minimised()) {
            return Reason.HIDDEN;
        }
        if (in.viewerOpen()) {
            return Reason.VIEWER;
        }
        if (in.busy()) {
            return Reason.BUSY;
        }
        if (in.onBattery()) {
            return Reason.BATTERY;
        }
        if (in.cpuLoad() >= 0 && in.cpuLoad() > (in.movingNow() ? STOP_CPU : START_CPU)) {
            return Reason.CPU_BUSY;
        }
        if (!in.focused()) {
            return Reason.UNFOCUSED;
        }
        if (!in.movingNow() && in.nanosSinceBusy() < QUIET_NANOS) {
            return Reason.SETTLING;
        }
        return Reason.MOVING;
    }
}
