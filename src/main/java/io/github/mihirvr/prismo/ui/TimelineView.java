package io.github.mihirvr.prismo.ui;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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
 * <p><b>Keyboard.</b> One photo can have keyboard focus (a ring around it). Arrow keys move it, Enter opens
 * it, and it's scrolled into view. Focus is kept as a media id, not a cell, so it survives row rebuilds.
 * The ListView itself never takes focus (no selection colours); this pane does, and handles the keys.
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
    private static final long NO_FOCUS = -1;
    /** Layout passes allowed to settle a scroll-into-view (see {@link #afterLayout}). A safety limit, not tuned. */
    private static final int REVEAL_PASSES = 5;

    private final ListView<TimelineRow> list = new ListView<>();
    private final ThumbnailLoader loader;
    private final Clock clock;
    private final DoubleProperty thumbnailSize = new SimpleDoubleProperty(DEFAULT_THUMBNAIL_SIZE);
    /** Every cell the list has made (a few dozen), to move the focus ring without rebuilding them. */
    private final List<RowCell> cells = new ArrayList<>();
    private List<TimelineItem> items = List.of();
    /** Date headers on (date sorts) or off (a plain grid). */
    private boolean grouped = true;
    private GridLayout layout = new GridLayout(1, DEFAULT_THUMBNAIL_SIZE);
    private long focusedId = NO_FOCUS;
    private BiConsumer<List<TimelineItem>, Integer> onOpen = (_, _) -> { };
    /**
     * How many more layout passes may still scroll the focused photo fully into view; 0 = done. Set by an arrow-key
     * move whose first scroll couldn't be exact (see {@link #scrollIntoView}).
     */
    private int revealPasses;
    private final Runnable afterLayout = () -> {
        if (revealPasses == 0) {
            return;
        }
        // VirtualFlow lays out a scroll in the next pass, and its row positions come from estimated heights that
        // it refines as rows get drawn. Measured: a single scroll to a row that wasn't drawn left it cut off.
        VirtualFlow<?> flow = flow();
        if (list.isNeedsLayout() || flow == null || flow.isNeedsLayout()) {
            return; // wait for the pass that lays out the new rows
        }
        revealPasses--;
        if (scrollIntoView(focusedId)) {
            revealPasses = 0; // fully in view: stop, so a later wheel scroll is never pulled back
        }
    };

    /** @param loader shared with the viewer (see {@link ThumbnailLoader}); its owner closes it */
    public TimelineView(ThumbnailLoader loader) {
        this(loader, Clock.systemDefaultZone());
    }

    TimelineView(ThumbnailLoader loader, Clock clock) {
        this.loader = loader;
        this.clock = clock;
        getStyleClass().add("timeline");
        list.getStyleClass().add("timeline-list");
        list.setCellFactory(_ -> {
            RowCell cell = new RowCell();
            cells.add(cell);
            return cell;
        });
        // The list never takes focus (no focus ring or selected-row colours); this pane does, for the arrow keys.
        list.setFocusTraversable(false);
        setFocusTraversable(true);
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
        addEventFilter(MouseEvent.MOUSE_PRESSED, _ -> requestFocus());
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // After a rebuild the new rows only have positions after the next layout pass, so the "keep the
        // focused photo in view" scroll runs from the scene's post-layout hook, not straight away.
        sceneProperty().addListener((_, old, scene) -> {
            if (old != null) {
                old.removePostLayoutPulseListener(afterLayout);
            }
            if (scene != null) {
                scene.addPostLayoutPulseListener(afterLayout);
            }
        });
    }

    /** Replaces everything shown (e.g. after an index run), staying on the same photo if it's still there. */
    public void setItems(List<TimelineItem> newItems) {
        items = List.copyOf(newItems); // rows hold views into this list, so it must never change
        rebuild(false);
    }

    /**
     * Replaces everything shown with a different set (a new search, filter or sort): from the top, nothing
     * focused.
     *
     * @param grouped date headers (date sorts) or a plain grid
     */
    public void showFromTop(List<TimelineItem> newItems, boolean grouped) {
        this.grouped = grouped;
        items = List.copyOf(newItems);
        focusedId = NO_FOCUS;
        list.getItems().setAll(buildRows());
        list.scrollTo(0);
    }

    /** The first item, or null if there are none. */
    public TimelineItem firstItem() {
        return items.isEmpty() ? null : items.getFirst();
    }

    /** Called with the Timeline's items and the index to open, on a click or Enter. */
    public void setOnOpen(BiConsumer<List<TimelineItem>, Integer> onOpen) {
        this.onOpen = onOpen;
    }

    /** Puts the keyboard focus on media {@code id} and scrolls it into view (back from the viewer). */
    public void reveal(long id) {
        moveFocus(id);
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

    /** The list itself, for tests and tools that drive scrolling. */
    ListView<TimelineRow> list() {
        return list;
    }

    /** The focused media id, or -1. For tests and tools. */
    long focusedId() {
        return focusedId;
    }

    // --- Keyboard focus ---

    private void onKey(KeyEvent event) {
        boolean handled = true;
        switch (event.getCode()) {
            case LEFT -> moveFocusBy(-1);
            case RIGHT -> moveFocusBy(+1);
            case UP -> moveFocusVertically(-1);
            case DOWN -> moveFocusVertically(+1);
            case ENTER -> open(focusedId);
            default -> handled = false;
        }
        if (handled) {
            event.consume();
        }
    }

    /** Left / Right: the previous / next item in Timeline order, across rows and groups. */
    private void moveFocusBy(int delta) {
        int at = indexOf(focusedId);
        if (at < 0) {
            focusFirstVisible();
            return;
        }
        int next = at + delta;
        if (next >= 0 && next < items.size()) {
            moveFocus(items.get(next).ref().id());
        }
    }

    private void moveFocusVertically(int direction) {
        if (indexOf(focusedId) < 0) {
            focusFirstVisible();
            return;
        }
        long next = TimelineRows.verticalNeighbour(list.getItems(), focusedId, direction);
        if (next >= 0) {
            moveFocus(next);
        }
    }

    /** The first arrow press with nothing focused (or the focused photo gone) picks the top-left visible photo. */
    private void focusFirstVisible() {
        VirtualFlow<?> flow = flow();
        int first = flow == null || flow.getFirstVisibleCell() == null ? 0 : flow.getFirstVisibleCell().getIndex();
        List<TimelineRow> rows = list.getItems();
        for (int r = Math.max(0, first); r < rows.size(); r++) {
            if (rows.get(r) instanceof TimelineRow.Thumbs(List<TimelineItem> rowItems)) {
                moveFocus(rowItems.getFirst().ref().id());
                return;
            }
        }
    }

    private void moveFocus(long id) {
        focusedId = id;
        for (RowCell cell : cells) {
            cell.updateFocusRings();
        }
        if (!scrollIntoView(id)) {
            revealPasses = REVEAL_PASSES; // check again once the scroll has been laid out
        }
    }

    /**
     * Scrolls only as far as needed to show the row holding {@code id} completely (ListView.scrollTo would put
     * it at the top). If the row's cell is drawn, scrolling to that cell is exact; if not (off screen),
     * VirtualFlow can only scroll by an estimated row height, so callers check again after the next layout.
     *
     * @return true if the row was already fully in view (nothing was scrolled)
     */
    private boolean scrollIntoView(long id) {
        int row = TimelineRows.rowOf(list.getItems(), id);
        VirtualFlow<?> flow = flow();
        if (row < 0 || flow == null) {
            return true; // nothing to show
        }
        IndexedCell<?> cell = flow.getVisibleCell(row);
        if (cell == null) {
            flow.scrollTo(row);
            return false;
        }
        Bounds shown = cell.localToScene(cell.getLayoutBounds());
        Bounds viewport = flow.localToScene(flow.getLayoutBounds());
        if (shown.getMinY() >= viewport.getMinY() - 0.5 && shown.getMaxY() <= viewport.getMaxY() + 0.5) {
            return true;
        }
        scrollToCell(flow, cell);
        return false;
    }

    /** VirtualFlow&lt;T&gt;.scrollTo(T) needs the flow's own cell type; the cell came from that same flow. */
    @SuppressWarnings("unchecked")
    private static <T extends IndexedCell<?>> void scrollToCell(VirtualFlow<T> flow, IndexedCell<?> cell) {
        flow.scrollTo((T) cell);
    }

    /** True if row {@code row} is at least partly on screen. */
    private boolean isOnScreen(int row) {
        VirtualFlow<?> flow = flow();
        if (row < 0 || flow == null || flow.getFirstVisibleCell() == null || flow.getLastVisibleCell() == null) {
            return false;
        }
        return row >= flow.getFirstVisibleCell().getIndex() && row <= flow.getLastVisibleCell().getIndex();
    }

    private void open(long id) {
        int at = indexOf(id);
        if (at >= 0) {
            onOpen.accept(items, at);
        }
    }

    /** Position in {@link #items}, or -1. A linear scan: one click or key press, ~1 ms for 100,000 items. */
    private int indexOf(long id) {
        if (id == NO_FOCUS) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).ref().id() == id) {
                return i;
            }
        }
        return -1;
    }

    private VirtualFlow<?> flow() {
        return list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow ? flow : null;
    }

    // --- Layout ---

    private void relayout() {
        double width = list.getWidth() - list.snappedLeftInset() - list.snappedRightInset() - SCROLL_BAR_ALLOWANCE;
        GridLayout next = GridLayout.fit(width, thumbnailSize.get(), GAP);
        if (!next.equals(layout)) {
            layout = next;
            rebuild(true);
        }
    }

    /**
     * Rebuilds the rows for the current items and layout, keeping the same photo at the top.
     *
     * <p>When the column count changed and the focused photo was on screen, the focused photo becomes that top
     * photo instead: a narrower window can otherwise push its row off the bottom. (Scrolling it into view after
     * the rebuild was tried and measured unreliable: VirtualFlow keeps shifting rows for a few passes while it
     * re-estimates their heights. Putting a row at the top is exact.) Not on a reload with the same columns
     * (every 3 s while indexing): the rows on screen don't move then, and the view must not jump.
     *
     * @param columnsChanged true for a resize / size change, false for new items
     */
    private void rebuild(boolean columnsChanged) {
        boolean keepFocus = columnsChanged && isOnScreen(TimelineRows.rowOf(list.getItems(), focusedId));
        Anchor anchor = keepFocus ? new Anchor(focusedId, false) : anchor();
        List<TimelineRow> rows = buildRows();
        list.getItems().setAll(rows);
        if (anchor != null) {
            int row = TimelineRows.rowOf(rows, anchor.mediaId());
            if (row >= 0) {
                list.scrollTo(anchor.header() ? row - 1 : row);
            }
        }
    }

    private List<TimelineRow> buildRows() {
        return TimelineRows.build(items, layout.columns(), LocalDate.now(clock).toEpochDay(), grouped);
    }

    /** Which photo is at the top of the view now, or null at the very top / before the first layout. */
    private Anchor anchor() {
        VirtualFlow<?> flow = flow();
        if (flow == null) {
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
            updateFocusRings();
        }

        void updateFocusRings() {
            for (ThumbnailTile tile : tiles) {
                tile.setFocusRing(tile.ref() != null && tile.ref().id() == focusedId);
            }
        }

        /** Uses the first {@code count} tiles and clears the rest (which cancels their loads). */
        private void showTiles(int count) {
            while (tiles.size() < count) {
                tiles.add(newTile());
            }
            for (int i = count; i < tiles.size(); i++) {
                tiles.get(i).clear();
            }
            if (thumbs.getChildren().size() != count) {
                thumbs.getChildren().setAll(tiles.subList(0, count));
            }
        }

        /** A tile that opens its photo on a click (a click, not the end of a drag). */
        private ThumbnailTile newTile() {
            ThumbnailTile tile = new ThumbnailTile(loader);
            tile.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress() && tile.ref() != null) {
                    long id = tile.ref().id();
                    moveFocus(id);
                    open(id);
                }
            });
            return tile;
        }
    }
}
