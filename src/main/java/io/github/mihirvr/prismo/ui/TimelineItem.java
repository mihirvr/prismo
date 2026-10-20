package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.util.Locale;

/**
 * One photo or video on the Timeline.
 *
 * @param sortDate the {@code media.sort_date} column: date taken as "floating" wall-clock time (stored as
 *                 if it were UTC), or the file's modified time when there is no date taken
 * @param width    display width in pixels (EXIF rotation already applied by the indexer), or 0 if unknown;
 *                 lets the viewer place the thumbnail where the full image will appear
 * @param height   the same for height
 * @param favorite {@code media.favorite}
 * @param hidden   {@code media.hidden}: Prismo's own Hide
 * @param trashed  in Prismo's Trash ({@code media.trashed_at} set)
 * @param durationSeconds a video's length ({@code media.duration_seconds}), or 0 if unknown or not a video
 * @param watchedSeconds  where a video was left ({@code media.playback_position_seconds}), or 0
 * @param osHidden the file has the operating system's Hidden mark ({@code media.os_hidden}, P6)
 */
public record TimelineItem(MediaRef ref, long sortDate, int width, int height, boolean favorite, boolean hidden,
        boolean trashed, double durationSeconds, double watchedSeconds, boolean osHidden) {

    /** A file without the operating system's Hidden mark. */
    public TimelineItem(MediaRef ref, long sortDate, int width, int height, boolean favorite, boolean hidden,
            boolean trashed, double durationSeconds, double watchedSeconds) {
        this(ref, sortDate, width, height, favorite, hidden, trashed, durationSeconds, watchedSeconds, false);
    }

    /** An item never played (tests, and items made before P6). */
    public TimelineItem(MediaRef ref, long sortDate, int width, int height, boolean favorite, boolean hidden,
            boolean trashed, double durationSeconds) {
        this(ref, sortDate, width, height, favorite, hidden, trashed, durationSeconds, 0);
    }

    /** An item without a known length (photos; tests). */
    public TimelineItem(MediaRef ref, long sortDate, int width, int height, boolean favorite, boolean hidden,
            boolean trashed) {
        this(ref, sortDate, width, height, favorite, hidden, trashed, 0, 0);
    }

    /** The same item after its file was renamed or moved (same id, so its state and thumbnail stay). */
    public TimelineItem withPath(Path newPath) {
        String name = newPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return new TimelineItem(new MediaRef(ref.id(), newPath, extension, ref.dateModified()), sortDate, width,
                height, favorite, hidden, trashed, durationSeconds, watchedSeconds, osHidden);
    }

    public TimelineItem withFavorite(boolean newFavorite) {
        return new TimelineItem(ref, sortDate, width, height, newFavorite, hidden, trashed, durationSeconds,
                watchedSeconds, osHidden);
    }

    public TimelineItem withOsHidden(boolean newOsHidden) {
        return new TimelineItem(ref, sortDate, width, height, favorite, hidden, trashed, durationSeconds,
                watchedSeconds, newOsHidden);
    }

    /** Shown only on Collections › Hidden: hidden in Prismo, or marked Hidden in the operating system. */
    public boolean hiddenAnyWay() {
        return hidden || osHidden;
    }
}
