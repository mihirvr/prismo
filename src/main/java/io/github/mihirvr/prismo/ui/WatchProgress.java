package io.github.mihirvr.prismo.ui;

/**
 * Video watch progress (PROJECT.md "Video Watch Progress"): where to resume, and how much of the thin bar on a
 * video's thumbnail to fill. Plain rules, no JavaFX, so they are unit-tested.
 *
 * <ul>
 *   <li>Watched: at or past {@link #WATCHED} of the length (end credits and black frames are often skipped).
 *       The bar is then full, in another colour, and the video starts from the beginning again.</li>
 *   <li>Under {@link #MIN_RESUME_SECONDS} in: not worth resuming; starts from the beginning, no bar.</li>
 * </ul>
 */
public final class WatchProgress {

    /** The share of a video's length after which it counts as watched. */
    public static final double WATCHED = 0.95;
    /** Positions before this many seconds aren't resumed (or shown). */
    public static final double MIN_RESUME_SECONDS = 5;

    private WatchProgress() {
    }

    /** Where to start playing, in seconds, from the saved position and the length (0 if unknown). */
    public static double resumeAt(double savedSeconds, double durationSeconds) {
        if (savedSeconds < MIN_RESUME_SECONDS || isWatched(savedSeconds, durationSeconds)) {
            return 0;
        }
        return durationSeconds > 0 ? Math.min(savedSeconds, durationSeconds) : savedSeconds;
    }

    public static boolean isWatched(double savedSeconds, double durationSeconds) {
        return durationSeconds > 0 && savedSeconds >= durationSeconds * WATCHED;
    }

    /**
     * How much of the thumbnail's bar to fill, 0..1: the share watched, 1 once watched; or -1 for no bar (not
     * started, under {@link #MIN_RESUME_SECONDS}, or the length unknown).
     */
    public static double barFraction(double savedSeconds, double durationSeconds) {
        if (durationSeconds <= 0 || savedSeconds < MIN_RESUME_SECONDS) {
            return -1;
        }
        return isWatched(savedSeconds, durationSeconds) ? 1 : Math.min(1, savedSeconds / durationSeconds);
    }
}
