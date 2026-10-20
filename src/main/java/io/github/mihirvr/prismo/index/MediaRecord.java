package io.github.mihirvr.prismo.index;

/**
 * Everything the indexer learned about one file, ready to be written to the {@code media} table.
 * Nullable fields are null when the file has no such metadata (or it couldn't be read).
 *
 * @param dateModified file's last-modified time, epoch milliseconds (a real instant)
 * @param dateTaken    capture time as "floating" local wall-clock time, see {@link MetadataReader}
 * @param width        display width, i.e. already swapped for photos rotated 90°
 * @param height       display height
 * @param osHidden     the file has the operating system's Hidden mark ({@code platform.OsHiddenMark}); read from
 *                     the folder walk's attributes, not from the file's contents
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
        Double durationSeconds,
        boolean osHidden) {

    /** A file without the Hidden mark (what {@link MetadataReader} returns; the indexer adds the mark). */
    public MediaRecord(String path, String folder, String fileName, String extension, MediaType type, long sizeBytes,
            long dateModified, Long dateTaken, Integer width, Integer height, String cameraMake, String cameraModel,
            Double durationSeconds) {
        this(path, folder, fileName, extension, type, sizeBytes, dateModified, dateTaken, width, height, cameraMake,
                cameraModel, durationSeconds, false);
    }

    public MediaRecord withOsHidden(boolean newOsHidden) {
        return new MediaRecord(path, folder, fileName, extension, type, sizeBytes, dateModified, dateTaken, width,
                height, cameraMake, cameraModel, durationSeconds, newOsHidden);
    }
}
