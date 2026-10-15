package io.github.mihirvr.prismo.ui;

/**
 * How many thumbnails fit across, and how big each one is.
 *
 * <p>As many columns as fit at the chosen size, then the cells grow a little so the row fills the width
 * exactly, with no ragged gap on the right.
 */
record GridLayout(int columns, double cellSize) {

    /**
     * @param width      the width available for one row
     * @param targetSize the thumbnail size the user picked
     * @param gap        space between two thumbnails
     */
    static GridLayout fit(double width, double targetSize, double gap) {
        if (width <= 0 || targetSize <= 0) {
            return new GridLayout(1, Math.max(1, targetSize)); // not laid out yet
        }
        int columns = Math.max(1, (int) ((width + gap) / (targetSize + gap)));
        // Whole pixels: fractional sizes would blur the images' edges.
        double cell = Math.floor((width - gap * (columns - 1)) / columns);
        return new GridLayout(columns, Math.max(1, cell));
    }
}
