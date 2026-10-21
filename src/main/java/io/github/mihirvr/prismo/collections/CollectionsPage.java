package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.platform.OsHiddenMark;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.AlbumGrid;
import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.ItemAction;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineQuery;
import io.github.mihirvr.prismo.ui.TimelineView;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Collections tab: a list of pages on the left (Folders, Albums, Favorites, Recently Added, Hidden, Trash)
 * and the chosen page on the right. Every page is the {@code media} table through a {@link MediaScope}, shown
 * in the same grid as the Timeline, with the same shared Filters + Sort.
 *
 * <p><b>Loading.</b> Like the Timeline: each load runs on the app's {@code prismo-timeline} thread, numbered;
 * a load replaced while it waited is skipped, and a result that's no longer the newest is dropped on the UI
 * thread. Reloading while the tab isn't on screen is put off until it is shown again.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class CollectionsPage extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(CollectionsPage.class);
    private static final double PAGE_ICON_SIZE = 18;
    private static final double FOLDER_ICON_SIZE = 16;

    /** The pages, in the order of the list on the left. */
    public enum Page {
        FOLDERS("Folders", Icon.FOLDER), ALBUMS("Albums", Icon.ALBUMS), FAVORITES("Favorites", Icon.HEART),
        RECENTLY_ADDED("Recently Added", Icon.CLOCK), HIDDEN("Hidden", Icon.EYE_OFF), TRASH("Trash", Icon.TRASH);

        private final String label;
        private final Icon icon;

        Page(String label, Icon icon) {
            this.label = label;
            this.icon = icon;
        }

        public Icon icon() {
            return icon;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Database database;
    private final Executor loads;
    private final AtomicLong latestLoad = new AtomicLong();

    private final ListView<Page> pages = new ListView<>();
    private final TimelineView grid;
    private final AlbumGrid albumGrid;
    private final TreeView<FolderTree.Node> tree = new TreeView<>();
    private final CheckBox recursive = new CheckBox("Include subfolders");
    private final Label title = new Label();
    private final Label hint = new Label();
    private final Button backToAlbums = new Button("Albums", Icon.CHEVRON_LEFT.node(PAGE_ICON_SIZE));
    private final Label placeholder = new Label();
    private final SplitPane folderSplit = new SplitPane();
    private final StackPane gridHolder = new StackPane();
    private final ReadOnlyBooleanWrapper sortLocked = new ReadOnlyBooleanWrapper();

    private ViewOptions options = ViewOptions.DEFAULT;
    /** The folder chosen in the tree (its {@code media.folder} path), or null for the first library folder. */
    private String folder;
    /** The album open on the Albums page, or null for the list of albums. */
    private Album openAlbum;
    /** Folders expanded in the tree, kept across reloads (the tree is rebuilt each time). */
    private final Set<String> expanded = new HashSet<>();
    /** True while the tree is being rebuilt, so its selection changes aren't taken as clicks. */
    private boolean rebuildingTree;
    /** What the grid shows now (scope + options), to tell a reload (stay put) from a new view (to the top). */
    private Object shownKey;
    private boolean stale = true;
    /** The layout on screen (page, album open or not), so it is only rebuilt when that changes. */
    private Object shownLayout;

    /**
     * @param loads the thread loads run on, one at a time, in order (shared with the Timeline)
     */
    public CollectionsPage(Database database, ThumbnailLoader loader, Executor loads) {
        this.database = database;
        this.loads = loads;
        getStyleClass().add("collections");
        grid = new TimelineView(loader);
        grid.setPlaceholder(placeholder);
        placeholder.getStyleClass().add("collections-placeholder");
        placeholder.setWrapText(true);
        albumGrid = new AlbumGrid(loader);
        albumGrid.thumbnailSizeProperty().bind(grid.thumbnailSizeProperty());
        Label noAlbums = new Label("No albums yet. Albums are made from the folders that hold photos or videos.");
        noAlbums.getStyleClass().add("collections-placeholder");
        albumGrid.setPlaceholder(noAlbums);
        albumGrid.setOnOpen(album -> {
            openAlbum = album;
            refresh();
        });
        gridHolder.getChildren().add(grid);

        pages.getItems().setAll(Page.values());
        pages.getStyleClass().add("collections-pages");
        pages.setCellFactory(_ -> new PageCell());
        pages.setPrefWidth(170);
        pages.getSelectionModel().select(Page.FOLDERS);
        pages.getSelectionModel().selectedItemProperty().addListener((_, _, page) -> {
            if (page != null) {
                openAlbum = null;
                refresh();
            }
        });

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(_ -> new FolderCell());
        tree.getSelectionModel().selectedItemProperty().addListener((_, _, item) -> {
            if (!rebuildingTree && item != null && item.getValue() != null) {
                folder = item.getValue().path();
                refresh();
            }
        });
        recursive.setSelected(true);
        recursive.selectedProperty().addListener((_, _, _) -> refresh());
        folderSplit.setOrientation(Orientation.HORIZONTAL);
        backToAlbums.getStyleClass().add("back-button");
        backToAlbums.setTooltip(new Tooltip("Back to all albums"));
        backToAlbums.setOnAction(_ -> {
            openAlbum = null;
            refresh();
        });

        title.getStyleClass().add("collections-title");
        title.setMinWidth(USE_PREF_SIZE);
        hint.getStyleClass().add("collections-hint");
        hint.setMinWidth(0); // a long folder path shortens (from the left), the title and checkbox don't
        recursive.setMinWidth(USE_PREF_SIZE);
        setLeft(pages);
        visibleProperty().addListener((_, _, shown) -> {
            if (shown && stale) {
                refresh();
            }
        });
        showLayout(Page.FOLDERS);
    }

    /** The media grid (every page but the album list): for the viewer and its "back to this photo". */
    public TimelineView grid() {
        return grid;
    }

    public void setOnOpen(BiConsumer<List<TimelineItem>, Integer> onOpen) {
        grid.setOnOpen(onOpen);
    }

    /** The grid's Info item and I key: open with the viewer's Info panel. */
    public void setOnInfo(BiConsumer<List<TimelineItem>, Integer> onInfo) {
        grid.setOnInfo(onInfo);
    }

    public void setOnAction(ItemAction.Handler onAction) {
        grid.setOnAction(onAction);
    }

    /** Folders' "Include subfolders" box; kept across restarts (P7). */
    public BooleanProperty includeSubfoldersProperty() {
        return recursive.selectedProperty();
    }

    /** True while Recently Added is shown: its order is fixed, so the Sort controls are locked. */
    public ReadOnlyBooleanProperty sortLockedProperty() {
        return sortLocked.getReadOnlyProperty();
    }

    public Page page() {
        return pages.getSelectionModel().getSelectedItem();
    }

    public void showPage(Page page) {
        pages.getSelectionModel().select(page);
    }

    /** Keyboard focus to the list of pages (Up / Down move between them). */
    public void focusPageList() {
        pages.requestFocus();
    }

    /** The shared Filters + Sort (or the File Explorer switch) changed. */
    public void setOptions(ViewOptions newOptions) {
        options = newOptions;
        refresh();
    }

    /** Something in the library changed (index run, file changes, an item action): reload what's shown. */
    public void refresh() {
        Page page = page();
        sortLocked.set(page == Page.RECENTLY_ADDED);
        showLayout(page);
        if (!isVisible()) {
            stale = true; // done when the tab is shown
            return;
        }
        stale = false;
        long number = latestLoad.incrementAndGet();
        Request request = new Request(page, folder, openAlbum, recursive.isSelected(), options);
        loads.execute(() -> {
            if (number != latestLoad.get()) {
                return; // replaced while it waited
            }
            try {
                Loaded loaded = database.read(c -> load(c, request));
                Platform.runLater(() -> {
                    if (number == latestLoad.get()) {
                        show(request, loaded);
                    }
                });
            } catch (SQLException | RuntimeException e) {
                log.warn("Could not load {}", request.page(), e);
                Platform.runLater(() -> placeholder.setText("Could not load this page: " + e.getMessage()));
            }
        });
    }

    // --- Loading (on the load thread) ---

    /** What to load: a snapshot of the page's state, taken on the UI thread. */
    private record Request(Page page, String folder, Album album, boolean recursive, ViewOptions options) {
    }

    /** What was loaded. {@code tree} only for Folders, {@code albums} only for the list of albums. */
    private record Loaded(List<FolderTree.Node> tree, String folder, List<Album> albums, MediaScope scope,
            ViewOptions options, List<TimelineItem> items) {
    }

    private static Loaded load(Connection c, Request request) throws SQLException {
        ZoneId zone = ZoneId.systemDefault();
        return switch (request.page()) {
            case FOLDERS -> {
                List<FolderTree.Node> nodes = FolderTree.load(c, request.options());
                String chosen = request.folder() != null && contains(nodes, request.folder()) ? request.folder()
                        : nodes.isEmpty() ? null : nodes.getFirst().path();
                MediaScope scope = chosen == null ? null : MediaScope.folder(chosen, request.recursive());
                List<TimelineItem> items = scope == null ? List.of()
                        : TimelineQuery.load(c, scope, request.options(), zone);
                yield new Loaded(nodes, chosen, null, scope, request.options(), items);
            }
            case ALBUMS -> {
                if (request.album() == null) {
                    yield new Loaded(null, null, Albums.load(c, request.options()), null, request.options(), List.of());
                }
                MediaScope scope = MediaScope.folder(request.album().folder(), false);
                yield new Loaded(null, null, null, scope, request.options(),
                        TimelineQuery.load(c, scope, request.options(), zone));
            }
            case FAVORITES, RECENTLY_ADDED, HIDDEN, TRASH -> {
                MediaScope scope = scopeOf(request.page());
                ViewOptions options = scope.applyTo(request.options());
                yield new Loaded(null, null, null, scope, options, TimelineQuery.load(c, scope, options, zone));
            }
        };
    }

    private static MediaScope scopeOf(Page page) {
        return switch (page) {
            case FAVORITES -> MediaScope.FAVORITES;
            case RECENTLY_ADDED -> MediaScope.RECENTLY_ADDED;
            case HIDDEN -> MediaScope.HIDDEN;
            case TRASH -> MediaScope.TRASH;
            case FOLDERS, ALBUMS -> MediaScope.LIBRARY;
        };
    }

    private static boolean contains(List<FolderTree.Node> nodes, String path) {
        for (FolderTree.Node node : nodes) {
            if (node.path().equals(path) || contains(node.children(), path)) {
                return true;
            }
        }
        return false;
    }

    // --- Showing (UI thread) ---

    private void show(Request request, Loaded loaded) {
        if (loaded.tree() != null) {
            folder = loaded.folder();
            showTree(loaded.tree());
            Path name = folder == null ? null : Path.of(folder).getFileName();
            title.setText(folder == null ? "Folders"
                    : "%s · %,d".formatted(name == null ? folder : name, loaded.items().size()));
            hint.setText(folder == null ? "" : folder);
        }
        if (loaded.albums() != null) {
            albumGrid.setAlbums(loaded.albums());
            title.setText("Albums · %,d".formatted(loaded.albums().size()));
            return;
        }
        if (request.album() != null) {
            title.setText("%s · %,d".formatted(request.album().name(), loaded.items().size()));
        } else if (request.page() != Page.FOLDERS) {
            title.setText("%s · %,d".formatted(request.page(), loaded.items().size()));
        }
        placeholder.setText(emptyText(request, loaded));
        // The File Explorer switch changes which items are in, not the order: stay on the same photo.
        Object key = List.of(String.valueOf(loaded.scope()), loaded.options().withOsHiddenShown(false));
        if (key.equals(shownKey)) {
            grid.setItems(loaded.items()); // same view, new data: stay on the same photo
        } else {
            grid.showFromTop(loaded.items(), loaded.options().grouped());
        }
        shownKey = key;
    }

    private String emptyText(Request request, Loaded loaded) {
        if (loaded.scope() == null) {
            return "No library folders yet. Click \"Library folders…\" to add one.";
        }
        String filtered = request.options().filter() == ViewOptions.DEFAULT.filter() ? ""
                : " (showing only " + request.options().filter() + ")";
        return switch (request.page()) {
            case FOLDERS -> request.recursive() ? "Nothing in this folder" + filtered + "."
                    : "No media directly in this folder" + filtered + ". Tick \"Include subfolders\" to see what's inside.";
            case ALBUMS -> "Nothing in this album" + filtered + ".";
            case FAVORITES -> "No favorites" + filtered + ". Press F on a photo, or right-click it › Add to Favorites.";
            case RECENTLY_ADDED -> "Nothing added yet" + filtered + ".";
            case HIDDEN -> "Nothing hidden" + filtered + ". Press H on a photo to hide it from everywhere but here."
                    + (OsHiddenMark.SUPPORTED && !request.options().osHiddenShown()
                            ? " Files marked Hidden in File Explorer show here too." : "");
            case TRASH -> "Trash is empty" + filtered + ".";
        };
    }

    /**
     * Puts the right pieces on screen for {@code page}. Only when the page (or album open / closed) changed:
     * moving the grid to a new parent on every reload could lose its scroll position.
     */
    private void showLayout(Page page) {
        // The Hidden page's hint follows the File Explorer switch.
        Object layout = List.of(page, openAlbum == null, page == Page.HIDDEN && options.osHiddenShown());
        if (layout.equals(shownLayout)) {
            return;
        }
        shownLayout = layout;
        HBox header = new HBox(12);
        header.getStyleClass().add("collections-header");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        folderSplit.getItems().clear();
        hint.setTextOverrun(page == Page.FOLDERS ? OverrunStyle.LEADING_ELLIPSIS : OverrunStyle.ELLIPSIS);
        hint.setText(switch (page) {
            case HIDDEN -> "Hidden items are shown only here: not on the Timeline, in search or in other pages."
                    + (!OsHiddenMark.SUPPORTED ? "" : options.osHiddenShown()
                            ? " Files marked Hidden in File Explorer show everywhere (Settings)."
                            : " That includes files marked Hidden in File Explorer.");
            case TRASH -> "Items in Trash are still on disk. Restore them, or delete them permanently"
                    + " (right-click, or Delete).";
            case RECENTLY_ADDED -> "Newest added to Prismo first.";
            default -> "";
        });
        switch (page) {
            case FOLDERS -> {
                header.getChildren().addAll(title, hint, spacer, recursive);
                folderSplit.getItems().setAll(tree, gridHolder);
                folderSplit.setDividerPositions(0.28);
                setCenter(new BorderPane(folderSplit, header, null, null, null));
            }
            case ALBUMS -> {
                if (openAlbum == null) {
                    header.getChildren().add(title);
                    setCenter(new BorderPane(albumGrid, header, null, null, null));
                } else {
                    header.getChildren().addAll(backToAlbums, title);
                    setCenter(new BorderPane(gridHolder, header, null, null, null));
                }
            }
            default -> {
                header.getChildren().addAll(title, hint);
                setCenter(new BorderPane(gridHolder, header, null, null, null));
            }
        }
    }

    /** Rebuilds the tree from fresh counts, keeping what was expanded and the chosen folder. */
    private void showTree(List<FolderTree.Node> nodes) {
        rebuildingTree = true;
        try {
            TreeItem<FolderTree.Node> root = new TreeItem<>();
            TreeItem<FolderTree.Node>[] chosen = itemArray();
            for (FolderTree.Node node : nodes) {
                root.getChildren().add(treeItem(node, chosen, true));
            }
            tree.setRoot(root);
            if (chosen[0] != null) {
                for (TreeItem<FolderTree.Node> up = chosen[0].getParent(); up != null; up = up.getParent()) {
                    up.setExpanded(true);
                }
                tree.getSelectionModel().select(chosen[0]);
            }
        } finally {
            rebuildingTree = false;
        }
    }

    @SuppressWarnings("unchecked")
    private static TreeItem<FolderTree.Node>[] itemArray() {
        return (TreeItem<FolderTree.Node>[]) new TreeItem<?>[1];
    }

    private TreeItem<FolderTree.Node> treeItem(FolderTree.Node node, TreeItem<FolderTree.Node>[] chosen, boolean top) {
        TreeItem<FolderTree.Node> item = new TreeItem<>(node);
        for (FolderTree.Node child : node.children()) {
            item.getChildren().add(treeItem(child, chosen, false));
        }
        item.setExpanded(top || expanded.contains(node.path()));
        item.expandedProperty().addListener((_, _, open) -> {
            if (open) {
                expanded.add(node.path());
            } else {
                expanded.remove(node.path());
            }
        });
        if (node.path().equals(folder)) {
            chosen[0] = item;
        }
        return item;
    }

    /** A page in the list on the left: its icon and name. */
    private static final class PageCell extends ListCell<Page> {
        @Override
        protected void updateItem(Page page, boolean empty) {
            super.updateItem(page, empty);
            setText(empty || page == null ? null : page.toString());
            setGraphic(empty || page == null ? null : page.icon().node(PAGE_ICON_SIZE));
        }
    }

    /**
     * A folder icon, the name and (muted) the count, which follows the Include subfolders box (everything below,
     * or only its own). A library folder shows only its own name; the full path is in the tooltip.
     */
    private final class FolderCell extends TreeCell<FolderTree.Node> {
        private final Label name = new Label();
        private final Label count = new Label();
        private final HBox row = new HBox(8, Icon.FOLDER.node(FOLDER_ICON_SIZE), name, count);
        private final Tooltip path = new Tooltip();

        FolderCell() {
            name.getStyleClass().add("folder-name");
            count.getStyleClass().add("folder-count");
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(FolderTree.Node node, boolean empty) {
            super.updateItem(node, empty);
            setText(null);
            if (empty || node == null) {
                setGraphic(null);
                setTooltip(null);
            } else {
                name.setText(node.shownName());
                count.setText("%,d".formatted(recursive.isSelected() ? node.totalCount() : node.ownCount()));
                path.setText(node.path());
                setGraphic(row);
                setTooltip(path);
            }
        }
    }

    // --- For tests and tools ---

    TreeView<FolderTree.Node> tree() {
        return tree;
    }

    AlbumGrid albumGrid() {
        return albumGrid;
    }

    CheckBox recursiveBox() {
        return recursive;
    }

    Label title() {
        return title;
    }
}
