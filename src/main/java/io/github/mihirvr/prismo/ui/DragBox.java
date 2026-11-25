package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Which tiles a drag-select box touches (1.4.1, drag from empty space as in File Explorer). The grid is virtual (only
 * the rows on screen exist), so the box is kept in rows, not pixels: each corner is a row of the list and a height
 * inside that row. Every thumbnail row has its tiles at the top ({@code cellSize} high), then the gap. Columns sit at
 * {@code column × (cellSize + gap)} from the row's left edge. No JavaFX: unit-tested.
 */
final class DragBox {

    /** A corner of the box: row {@code row} of the list, {@code y} pixels down inside that row (may be outside it). */
    record Corner(int row, double y) {
    }

    private DragBox() {
    }

    /**
     * The media ids of the tiles the box touches, in row order.
     *
     * @param a      where the drag started
     * @param b      where the pointer is now
     * @param x1     one side of the box, in the rows' own x (0 = left edge of the first tile)
     * @param x2     the other side
     */
    static List<Long> hits(List<TimelineRow> rows, Corner a, Corner b, double x1, double x2, double cellSize,
            double gap) {
        Corner top = a.row() < b.row() || a.row() == b.row() && a.y() <= b.y() ? a : b;
        Corner bottom = top == a ? b : a;
        double left = Math.min(x1, x2);
        double right = Math.max(x1, x2);
        double step = cellSize + gap;
        List<Long> ids = new ArrayList<>();
        int from = Math.max(0, top.row());
        int to = Math.min(rows.size() - 1, bottom.row());
        for (int r = from; r <= to; r++) {
            if (!(rows.get(r) instanceof TimelineRow.Thumbs(List<TimelineItem> items))) {
                continue;
            }
            double boxTop = r == top.row() ? top.y() : Double.NEGATIVE_INFINITY;
            double boxBottom = r == bottom.row() ? bottom.y() : Double.POSITIVE_INFINITY;
            if (boxBottom < 0 || boxTop > cellSize) {
                continue; // only the gap under the tiles, or nothing of this row
            }
            for (int column = 0; column < items.size(); column++) {
                double tileLeft = column * step;
                if (right >= tileLeft && left <= tileLeft + cellSize) {
                    ids.add(items.get(column).ref().id());
                }
            }
        }
        return ids;
    }
}
