package io.github.mihirvr.prismo.viewer;

/**
 * Zoom, pan and rotation of one image in the viewer. Pure maths with no JavaFX, so it's unit-tested.
 *
 * <p><b>Units.</b> The image is measured in its own full-resolution pixels, already the right way up
 * (EXIF orientation applied). The view is measured in JavaFX layout units. On a 125% scaled screen one
 * layout unit is 1.25 physical pixels, so "100%" (one image pixel per screen pixel) is a scale of
 * {@code 1 / pixelScale}, not 1.
 *
 * <p><b>State.</b> {@code scale} (layout units per image pixel), {@code offset} (where the image's centre is,
 * relative to the view's centre) and a quarter-turn count. Everything the screen needs is derived from those.
 *
 * <p><b>Rules.</b> "Fit" shows the whole image, but never above 100% (small images stay sharp). The image can't
 * be zoomed out below fit or in beyond {@link #MAX_ZOOM} × 100%. It can't be dragged so far that a gap opens
 * on a side where it's bigger than the view; a side that fits stays centred.
 */
final class ViewTransform {

    /** Most zoom allowed, as a multiple of 100% (one image pixel per screen pixel). */
    static final double MAX_ZOOM = 8;
    private static final double EPSILON = 1e-9;

    private double imageWidth = 1;
    private double imageHeight = 1;
    private double viewWidth = 1;
    private double viewHeight = 1;
    private double pixelScale = 1;
    private int quarterTurns;
    private double scale = 1;
    private double offsetX;
    private double offsetY;
    /** True while showing "fit": a window resize then re-fits instead of keeping the zoom. */
    private boolean fitted = true;

    /** A new image (full-resolution size, already oriented): rotation back to 0, fitted. */
    void setImage(double width, double height) {
        imageWidth = Math.max(1, width);
        imageHeight = Math.max(1, height);
        quarterTurns = 0;
        fit();
    }

    /**
     * The view's size and the screen's output scale changed. Keeps "fit" if it was fitted, otherwise keeps
     * the zoom and pulls the image back into bounds.
     */
    void setView(double width, double height, double screenPixelScale) {
        viewWidth = Math.max(1, width);
        viewHeight = Math.max(1, height);
        pixelScale = screenPixelScale > 0 ? screenPixelScale : 1;
        if (fitted) {
            fit();
        } else {
            scale = Math.clamp(scale, minScale(), maxScale());
            clampOffset();
        }
    }

    void fit() {
        scale = fitScale();
        offsetX = 0;
        offsetY = 0;
        fitted = true;
    }

    /**
     * Zooms by {@code factor} (above 1 = in) around the point (x, y) in view coordinates, so the image
     * point under it stays under it, unless that would pull the image out of bounds.
     */
    void zoomAt(double factor, double x, double y) {
        if (!(factor > 0)) {
            return; // 0, negative or NaN
        }
        double next = Math.clamp(scale * factor, minScale(), maxScale());
        double ratio = next / scale;
        // The cursor relative to the view centre (px, py), and relative to the image centre (px - offset).
        // After scaling, that image point is (px - offset) * ratio from the image centre; keep it at px.
        double px = x - viewWidth / 2;
        double py = y - viewHeight / 2;
        offsetX = px - (px - offsetX) * ratio;
        offsetY = py - (py - offsetY) * ratio;
        scale = next;
        fitted = Math.abs(scale - fitScale()) < EPSILON;
        if (fitted) {
            offsetX = 0;
            offsetY = 0;
        } else {
            clampOffset();
        }
    }

    /** Zooms around the view's centre (keyboard + / -). */
    void zoomAtCentre(double factor) {
        zoomAt(factor, viewWidth / 2, viewHeight / 2);
    }

    /** Double-click: fitted → 100% around the point; zoomed → back to fit. */
    void toggleFitActual(double x, double y) {
        if (fitted) {
            zoomAt(actualScale() / scale, x, y); // no change if the image already fits at 100%
        } else {
            fit();
        }
    }

    /** Drags the image by (dx, dy) layout units. Does nothing on a side that fits in the view. */
    void panBy(double dx, double dy) {
        offsetX += dx;
        offsetY += dy;
        clampOffset();
    }

    /** Turns the view a quarter clockwise and fits the turned image. Never changes the file. */
    void rotateClockwise() {
        quarterTurns = (quarterTurns + 1) % 4;
        fit();
    }

    // --- What the screen needs ---

    /** The image's full-resolution size, as last given to {@link #setImage}. */
    double imageWidth() {
        return imageWidth;
    }

    double imageHeight() {
        return imageHeight;
    }

    /** Layout units per image pixel. */
    double scale() {
        return scale;
    }

    /** The image's size on screen before rotation (the node is sized to this, then turned about its centre). */
    double displayWidth() {
        return imageWidth * scale;
    }

    double displayHeight() {
        return imageHeight * scale;
    }

    /** Where the image's centre goes, in view coordinates. Rotation is about that centre. */
    double centreX() {
        return viewWidth / 2 + offsetX;
    }

    double centreY() {
        return viewHeight / 2 + offsetY;
    }

    double rotationDegrees() {
        return quarterTurns * 90;
    }

    boolean isFitted() {
        return fitted;
    }

    /** True if the turned image is bigger than the view on some side, so dragging moves it. */
    boolean canPan() {
        return turnedWidth() * scale > viewWidth + EPSILON || turnedHeight() * scale > viewHeight + EPSILON;
    }

    /** Zoom as a percentage of 100% (one image pixel per screen pixel), for display. */
    double zoomPercent() {
        return scale / actualScale() * 100;
    }

    // --- Internals ---

    private double actualScale() {
        return 1 / pixelScale;
    }

    private double fitScale() {
        return Math.min(actualScale(), Math.min(viewWidth / turnedWidth(), viewHeight / turnedHeight()));
    }

    private double minScale() {
        return fitScale();
    }

    private double maxScale() {
        return Math.max(fitScale(), actualScale() * MAX_ZOOM);
    }

    private double turnedWidth() {
        return quarterTurns % 2 == 0 ? imageWidth : imageHeight;
    }

    private double turnedHeight() {
        return quarterTurns % 2 == 0 ? imageHeight : imageWidth;
    }

    private void clampOffset() {
        double maxX = Math.max(0, (turnedWidth() * scale - viewWidth) / 2);
        double maxY = Math.max(0, (turnedHeight() * scale - viewHeight) / 2);
        offsetX = Math.clamp(offsetX, -maxX, maxX);
        offsetY = Math.clamp(offsetY, -maxY, maxY);
    }
}
