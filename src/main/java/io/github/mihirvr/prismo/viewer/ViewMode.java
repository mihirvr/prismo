package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.thumbs.OtherStills;
import io.github.mihirvr.prismo.thumbs.ThumbnailGenerator;
import java.util.Locale;

/** How the viewer shows a file, decided by its extension. */
enum ViewMode {
    /** Decoded with ImageIO (JPEG, PNG, WEBP, BMP, TIFF…): EXIF rotation applied, size capped. */
    STILL,
    /** GIF, through JavaFX's own {@code Image}, which plays the animation. */
    ANIMATED,
    /** Played in the app with FFmpeg: every video type Prismo indexes, and (2.0.0) every audio type. */
    VIDEO,
    /** Not shown in the app: thumbnail or name + "Open with default app". */
    EXTERNAL;

    static ViewMode of(String extension) {
        String ext = extension.toLowerCase(Locale.ROOT);
        if (ext.equals("gif")) {
            return ANIMATED;
        }
        MediaType type = MediaType.fromExtension(ext).orElse(null);
        if (type == MediaType.VIDEO || type == MediaType.AUDIO) {
            return VIDEO;
        }
        // HEIC and RAW are STILL too since P7: StillDecoder reads them through OtherStills.
        return ThumbnailGenerator.canDecode(ext) || OtherStills.handles(ext) ? STILL : EXTERNAL;
    }

    /** The slideshow shows only pictures the app can draw itself. */
    boolean inSlideshow() {
        return this == STILL || this == ANIMATED;
    }
}
