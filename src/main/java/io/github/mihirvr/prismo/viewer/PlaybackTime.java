package io.github.mihirvr.prismo.viewer;

import java.util.Locale;

/** "1:05 / 12:30" for the video controls. Pure, so it's unit-tested. */
final class PlaybackTime {

    private PlaybackTime() {
    }

    /** {@code current / total}, both as m:ss, or h:mm:ss once the video is an hour or longer. */
    static String format(double currentSeconds, double totalSeconds) {
        // Unknown length (JavaFX's Duration.INDEFINITE / UNKNOWN) is infinite or NaN: not "an hour or more".
        boolean hours = Double.isFinite(totalSeconds) && totalSeconds >= 3600;
        return clock(currentSeconds, hours) + " / " + clock(totalSeconds, hours);
    }

    /** One time on its own ("1:23", or "1:02:03" from an hour on), e.g. "Resumed at 1:23". */
    static String clock(double seconds) {
        return clock(seconds, Double.isFinite(seconds) && seconds >= 3600);
    }

    private static String clock(double seconds, boolean hours) {
        long s = Double.isFinite(seconds) && seconds > 0 ? (long) seconds : 0; // unknown / NaN → 0:00
        return hours
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
