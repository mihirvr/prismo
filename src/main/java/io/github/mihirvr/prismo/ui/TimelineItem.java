package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;

/**
 * One photo or video on the Timeline.
 *
 * @param sortDate the {@code media.sort_date} column: date taken as "floating" wall-clock time (stored as
 *                 if it were UTC), or the file's modified time when there is no date taken
 */
public record TimelineItem(MediaRef ref, long sortDate) {
}
