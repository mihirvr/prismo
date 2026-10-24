package io.github.mihirvr.prismo.thumbs;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The thumbnails on disk as a whole (Settings › Performance): how much room they take, and clearing them. Only
 * {@code <cache dir>/thumbs} is touched; the cache folder's {@code native} folder (FFmpeg's unpacked files) is not.
 * Cleared thumbnails are made again as they're needed. Disk work: call off the UI thread.
 */
public final class ThumbnailFolder {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailFolder.class);

    private ThumbnailFolder() {
    }

    /** Bytes taken by every thumbnail size (and failure marker); 0 if there are none yet. */
    public static long size(Path cacheDir) throws IOException {
        Path thumbs = cacheDir.resolve("thumbs");
        if (!Files.isDirectory(thumbs)) {
            return 0;
        }
        long[] total = {0};
        Files.walkFileTree(thumbs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE; // just being written or removed: not counted
            }
        });
        return total[0];
    }

    /**
     * Deletes only the failure markers (every thumbnail size), so the files that failed are tried again; thumbnails
     * stay. Returns how many were deleted.
     */
    public static int forgetFailures(Path cacheDir) throws IOException {
        Path thumbs = cacheDir.resolve("thumbs");
        if (!Files.isDirectory(thumbs)) {
            return 0;
        }
        int[] forgotten = {0};
        Files.walkFileTree(thumbs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".fail")) {
                    try {
                        Files.delete(file);
                        forgotten[0]++;
                    } catch (IOException e) {
                        log.debug("Kept {}: {}", file, e.toString());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return forgotten[0];
    }

    /**
     * Deletes every thumbnail and failure marker (so files that failed are tried again). A file that can't be
     * deleted right now (being written) is left. Returns the bytes freed.
     */
    public static long clear(Path cacheDir) throws IOException {
        Path thumbs = cacheDir.resolve("thumbs");
        if (!Files.isDirectory(thumbs)) {
            return 0;
        }
        long[] freed = {0};
        Files.walkFileTree(thumbs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.delete(file);
                    freed[0] += attrs.size();
                } catch (IOException e) {
                    log.debug("Kept {}: {}", file, e.toString());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                if (!dir.equals(thumbs)) {
                    try {
                        Files.deleteIfExists(dir); // only goes if empty
                    } catch (IOException notEmpty) {
                        // a thumbnail was written meanwhile: keep the folder
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        log.info("Cleared {} bytes of thumbnails", freed[0]);
        return freed[0];
    }
}
