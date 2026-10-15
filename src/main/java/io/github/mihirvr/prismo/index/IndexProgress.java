package io.github.mihirvr.prismo.index;

import java.time.Duration;

/**
 * A snapshot of an indexing run, sent to the progress listener.
 *
 * @param found     media files seen so far (by extension)
 * @param indexed   new or changed files written to the database
 * @param unchanged files skipped because size and modified time match the database
 * @param failed    files or folders that couldn't be read at all
 * @param done      true for the final snapshot of the run
 */
public record IndexProgress(int found, int indexed, int unchanged, int failed, boolean done, Duration elapsed) {
}
