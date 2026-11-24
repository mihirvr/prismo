package io.github.mihirvr.prismo.viewer;

/**
 * The running total of quick skips in a video (1.4.0, as on YouTube): pressing Right three times shows +5 s, +10 s,
 * +15 s instead of "Forward 5 s" three times. Pure: no JavaFX; the time comes in as nanoseconds.
 *
 * <p>The total starts again after {@link #PAUSE_NANOS} without a skip, or when the direction changes.
 */
final class SkipTotal {

    /** A skip this long after the last one starts a new total. */
    static final long PAUSE_NANOS = 1_000_000_000L;

    private long totalMicros;
    private long lastNanos;
    private boolean any;

    /** Adds a skip of {@code micros} made at {@code nowNanos}; returns the total so far, this skip included. */
    long add(long micros, long nowNanos) {
        boolean sameRun = any
                && nowNanos - lastNanos <= PAUSE_NANOS
                && Long.signum(micros) == Long.signum(totalMicros);
        totalMicros = sameRun ? totalMicros + micros : micros;
        lastNanos = nowNanos;
        any = true;
        return totalMicros;
    }

    /** Forgets the total (a new video, or a seek of another kind). */
    void reset() {
        any = false;
        totalMicros = 0;
    }

    /** "+15 s", "−10 s", "+1 min", "+1 min 30 s" (a real minus sign, so + and − line up). */
    static String text(long micros) {
        long seconds = Math.abs(micros) / 1_000_000;
        String sign = micros < 0 ? "−" : "+";
        if (seconds < 60) {
            return sign + seconds + " s";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return sign + minutes + " min" + (rest == 0 ? "" : " " + rest + " s");
    }
}
