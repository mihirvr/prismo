package io.github.mihirvr.prismo.thumbs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where thumbnails live on disk and whether one is still valid.
 *
 * <p>Layout: {@code <cache dir>/thumbs/<size>/<shard>/<media id>.jpg}, where shard is the media id's
 * lowest byte as two hex digits (00–ff). 256 sub-folders keep any one folder from holding hundreds of
 * thousands of files, which slows down directory operations on every file system.
 *
 * <p><b>Freshness without a database.</b> When a thumbnail is written, its file's modified time is set
 * to the source file's modified time. A thumbnail is fresh while those two match. If the photo is
 * edited, its modified time changes and the thumbnail is regenerated. A {@code .fail} marker works the
 * same way for files that couldn't be decoded, so they aren't retried until they change.
 */
public final class ThumbnailCache {

    private final Path root;

    /** @param cacheDir the app's cache folder ({@code AppPaths.cacheDir()}) */
    public ThumbnailCache(Path cacheDir, int size) {
        this.root = cacheDir.resolve("thumbs").resolve(Integer.toString(size));
    }

    public Path fileFor(long mediaId) {
        return shard(mediaId).resolve(mediaId + ".jpg");
    }

    Path failMarkerFor(long mediaId) {
        return shard(mediaId).resolve(mediaId + ".fail");
    }

    private Path shard(long mediaId) {
        return root.resolve("%02x".formatted(mediaId & 0xFF));
    }

    /** True if {@code file} exists and was stamped with exactly {@code sourceModified}. */
    static boolean isFresh(Path file, long sourceModified) {
        try {
            return Files.getLastModifiedTime(file).toMillis() == sourceModified;
        } catch (IOException e) {
            return false; // missing (NoSuchFileException) or unreadable: not fresh
        }
    }
}
