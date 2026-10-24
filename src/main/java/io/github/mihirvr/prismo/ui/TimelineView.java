package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.platform.OsHiddenMark;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
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
 * <p><b>Item actions.</b> F / H / Delete on the focused photo, and a right-click menu on any tile, run an
 * {@link ItemAction} through the handler set with {@link #setOnAction}. When the photo leaves this view
 * (hidden, trashed…), the focus moves on to its neighbour; the photo itself goes at the next reload. The menu
 * also has the file items ({@link FileItems}: Show in folder, Open with default app, Copy with Ctrl+C) and Info
 * (I or Alt+Enter: the viewer opens with its Info panel), and every entry shows its key.
 *
 * <p><b>Selection Mode</b> (P7). Ctrl-click, Space, the check circle on a tile (under the mouse) or the menu's
 * Select start it; then a click adds or removes a tile, Shift-click (or Shift + an arrow) selects a range, Ctrl+A
 * selects all. A floating bar shows the count and the actions for the whole selection ({@link ItemAction#forSelection},
 * Copy, Move to folder); F / H / Delete / Ctrl+C work on it too. Esc or the bar's × clears it. The selection is a set of
 * media ids ({@link Selection}): a reload keeps what is still shown, a new view (search, filter, page) clears it.
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
    /** I opens the item with the viewer's Info panel; so does Alt+Enter (File Explorer's Properties key). */
    private static final KeyCombination INFO = new KeyCodeCombination(KeyCode.I);
    private static final KeyCombination INFO_EXPLORER_STYLE = new KeyCodeCombination(KeyCode.ENTER,
            KeyCombination.ALT_DOWN);
    /** Layout passes allowed to settle a scroll-into-view (see {@link #afterLayout}). A safety limit, not tuned. */
    private static final int REVEAL_PASSES = 5;
    /** On this pane while something is selected: every tile shows its check circle (CSS). */
    private static final PseudoClass SELECTING = PseudoClass.getPseudoClass("selecting");
    private static final KeyCombination SELECT_ALL = new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);
    private static final double BAR_ICON_SIZE = 18;

    private final ListView<TimelineRow> list = new ListView<>();
    /** Middle-click auto-scroll (1.0.1). */
    private final AutoScroll autoScroll;
    private final ThumbnailLoader loader;
    private final Clock clock;
    private final DoubleProperty thumbnailSize = new SimpleDoubleProperty(DEFAULT_THUMBNAIL_SIZE);
    /** Every cell the list has made (a few dozen), to move the focus ring without rebuilding them. */
    private final List<RowCell> cells = new ArrayList<>();
    private List<TimelineItem> items = List.of();
    /** Date headers on (date sorts) or off (a plain grid). */
    private boolean grouped = true;
    /** How date headers split the items (Settings › Timeline); shared by every grid. */
    private Grouping grouping = Grouping.DEFAULT;
    private GridLayout layout = new GridLayout(1, DEFAULT_THUMBNAIL_SIZE);
    private long focusedId = NO_FOCUS;
    private BiConsumer<List<TimelineItem>, Integer> onOpen = (_, _) -> { };
    private BiConsumer<List<TimelineItem>, Integer> onInfo = (_, _) -> { };
    private ItemAction.Handler onAction = (_, _) -> false;
    private FileItems fileItems = FileItems.NONE;
    /** The Settings switch "Show files hidden in File Explorer" (which actions an item gets). */
    private BooleanSupplier osHiddenShown = () -> false;
    private ContextMenu menu;
    private final Selection selection = new Selection();
    /** Library folders that can't be reached right now: their tiles are greyed (P7). */
    private OfflineFolders offline = OfflineFolders.NONE;
    /** Floats over the bottom of the grid while something is selected: the count and the actions. */
    private final HBox selectionBar = new HBox();
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
        selectionBar.getStyleClass().add("selection-bar");
        selectionBar.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        selectionBar.setVisible(false);
        StackPane.setAlignment(selectionBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(selectionBar, new Insets(0, 16, 14, 16));
        getChildren().addAll(list, selectionBar);
        autoScroll = new AutoScroll(list, this::flow);
        visibleProperty().addListener((_, _, shown) -> {
            if (!shown) {
                autoScroll.stop();
            }
        });

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
        selection.retainShown(items);
        updateSelection(); // also when nothing left it: the actions follow the items (Favorite becomes Remove)
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
        selection.clear();
        list.getItems().setAll(buildRows());
        list.scrollTo(0);
        updateSelection();
    }

    /** True when the items are in date order (a date sort, with date headers): only then can it jump to a date. */
    public boolean isDateOrdered() {
        return grouped && !items.isEmpty();
    }

    /**
     * The days that have at least one item, for the Go to date calendar (1.0.1). One pass over the items in memory, no
     * disk: a few milliseconds for 40,000.
     */
    public Set<LocalDate> daysWithItems() {
        Set<LocalDate> days = new HashSet<>();
        for (TimelineItem item : items) {
            days.add(LocalDate.ofEpochDay(DateGroups.dayOf(item.sortDate())));
        }
        return days;
    }

    /** The day of the item at the top of the view (or the first item), or null when there are none. */
    public LocalDate dayAtTop() {
        if (items.isEmpty()) {
            return null;
        }
        Anchor top = anchor();
        int at = top == null ? 0 : Math.max(0, indexOf(top.mediaId()));
        return LocalDate.ofEpochDay(DateGroups.dayOf(items.get(at).sortDate()));
    }

    /**
     * Scrolls to {@code day}: its first item goes to the top (under its date header) and gets the focus. A day without
     * items goes to the nearest one after it in the view's order (newest first: the next older day). Date order only.
     */
    public void jumpTo(LocalDate day) {
        jumpToIndex(jumpIndex(items, day.toEpochDay()));
    }

    /** Scrolls to the first item of {@code month} in the view's order, or the nearest one after it. Date order only. */
    public void jumpTo(YearMonth month) {
        boolean newestFirst = newestFirst(items);
        jumpToIndex(jumpIndex(items, (newestFirst ? month.atEndOfMonth() : month.atDay(1)).toEpochDay()));
    }

    /**
     * Where a jump to {@code day} (an epoch day) lands in {@code items} (in date order, either way): the first item
     * of that day or, without one, of the nearest day after it in that order; the last item when there is none.
     */
    static int jumpIndex(List<TimelineItem> items, long day) {
        boolean newestFirst = newestFirst(items);
        for (int i = 0; i < items.size(); i++) {
            long itemDay = DateGroups.dayOf(items.get(i).sortDate());
            if (newestFirst ? itemDay <= day : itemDay >= day) {
                return i;
            }
        }
        return items.size() - 1;
    }

    private static boolean newestFirst(List<TimelineItem> items) {
        return items.isEmpty() || items.getFirst().sortDate() >= items.getLast().sortDate();
    }

    private void jumpToIndex(int index) {
        if (!isDateOrdered() || index < 0) {
            return;
        }
        autoScroll.stop();
        long id = items.get(index).ref().id();
        List<TimelineRow> rows = list.getItems();
        int row = TimelineRows.rowOf(rows, id);
        if (row < 0) {
            return;
        }
        // Its date header on top when it starts the group; putting a row at the top is exact (see rebuild).
        list.scrollTo(row > 0 && rows.get(row - 1) instanceof TimelineRow.Header ? row - 1 : row);
        focusedId = id;
        for (RowCell cell : cells) {
            cell.updateMarks();
        }
        requestFocus();
    }

    /** The first item, or null if there are none. */
    public TimelineItem firstItem() {
        return items.isEmpty() ? null : items.getFirst();
    }

    /** Called with the Timeline's items and the index to open, on a click or Enter. */
    public void setOnOpen(BiConsumer<List<TimelineItem>, Integer> onOpen) {
        this.onOpen = onOpen;
    }

    /** Called like {@link #setOnOpen}, for the Info item and the I key: the viewer opens with its Info panel. */
    public void setOnInfo(BiConsumer<List<TimelineItem>, Integer> onInfo) {
        this.onInfo = onInfo;
    }

    /** Runs the item actions (keys and the right-click menu). */
    public void setOnAction(ItemAction.Handler onAction) {
        this.onAction = onAction;
    }

    /** The Settings switch "Show files hidden in File Explorer", read whenever the actions are worked out. */
    public void setOsHiddenShown(BooleanSupplier osHiddenShown) {
        this.osHiddenShown = osHiddenShown;
    }

    /** Show in folder, Open with default app and Copy (the menu and Ctrl+C). */
    public void setFileItems(FileItems fileItems) {
        this.fileItems = fileItems;
    }

    /** The library folders that can't be reached right now: their tiles are greyed and marked. */
    public void setOffline(OfflineFolders newOffline) {
        if (!newOffline.equals(offline)) {
            offline = newOffline;
            for (RowCell cell : cells) {
                cell.updateMarks();
            }
        }
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

    /** How date headers split the items (Settings › Timeline); rebuilds the rows, staying on the same photo. */
    public void setGrouping(Grouping newGrouping) {
        if (newGrouping != grouping) {
            grouping = newGrouping;
            rebuild(false);
        }
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

    /** The selected items, in the order shown. For tests and tools. */
    List<TimelineItem> selectedItems() {
        return selection.items(items);
    }

    /** The floating bar of Selection Mode. For tests and tools. */
    HBox selectionBar() {
        return selectionBar;
    }

    // --- Keyboard focus ---

    private void onKey(KeyEvent event) {
        if (SELECT_ALL.match(event)) {
            selection.selectAll(items);
            updateSelection();
            event.consume();
            return;
        }
        if (!selection.isEmpty() && onSelectionKey(event)) {
            event.consume();
            return;
        }
        int focused = indexOf(focusedId);
        if (fileItems.handleKey(event, focused < 0 ? null : items.get(focused))) {
            event.consume(); // Ctrl+C
            return;
        }
        if (INFO.match(event) || INFO_EXPLORER_STYLE.match(event)) {
            openInfo(focusedId);
            event.consume();
            return;
        }
        boolean handled = true;
        long before = focusedId;
        switch (event.getCode()) {
            case LEFT -> moveFocusBy(-1);
            case RIGHT -> moveFocusBy(+1);
            case UP -> moveFocusVertically(-1);
            case DOWN -> moveFocusVertically(+1);
            case ENTER -> open(focusedId);
            case SPACE -> toggleSelected(focusedId);
            default -> {
                int at = indexOf(focusedId);
                ItemAction action = at < 0 || event.isShortcutDown() || event.isAltDown()
                        ? null : ItemAction.forKey(event.getCode(), items.get(at), osHiddenShown.getAsBoolean());
                handled = action != null;
                if (handled) {
                    perform(action, items.get(at));
                }
            }
        }
        if (handled) {
            event.consume();
        }
        if (event.isShiftDown() && event.getCode().isArrowKey() && focusedId != NO_FOCUS) {
            // Shift + arrow: from where the focus was to where it is now, like File Explorer.
            selection.anchorAt(before == NO_FOCUS ? focusedId : before);
            selection.selectRange(items, focusedId, false);
            updateSelection();
        }
    }

    /**
     * Keys that work on the whole selection: Esc clears it, Ctrl+C copies every file, F / H / Delete do the
     * selection bar's action.
     *
     * @return true if the key was used
     */
    private boolean onSelectionKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            clearSelection();
            return true;
        }
        if (FileItems.COPY.match(event)) {
            fileItems.copy(selectedItems());
            return true;
        }
        ItemAction action = event.isShortcutDown() || event.isAltDown() ? null
                : ItemAction.forSelectionKey(event.getCode(), selectedItems(), osHiddenShown.getAsBoolean());
        if (action == null) {
            return false;
        }
        performOnSelection(action);
        return true;
    }

    // --- Selection Mode ---

    /** Adds or removes one item, and puts the keyboard focus on it. */
    private void toggleSelected(long id) {
        if (indexOf(id) < 0) {
            return;
        }
        selection.toggle(id);
        moveFocus(id);
        updateSelection();
    }

    private void clearSelection() {
        selection.clear();
        updateSelection();
    }

    /** Runs a selection bar action on every selected item; after anything but a favorite change, the selection ends. */
    private void performOnSelection(ItemAction action) {
        List<TimelineItem> chosen = selectedItems();
        if (chosen.isEmpty()) {
            return;
        }
        if (onAction.performAll(action, chosen)
                && !action.keepsItemInView(chosen.getFirst(), osHiddenShown.getAsBoolean())) {
            clearSelection();
        }
    }

    /** Shows the selection on the tiles and in the bar (hidden when nothing is selected). */
    private void updateSelection() {
        boolean selecting = !selection.isEmpty();
        pseudoClassStateChanged(SELECTING, selecting);
        for (RowCell cell : cells) {
            cell.updateMarks();
        }
        selectionBar.setVisible(selecting);
        if (!selecting) {
            selectionBar.getChildren().clear();
            return;
        }
        List<TimelineItem> chosen = selectedItems();
        Button clear = barButton(Icon.CLOSE, null, "Clear selection (Esc)", this::clearSelection);
        Label count = new Label("%,d selected".formatted(chosen.size()));
        count.getStyleClass().add("selection-count");
        Region gap = new Region();
        gap.getStyleClass().add("selection-gap");
        selectionBar.getChildren().setAll(clear, count, gap);
        boolean shown = osHiddenShown.getAsBoolean();
        for (ItemAction action : ItemAction.forSelection(chosen, shown)) {
            selectionBar.getChildren().add(barButton(action.icon(), action.buttonText(), action.tooltip(),
                    () -> performOnSelection(action)));
        }
        selectionBar.getChildren().add(barButton(Icon.COPY, null, "Copy (" + FileItems.COPY.getDisplayText() + ")",
                () -> fileItems.copy(selectedItems())));
        if (fileItems.canMove(chosen)) {
            selectionBar.getChildren().add(barButton(Icon.MOVE, null, "Move to folder\u2026",
                    () -> fileItems.move(selectedItems())));
        }
        Button all = barButton(Icon.CHECK, "Select all", "Select all (" + SELECT_ALL.getDisplayText() + ")", () -> {
            selection.selectAll(items);
            updateSelection();
        });
        all.setDisable(chosen.size() == items.size());
        selectionBar.getChildren().add(all);
    }

    /** A button of the selection bar: an icon, maybe a few words, and a tooltip naming it with its key. */
    private Button barButton(Icon icon, String text, String tooltip, Runnable action) {
        Button button = new Button(text, icon.node(BAR_ICON_SIZE));
        button.getStyleClass().add("selection-button");
        if (text == null) {
            button.getStyleClass().add("icon-button");
        }
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false); // keys stay with the grid
        button.setOnAction(_ -> action.run());
        return button;
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
            cell.updateMarks();
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

    /** Opens media {@code id} with the viewer's Info panel. */
    private void openInfo(long id) {
        int at = indexOf(id);
        if (at >= 0) {
            onInfo.accept(items, at);
        }
    }

    /** Runs {@code action}; if the item leaves this view, the focus moves to the next item (or the previous). */
    private void perform(ItemAction action, TimelineItem item) {
        int at = indexOf(item.ref().id());
        if (!onAction.perform(action, item) || action.keepsItemInView(item, osHiddenShown.getAsBoolean()) || at < 0
                || item.ref().id() != focusedId) {
            return;
        }
        if (at + 1 < items.size()) {
            moveFocus(items.get(at + 1).ref().id());
        } else if (at > 0) {
            moveFocus(items.get(at - 1).ref().id());
        }
    }

    /**
     * The right-click menu for one tile: Open, then what {@link ItemAction#forMenu} allows, then the file items.
     * Each entry shows its key (shown only: the keys themselves are handled in {@link #onKey}).
     */
    private void showMenu(ThumbnailTile tile, double screenX, double screenY) {
        int at = tile.ref() == null ? -1 : indexOf(tile.ref().id());
        if (at < 0) {
            return;
        }
        TimelineItem item = items.get(at);
        moveFocus(item.ref().id());
        if (menu != null) {
            menu.hide();
        }
        if (selection.size() > 1 && selection.contains(item.ref().id())) {
            menu = selectionMenu();
            menu.show(tile, screenX, screenY);
            return;
        }
        MenuItem open = Menus.item("Open", Icon.IMAGE, new KeyCodeCombination(KeyCode.ENTER),
                () -> open(item.ref().id()));
        MenuItem select = Menus.item(selection.contains(item.ref().id()) ? "Unselect" : "Select", Icon.CHECK,
                new KeyCodeCombination(KeyCode.SPACE), () -> toggleSelected(item.ref().id()));
        menu = new ContextMenu(open, select, new SeparatorMenuItem());
        for (ItemAction action : ItemAction.forMenu(item, OsHiddenMark.SUPPORTED, osHiddenShown.getAsBoolean())) {
            menu.getItems().add(Menus.item(action.menuText(), action.icon(),
                    action.shortcut(item, osHiddenShown.getAsBoolean()),
                    () -> perform(action, item)));
        }
        menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().addAll(fileItems.menuItems(item));
        menu.getItems().add(Menus.item("Info", Icon.INFO, INFO, () -> openInfo(item.ref().id())));
        menu.show(tile, screenX, screenY);
    }

    /** The right-click menu on a selected tile while several are selected: what the selection bar does. */
    private ContextMenu selectionMenu() {
        List<TimelineItem> chosen = selectedItems();
        ContextMenu selected = new ContextMenu();
        MenuItem count = new MenuItem("%,d selected".formatted(chosen.size()));
        count.setDisable(true);
        selected.getItems().addAll(count, new SeparatorMenuItem());
        for (ItemAction action : ItemAction.forSelection(chosen, osHiddenShown.getAsBoolean())) {
            selected.getItems().add(Menus.item(action.menuText(), action.icon(), action.shortcut(),
                    () -> performOnSelection(action)));
        }
        selected.getItems().add(new SeparatorMenuItem());
        selected.getItems().addAll(fileItems.menuItems(chosen));
        selected.getItems().addAll(new SeparatorMenuItem(),
                Menus.item("Select all", Icon.CHECK, SELECT_ALL, () -> {
                    selection.selectAll(items);
                    updateSelection();
                }),
                Menus.item("Clear selection", Icon.CLOSE, new KeyCodeCombination(KeyCode.ESCAPE), this::clearSelection));
        return selected;
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
        return TimelineRows.build(items, layout.columns(), LocalDate.now(clock).toEpochDay(), grouping, grouped);
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
                        tiles.get(i).setFavorite(rowItems.get(i).favorite());
                        tiles.get(i).setVideoLength(rowItems.get(i).durationSeconds());
                        tiles.get(i).setWatchProgress(rowItems.get(i).watchedSeconds(), rowItems.get(i).durationSeconds());
                    }
                    setGraphic(thumbs);
                }
            }
            updateMarks();
        }

        /** The focus ring, the selected look and the offline mark of each tile. */
        void updateMarks() {
            for (ThumbnailTile tile : tiles) {
                tile.setFocusRing(tile.ref() != null && tile.ref().id() == focusedId);
                tile.setSelected(tile.ref() != null && selection.contains(tile.ref().id()));
                tile.setOffline(tile.ref() != null && offline.contains(tile.ref().path()));
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

        /**
         * A tile that opens its photo on a click (a click, not the end of a drag). Ctrl-click, and any click while
         * something is selected, adds or removes it instead; Shift-click selects a range (Ctrl+Shift adds one).
         */
        private ThumbnailTile newTile() {
            ThumbnailTile tile = new ThumbnailTile(loader);
            tile.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress() && tile.ref() != null) {
                    long id = tile.ref().id();
                    if (event.isShiftDown()) {
                        selection.anchorAt(focusedId != NO_FOCUS ? focusedId : id);
                        selection.selectRange(items, id, event.isShortcutDown());
                        moveFocus(id);
                        updateSelection();
                    } else if (event.isShortcutDown() || !selection.isEmpty()) {
                        toggleSelected(id);
                    } else {
                        moveFocus(id);
                        open(id);
                    }
                }
            });
            tile.setOnCheck(() -> toggleSelected(tile.ref().id()));
            tile.setOnContextMenuRequested(event -> {
                showMenu(tile, event.getScreenX(), event.getScreenY());
                event.consume();
            });
            return tile;
        }
    }
}
