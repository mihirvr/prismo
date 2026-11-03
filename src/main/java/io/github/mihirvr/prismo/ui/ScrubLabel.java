package io.github.mihirvr.prismo.ui;

import java.util.List;
import java.util.function.IntFunction;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

/**
 * The scroll-bar scrub label (1.1.0): while the scroll bar is dragged or hovered, a small label next to it says
 * what is at that height of the grid: the letter (Name sort), the date (date sorts) or the folder (Folders view).
 *
 * <p>Where the mouse is on the bar is turned into a row by the rows' estimated heights ({@link #rowAt}); the label
 * is the header above that row, or what {@code fallback} says for a grid without headers (null = no label).
 */
final class ScrubLabel {

    /** About the height of a header row (CSS {@code .timeline-header}: 18 + 8 padding and a 16px line). */
    static final double HEADER_HEIGHT = 48;

    private final Label label = new Label();
    private final ListView<TimelineRow> list;
    private final java.util.function.DoubleSupplier rowHeight;
    private final IntFunction<String> fallback;
    private ScrollBar bar;
    private boolean dragging;

    /**
     * @param owner     the pane the label floats in, over the list
     * @param rowHeight the height of a row of thumbnails now
     * @param fallback  the label for row {@code i} of a grid without headers (or null for none)
     */
    ScrubLabel(StackPane owner, ListView<TimelineRow> list, java.util.function.DoubleSupplier rowHeight,
            IntFunction<String> fallback) {
        this.list = list;
        this.rowHeight = rowHeight;
        this.fallback = fallback;
        label.getStyleClass().add("scrub-label");
        label.setVisible(false);
        label.setMouseTransparent(true);
        label.setManaged(false);
        owner.getChildren().add(label);
        StackPane.setAlignment(label, Pos.TOP_RIGHT);
        list.skinProperty().addListener((_, _, skin) -> {
            if (skin != null) {
                javafx.application.Platform.runLater(this::attach);
            }
        });
    }

    /** Finds the list's vertical scroll bar once it exists and listens to the mouse on it. */
    private void attach() {
        for (var node : list.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar scrollBar && scrollBar.getOrientation() == Orientation.VERTICAL
                    && scrollBar != bar) {
                bar = scrollBar;
                bar.addEventFilter(MouseEvent.MOUSE_MOVED, this::follow);
                bar.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
                    dragging = true;
                    follow(event);
                });
                bar.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
                    dragging = false;
                    if (!bar.isHover()) {
                        label.setVisible(false);
                    }
                });
                bar.addEventFilter(MouseEvent.MOUSE_EXITED, _ -> {
                    if (!dragging) {
                        label.setVisible(false);
                    }
                });
            }
        }
    }

    private void follow(MouseEvent event) {
        List<TimelineRow> rows = list.getItems();
        double trackHeight = bar.getHeight();
        if (rows.isEmpty() || trackHeight <= 0) {
            label.setVisible(false);
            return;
        }
        double y = Math.clamp(event.getY(), 0, trackHeight);
        int row = rowAt(rows, rowHeight.getAsDouble(), HEADER_HEIGHT, y / trackHeight);
        String text = labelFor(rows, row, fallback);
        if (text == null) {
            label.setVisible(false);
            return;
        }
        label.setText(text);
        label.autosize();
        // Next to the bar, at the mouse, kept inside the grid.
        double inOwner = bar.localToScene(0, y).getY() - label.getParent().localToScene(0, 0).getY();
        double width = label.prefWidth(-1);
        double height = label.prefHeight(width);
        double ownerWidth = ((StackPane) label.getParent()).getWidth();
        double ownerHeight = ((StackPane) label.getParent()).getHeight();
        label.resizeRelocate(ownerWidth - bar.getWidth() - width - 10,
                Math.clamp(inOwner - height / 2, 4, Math.max(4, ownerHeight - height - 4)), width, height);
        label.setVisible(true);
        label.toFront();
    }

    /** The row at {@code fraction} (0 = top, 1 = bottom) of the whole list, by the rows' estimated heights. */
    static int rowAt(List<TimelineRow> rows, double rowHeight, double headerHeight, double fraction) {
        double total = 0;
        for (TimelineRow row : rows) {
            total += row instanceof TimelineRow.Header ? headerHeight : rowHeight;
        }
        double target = Math.clamp(fraction, 0, 1) * total;
        double sum = 0;
        for (int i = 0; i < rows.size(); i++) {
            sum += rows.get(i) instanceof TimelineRow.Header ? headerHeight : rowHeight;
            if (sum >= target) {
                return i;
            }
        }
        return rows.size() - 1;
    }

    /** The header at or above row {@code index}; without one, what {@code fallback} says (may be null). */
    static String labelFor(List<TimelineRow> rows, int index, IntFunction<String> fallback) {
        for (int i = Math.min(index, rows.size() - 1); i >= 0; i--) {
            if (rows.get(i) instanceof TimelineRow.Header header) {
                return header.label();
            }
        }
        return fallback == null ? null : fallback.apply(index);
    }

    /** For tests and tools. */
    Label label() {
        return label;
    }
}
