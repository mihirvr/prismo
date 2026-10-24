package io.github.mihirvr.prismo.thumbs;

import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.platform.FileReads;
import io.github.mihirvr.prismo.video.VideoNatives;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Turns one image file into a small JPEG: at most {@code size} pixels on the long edge, the right way up.
 *
 * <ol>
 *   <li><b>Subsampled decode:</b> ImageIO is asked to read only every Nth pixel and row, so a 24 MP
 *       photo is decoded at about twice the target size, never at full size. This is where most of the
 *       time and memory is saved.</li>
 *   <li><b>Scale + orient:</b> one bilinear draw from that ~2× image to the target size, through an
 *       affine transform for the EXIF orientation (rotate / flip), onto an opaque canvas.</li>
 *   <li><b>Encode:</b> JPEG at quality 0.85.</li>
 * </ol>
 *
 * <p>A JPEG whose EXIF holds a preview as big as the thumbnail and of the photo's shape skips all of that: the
 * preview is scaled and turned instead, and only the start of the file is read ({@link EmbeddedPreview}, 1.0.1).
 *
 * <p>Videos go to {@link VideoThumbnailer} instead (one frame, by the bundled {@code ffmpeg} program). HEIC and RAW
 * photos are read by {@link OtherStills} (ffmpeg, or the JPEG preview inside the RAW file), then scaled the same way.
 *
 * <p>Pure CPU + file work with no shared state, so it's safe to call from many threads at once.
 */
public final class ThumbnailGenerator {

    /**
     * Raised when a change can make thumbnails of files that failed before; their failure markers are then forgotten
     * once, so they are tried again. 2 = 1.0.1 (tiled HEIC).
     */
    public static final int REVISION = 2;
    private static final float JPEG_QUALITY = 0.85f;
    private static final Set<String> JPEG = Set.of("jpg", "jpeg", "jpe", "jfif");
    /** An image this small is read whole even in the warm-up's quick pass: it costs about as much as a preview. */
    static final long QUICK_BYTES = 1024 * 1024;

    private final int size;
    private final VideoThumbnailer videos;
    private final VideoThumbnailer.Program ffmpeg;

    public ThumbnailGenerator(int size) {
        this(size, VideoNatives::ffmpegExecutable);
    }

    /** {@code ffmpeg} finds the program for video thumbnails (tests pass a missing one). */
    ThumbnailGenerator(int size, VideoThumbnailer.Program ffmpeg) {
        this.size = size;
        this.videos = new VideoThumbnailer(ffmpeg, size);
        this.ffmpeg = ffmpeg;
    }

    /** True if some installed ImageIO reader handles this extension (JDK readers + TwelveMonkeys plugins). */
    public static boolean canDecode(String extension) {
        return ImageIO.getImageReadersBySuffix(extension).hasNext();
    }

    /** True if a thumbnail can be made for this extension: an image ImageIO decodes, HEIC / RAW, or a video. */
    public static boolean canMake(String extension) {
        return isVideo(extension) || canDecode(extension) || OtherStills.handles(extension);
    }

    private static boolean isVideo(String extension) {
        return MediaType.fromExtension(extension).orElse(null) == MediaType.VIDEO;
    }

    public void generate(Path source, Path target) throws IOException {
        String extension = MediaType.extensionOf(source.getFileName().toString());
        if (isVideo(extension)) {
            videos.generate(source, target);
            return;
        }
        if (!canDecode(extension) && OtherStills.handles(extension)) {
            // HEIF scaled by ffmpeg, a RAW's JPEG preview subsampled like any JPEG: never decoded whole (P7).
            byte[] picture = OtherStills.encoded(source, ffmpeg::path, size * 2);
            BufferedImage decoded;
            try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(picture))) {
                decoded = decodeSubsampled(in, source);
            }
            int orientation = OtherStills.needsExifRotation(extension) ? ExifOrientation.read(source) : 1;
            writeJpeg(scaleAndOrient(decoded, orientation), target);
            return;
        }
        if (JPEG.contains(extension.toLowerCase(Locale.ROOT))) {
            BufferedImage fromPreview = fromEmbeddedPreview(source);
            if (fromPreview != null) {
                writeJpeg(fromPreview, target);
                return;
            }
        }
        BufferedImage decoded;
        try (ImageInputStream in = FileReads.image(source)) {
            decoded = decodeSubsampled(in, source);
        }
        BufferedImage thumbnail = scaleAndOrient(decoded, ExifOrientation.read(source));
        writeJpeg(thumbnail, target);
    }

    /**
     * The thumbnail made from the preview stored in the JPEG's EXIF, reading only the start of the file, or null when
     * there is none that fits ({@link EmbeddedPreview#fits}): then the whole photo is decoded as before.
     */
    private BufferedImage fromEmbeddedPreview(Path source) throws IOException {
        byte[] header;
        try (InputStream in = Files.newInputStream(source)) { // a plain stream: the file is never locked
            header = in.readNBytes(EmbeddedPreview.HEADER_BYTES);
        }
        EmbeddedPreview.Found found = EmbeddedPreview.find(header).orElse(null);
        if (found == null) {
            return null;
        }
        BufferedImage preview;
        try {
            preview = ImageIO.read(new ByteArrayInputStream(found.jpeg()));
        } catch (IOException | RuntimeException e) {
            return null; // a damaged preview: the photo itself may be fine
        }
        if (preview == null || !EmbeddedPreview.fits(preview.getWidth(), preview.getHeight(), found, size)) {
            return null;
        }
        return scaleAndOrient(preview, found.orientation());
    }

    /**
     * The warm-up's quick pass (1.0.1): makes the thumbnail only if little of the file has to be read: a JPEG whose EXIF
     * preview fits, or an image of at most {@link #QUICK_BYTES}. Returns false, writing nothing, for anything else
     * (big photos without a usable preview, HEIC, RAW, videos): those come in the second pass.
     */
    public boolean generateQuick(Path source, Path target) throws IOException {
        String extension = MediaType.extensionOf(source.getFileName().toString());
        if (isVideo(extension) || !canDecode(extension)) {
            return false;
        }
        if (JPEG.contains(extension.toLowerCase(Locale.ROOT))) {
            BufferedImage fromPreview = fromEmbeddedPreview(source);
            if (fromPreview != null) {
                writeJpeg(fromPreview, target);
                return true;
            }
        }
        if (Files.size(source) > QUICK_BYTES) {
            return false;
        }
        generate(source, target);
        return true;
    }

    /** Decodes {@code in} (the picture of {@code source}) at about twice the thumbnail size, skipping pixels. */
    private BufferedImage decodeSubsampled(ImageInputStream in, Path source) throws IOException {
        if (in == null) {
            throw new IOException("Cannot open " + source);
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) {
            throw new IOException("No image reader recognises " + source);
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(in, true, true); // forward-only, skip metadata: faster
            int longEdge = Math.max(reader.getWidth(0), reader.getHeight(0));
            int step = Math.max(1, longEdge / (size * 2));
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(step, step, 0, 0);
            return reader.read(0, param);
        } finally {
            reader.dispose();
        }
    }

    BufferedImage scaleAndOrient(BufferedImage image, int orientation) {
        double scale = Math.min(1.0, (double) size / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        boolean swap = ExifOrientation.swapsSides(orientation);

        BufferedImage canvas = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE); // transparent PNG/GIF areas become white (JPEG has no alpha)
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.transform(ExifOrientation.transform(orientation, w, h));
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static void writeJpeg(BufferedImage image, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
