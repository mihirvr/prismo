package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.thumbs.ThumbnailGenerator;
import java.util.Locale;
import java.util.Set;

/** How the viewer shows a file, decided by its extension. */
enum ViewMode {
    /** Decoded with ImageIO (JPEG, PNG, WEBP, BMP, TIFF…): EXIF rotation applied, size capped. */
    STILL,
    /** GIF, through JavaFX's own {@code Image}, which plays the animation. */
    ANIMATED,
    /** Played in the app with JavaFX Media: MP4 / M4V (H.264 or H.265 video, AAC audio). */
    VIDEO,
    /** Not shown in the app yet (MOV, MKV, WebM, AVI, HEIC, RAW): thumbnail or name + "Open with default app". */
    EXTERNAL;

    /** The containers JavaFX Media lists as supported. Whether H.265 inside them plays depends on the OS's codecs. */
    private static final Set<String> PLAYABLE_VIDEO = Set.of("mp4", "m4v");

    static ViewMode of(String extension) {
        String ext = extension.toLowerCase(Locale.ROOT);
        if (ext.equals("gif")) {
            return ANIMATED;
        }
        if (MediaType.fromExtension(ext).orElse(null) == MediaType.VIDEO) {
            return PLAYABLE_VIDEO.contains(ext) ? VIDEO : EXTERNAL;
        }
        return ThumbnailGenerator.canDecode(ext) ? STILL : EXTERNAL;
    }

    /** The slideshow shows only pictures the app can draw itself. */
    boolean inSlideshow() {
        return this == STILL || this == ANIMATED;
    }
}
