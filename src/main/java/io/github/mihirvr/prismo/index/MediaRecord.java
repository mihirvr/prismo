package io.github.mihirvr.prismo.index;

/**
 * Everything the indexer learned about one file, ready to be written to the {@code media} table.
 * Nullable fields are null when the file has no such metadata (or it couldn't be read).
 *
 * @param dateModified file's last-modified time, epoch milliseconds (a real instant)
 * @param dateTaken    capture time as "floating" local wall-clock time, see {@link MetadataReader}
 * @param width        display width, i.e. already swapped for photos rotated 90°
 * @param height       display height
 */
public record MediaRecord(
        String path,
        String folder,
        String fileName,
        String extension,
        MediaType type,
        long sizeBytes,
        long dateModified,
        Long dateTaken,
        Integer width,
        Integer height,
        String cameraMake,
        String cameraModel,
        Double durationSeconds) {
}
