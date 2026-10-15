package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/**
 * The Timeline: every photo and video, newest first, under date headers, as a grid of square thumbnails.
 *
 * <p><b>Virtualized.</b> JavaFX has no virtualized grid with section headers, so this is a {@link ListView}
 * whose items are <i>rows</i>: a date header, or a row of up to N thumbnails. ListView only creates cells
 * for the rows on screen and reuses them while scrolling, so 100,000 photos still means a few dozen
 * nodes. N comes from the width; when the width or the thumbnail size changes, the rows are rebuilt and
 * the view stays on the same photo.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class TimelineView extends StackPane {

    public static final double MIN_THUMBNAIL_SIZE = 96;
    public static final double MAX_THUMBNAIL_SIZE = 320; // the cached thumbnails' size; bigger would blur
    public static final double DEFAULT_THUMBNAIL_SIZE = 180;

    /** Space between thumbnails, across and down. */
    static final double GAP = 4;
    /**
     * Width kept free for the vertical scroll bar (Modena's is about 15 px), so a row never needs a
     * horizontal one. Reserved even when there's no scroll bar, so the columns don't change when it appears.
     */
    private static final double SCROLL_BAR_ALLOWANCE = 16;
    /** Ctrl + mouse wheel changes the thumbnail size by this factor per notch. */
    private static final double ZOOM_STEP = 1.1;

    private final ListView<TimelineRow> list = new ListView<>();
    private final ThumbnailLoader loader;
    private final Clock clock;
    private final DoubleProperty thumbnailSize = new SimpleDoubleProperty(DEFAULT_THUMBNAIL_SIZE);
    private List<TimelineItem> items = List.of();
    private GridLayout layout = new GridLayout(1, DEFAULT_THUMBNAIL_SIZE);

    public TimelineView(ThumbnailService thumbnails) {
        this(thumbnails, Clock.systemDefaultZone());
    }

    TimelineView(ThumbnailService thumbnails, Clock clock) {
        this.loader = new ThumbnailLoader(thumbnails);
        this.clock = clock;
        getStyleClass().add("timeline");
        list.getStyleClass().add("timeline-list");
        list.setCellFactory(_ -> new RowCell());
        // No keyboard focus: no focus ring or selected-row colour. Keyboard navigation comes with the Viewer.
        list.setFocusTraversable(false);
        getChildren().add(list);

        list.widthProperty().addListener((_, _, _) -> relayout());
        thumbnailSize.addListener((_, _, _) -> relayout());
        // Ctrl + wheel (Cmd on macOS = "shortcut") zooms instead of scrolling, like a phone's pinch.
        list.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.isShortcutDown() && event.getDeltaY() != 0) {
                double factor = event.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP;
                setThumbnailSize(thumbnailSize.get() * factor);
                event.consume();
            }
        });
    }

    /** Replaces everything shown (e.g. after an index run), staying on the same photo if it's still there. */
    public void setItems(List<TimelineItem> newItems) {
        items = List.copyOf(newItems); // rows hold views into this list, so it must never change
        rebuild();
    }

    /** The thumbnail size the user picked, in pixels; the real size stretches it to fill the row. */
    public DoubleProperty thumbnailSizeProperty() {
        return thumbnailSize;
    }

    public void setThumbnailSize(double size) {
        thumbnailSize.set(Math.clamp(size, MIN_THUMBNAIL_SIZE, MAX_THUMBNAIL_SIZE));
    }

    /** Shown when there is nothing to show. */
    public void setPlaceholder(Node placeholder) {
        list.setPlaceholder(placeholder);
    }

    /** Cancels outstanding loads' lookups. */
    public void close() {
        loader.close();
    }

    /** The list itself, for tests and tools that drive scrolling. */
    ListView<TimelineRow> list() {
        return list;
    }

    private void relayout() {
        double width = list.getWidth() - list.snappedLeftInset() - list.snappedRightInset() - SCROLL_BAR_ALLOWANCE;
        GridLayout next = GridLayout.fit(width, thumbnailSize.get(), GAP);
        if (!next.equals(layout)) {
            layout = next;
            rebuild();
        }
    }

    /** Rebuilds the rows for the current items and layout, keeping the same photo at the top. */
    private void rebuild() {
        Anchor anchor = anchor();
        long today = LocalDate.now(clock).toEpochDay();
        List<TimelineRow> rows = TimelineRows.build(items, layout.columns(), today);
        list.getItems().setAll(rows);
        if (anchor != null) {
            int row = TimelineRows.rowOf(rows, anchor.mediaId());
            if (row >= 0) {
                list.scrollTo(anchor.header() ? row - 1 : row);
            }
        }
    }

    /** Which photo is at the top of the view now, or null at the very top / before the first layout. */
    private Anchor anchor() {
        if (!(list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow)) {
            return null;
        }
        IndexedCell<?> first = flow.getFirstVisibleCell();
        if (first == null || first.getIndex() <= 0 || first.getIndex() >= list.getItems().size()) {
            return null; // at the top: stay at the top
        }
        int index = first.getIndex();
        List<TimelineRow> rows = list.getItems();
        return switch (rows.get(index)) {
            // A header's group starts in the next row.
            case TimelineRow.Header _ when index + 1 < rows.size()
                    && rows.get(index + 1) instanceof TimelineRow.Thumbs(var next) ->
                    new Anchor(next.getFirst().ref().id(), true);
            case TimelineRow.Header _ -> null;
            case TimelineRow.Thumbs(var row) -> new Anchor(row.getFirst().ref().id(), false);
        };
    }

    /** The photo to keep in view across a rebuild; {@code header}: its group's header was the top row. */
    private record Anchor(long mediaId, boolean header) {
    }

    /** One list cell: draws a header or a row of tiles, and keeps its tiles for reuse. */
    private final class RowCell extends ListCell<TimelineRow> {
        private final HBox thumbs = new HBox(GAP);
        private final List<ThumbnailTile> tiles = new ArrayList<>();
        private final Label headerLabel = new Label();
        private final Label headerCount = new Label();
        private final HBox header = new HBox(8, headerLabel, headerCount);

        RowCell() {
            getStyleClass().add("timeline-cell");
            header.getStyleClass().add("timeline-header");
            headerLabel.getStyleClass().add("timeline-header-label");
            headerCount.getStyleClass().add("timeline-header-count");
            setText(null);
        }

        /**
         * Rows are rebuilt as new objects on every layout change, so "changed" means "a different object",
         * not {@code equals}: equal rows at a new size must still be redrawn.
         */
        @Override
        protected boolean isItemChanged(TimelineRow oldItem, TimelineRow newItem) {
            return oldItem != newItem;
        }

        @Override
        protected void updateItem(TimelineRow row, boolean empty) {
            super.updateItem(row, empty);
            switch (empty ? null : row) {
                case null -> {
                    showTiles(0);
                    setGraphic(null);
                }
                case TimelineRow.Header(String label, int count) -> {
                    showTiles(0);
                    headerLabel.setText(label);
                    headerCount.setText("%,d".formatted(count));
                    setGraphic(header);
                }
                case TimelineRow.Thumbs(List<TimelineItem> rowItems) -> {
                    showTiles(rowItems.size());
                    for (int i = 0; i < rowItems.size(); i++) {
                        tiles.get(i).show(rowItems.get(i).ref(), layout.cellSize());
                    }
                    setGraphic(thumbs);
                }
            }
        }

        /** Uses the first {@code count} tiles and clears the rest (which cancels their loads). */
        private void showTiles(int count) {
            while (tiles.size() < count) {
                tiles.add(new ThumbnailTile(loader));
            }
            for (int i = count; i < tiles.size(); i++) {
                tiles.get(i).clear();
            }
            if (thumbs.getChildren().size() != count) {
                thumbs.getChildren().setAll(tiles.subList(0, count));
            }
        }
    }
}
