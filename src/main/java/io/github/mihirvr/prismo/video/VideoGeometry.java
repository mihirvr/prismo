package io.github.mihirvr.prismo.video;

/** Pure size and rotation maths for the player. */
final class VideoGeometry {

    private VideoGeometry() {
    }

    /**
     * The size to decode pictures at: the video's own size, made smaller (never bigger) to fit
     * {@code maxWidth} × {@code maxHeight} on screen, keeping its shape. A video shown turned by 90° or 270°
     * stands on its side, so its width has to fit the screen's height. Both sides are rounded to even
     * numbers (the scaler works on 2 × 2 blocks of colour), downwards so the result never exceeds the video
     * or the screen, and at least 2.
     *
     * @return {width, height} in the video's own (unturned) orientation
     */
    static int[] decodeSize(int width, int height, int rotation, int maxWidth, int maxHeight) {
        boolean sideways = rotation == 90 || rotation == 270;
        int boxWidth = sideways ? maxHeight : maxWidth;
        int boxHeight = sideways ? maxWidth : maxHeight;
        double scale = Math.min(1.0, Math.min((double) boxWidth / width, (double) boxHeight / height));
        return new int[] {even(width * scale), even(height * scale)};
    }

    private static int even(double side) {
        return Math.max(2, (int) (side / 2) * 2);
    }

    /**
     * FFmpeg's display rotation (degrees counter-clockwise, any value, from the file's "display matrix")
     * as the clockwise quarter turn to show the picture at: 0, 90, 180 or 270. Phones film upright videos
     * as sideways pictures plus this tag.
     */
    static int clockwiseQuarterTurn(double counterClockwiseDegrees) {
        long quarters = Math.round(-counterClockwiseDegrees / 90);
        return (int) Math.floorMod(quarters * 90, 360L);
    }
}
