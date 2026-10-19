package io.github.mihirvr.prismo.index;

import java.time.Duration;

/**
 * A snapshot of an indexing run, sent to the progress listener.
 *
 * @param found     media files seen so far (by extension)
 * @param indexed   new or changed files written to the database
 * @param unchanged files skipped because size and modified time match the database
 * @param failed    files or folders that couldn't be read at all
 * @param moved     files found at a new path (moved or renamed); their rows kept their state. Set at the end.
 * @param removed   rows removed because their file is gone. Set at the end.
 * @param done      true for the final snapshot of the run
 */
public record IndexProgress(int found, int indexed, int unchanged, int failed, int moved, int removed, boolean done,
        Duration elapsed) {

    /** True if the run changed anything in the database. */
    public boolean changedAnything() {
        return indexed > 0 || moved > 0 || removed > 0;
    }
}
