package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;

/**
 * One photo or video on the Timeline.
 *
 * @param sortDate the {@code media.sort_date} column: date taken as "floating" wall-clock time (stored as
 *                 if it were UTC), or the file's modified time when there is no date taken
 * @param width    display width in pixels (EXIF rotation already applied by the indexer), or 0 if unknown;
 *                 lets the viewer place the thumbnail where the full image will appear
 * @param height   the same for height
 * @param favorite {@code media.favorite}
 * @param hidden   {@code media.hidden}
 * @param trashed  in Prismo's Trash ({@code media.trashed_at} set)
 */
public record TimelineItem(MediaRef ref, long sortDate, int width, int height, boolean favorite, boolean hidden,
        boolean trashed) {

    public TimelineItem withFavorite(boolean newFavorite) {
        return new TimelineItem(ref, sortDate, width, height, newFavorite, hidden, trashed);
    }
}
