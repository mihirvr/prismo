package io.github.mihirvr.prismo.thumbs;

import java.nio.file.Path;

/**
 * The minimum the thumbnail code needs to know about one indexed file.
 *
 * @param dateModified the source file's modified time when it was indexed; a thumbnail is fresh only
 *                     while it carries this same time
 */
public record MediaRef(long id, Path path, String extension, long dateModified) {
}
