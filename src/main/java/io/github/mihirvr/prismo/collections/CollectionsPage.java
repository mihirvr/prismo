package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.AlbumGrid;
import io.github.mihirvr.prismo.ui.FolderArrange;
import io.github.mihirvr.prismo.ui.FolderSections;
import io.github.mihirvr.prismo.ui.GridView;
import io.github.mihirvr.prismo.ui.Headers;
import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.Menus;
import io.github.mihirvr.prismo.ui.ItemAction;
import io.github.mihirvr.prismo.ui.MediaScope;
import io.github.mihirvr.prismo.ui.PeopleFilter;
import io.github.mihirvr.prismo.ui.ThumbnailLoader;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineQuery;
import io.github.mihirvr.prismo.ui.TimelineView;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
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
import javafx.scene.control.skin.TreeCellSkin;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.PathElement;
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
 * <p><b>Back / Forward</b> (P8): the page, folder and album on screen are a {@link Spot}. A change the user makes
 * (a page in the list, a folder in the tree, an album opened or "Albums" to go back) is reported to
 * {@link #setOnNavigate}; {@link #goTo} shows a spot again without reporting it.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class CollectionsPage extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(CollectionsPage.class);
    private static final double PAGE_ICON_SIZE = 18;
    private static final double FOLDER_ICON_SIZE = 16;
    /** How round the folder tree's elbows are. */
    private static final double ELBOW_RADIUS = 10;

    /** The pages, in the order of the list on the left. */
    public enum Page {
        FOLDERS("Folders", Icon.FOLDER), ALBUMS("Albums", Icon.ALBUMS), PEOPLE("People", Icon.PEOPLE),
        FAVORITES("Favorites", Icon.HEART),
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

    /**
     * Where the user is on this tab, for Back / Forward: the page, plus the folder chosen on Folders (null = the first
     * library folder) or the album open on Albums (null = the list of albums). Unused parts are null, so two spots
     * of the same page compare equal.
     */
    public record Spot(Page page, String folder, Album album) {

        public Spot {
            folder = page == Page.FOLDERS ? folder : null;
            album = page == Page.ALBUMS || page == Page.PEOPLE ? album : null; // People (1.2.0): the person open
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
    /** 1.2.0: the People page's cards (a person is an {@link Album} with a person id), and its buttons. */
    private final AlbumGrid peopleGrid;
    private final Button backToPeople = new Button("People", Icon.CHEVRON_LEFT.node(PAGE_ICON_SIZE));
    private final Button newPerson = new Button("New person", Icon.PLUS.node(PAGE_ICON_SIZE));
    /** Makes, renames and deletes people, and opens the People window for a folder; null = not offered. */
    private PeopleUi people;
    private final Label placeholder = new Label();
    private final SplitPane folderSplit = new SplitPane();
    private final StackPane gridHolder = new StackPane();
    private final ReadOnlyBooleanWrapper sortLocked = new ReadOnlyBooleanWrapper();

    private ViewOptions options = ViewOptions.DEFAULT;
    /** The View and Arrange of the Folders page (shared with the Grid). */
    private GridView view = GridView.DEFAULT;
    private FolderArrange arrange = FolderArrange.DEFAULT;
    /** The folder chosen in the tree (its {@code media.folder} path), or null for the first library folder. */
    private String folder;
    /** The album open on the Albums page (or the person open on People), or null for the list of cards. */
    private Album openAlbum;
    /** Folders expanded in the tree, kept across reloads (the tree is rebuilt each time). */
    private final Set<String> expanded = new HashSet<>();
    /** True while the tree is being rebuilt, so its selection changes aren't taken as clicks. */
    private boolean rebuildingTree;
    /** Told when the user moves to another page, folder or album (Back / Forward history). */
    private Runnable onNavigate = () -> { };
    /** True while {@link #goTo} puts a spot back, so its own changes aren't reported as the user's. */
    private boolean restoring;
    /** What the grid shows now (scope + options), to tell a reload (stay put) from a new view (to the top). */
    private Object shownKey;
    private boolean stale = true;
    /** The width of the tree's expand arrows (measured from a row that has one), for the guide lines. */
    private double arrowWidth = 20;
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
        Label noAlbums = new Label("No albums yet");
        noAlbums.getStyleClass().add("collections-placeholder");
        albumGrid.setPlaceholder(noAlbums);
        albumGrid.setOnOpen(album -> {
            openAlbum = album;
            refresh();
            navigated();
        });
        peopleGrid = new AlbumGrid(loader);
        peopleGrid.thumbnailSizeProperty().bind(grid.thumbnailSizeProperty());
        Label noPeople = new Label("No people yet. Add someone with New person, or right-click a photo \u203a People\u2026");
        noPeople.getStyleClass().add("collections-placeholder");
        noPeople.setWrapText(true);
        peopleGrid.setPlaceholder(noPeople);
        peopleGrid.setOnOpen(person -> {
            openAlbum = person;
            refresh();
            navigated();
        });
        peopleGrid.setOnMenu((person, event) -> {
            if (people == null) {
                return;
            }
            ContextMenu menu = new ContextMenu(
                    Menus.item("Rename\u2026", Icon.RENAME, null, () -> people.rename(person)),
                    Menus.item("Delete person\u2026", Icon.TRASH, null, () -> people.delete(person)));
            menu.show(peopleGrid, event.getScreenX(), event.getScreenY());
        });
        backToPeople.getStyleClass().add("back-button");
        backToPeople.setTooltip(new Tooltip("Back to everyone"));
        backToPeople.setOnAction(_ -> {
            openAlbum = null;
            refresh();
            navigated();
        });
        newPerson.setOnAction(_ -> {
            if (people != null) {
                people.newPerson();
            }
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
                navigated();
            }
        });

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(_ -> new FolderCell());
        tree.getSelectionModel().selectedItemProperty().addListener((_, _, item) -> {
            if (!rebuildingTree && item != null && item.getValue() != null) {
                folder = item.getValue().path();
                refresh();
                navigated();
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
            navigated();
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

    /**
     * People tags (1.2.0): the People page's New person, Rename and Delete, and right-click \u203a People\u2026 on a
     * folder in the tree.
     */
    public void setPeople(PeopleUi people) {
        this.people = people;
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

    /** Where the user is on this tab now (Back / Forward history). */
    public Spot spot() {
        return new Spot(page(), folder, openAlbum);
    }

    /** Shows {@code spot} again (Back / Forward), without reporting it as the user's move. */
    public void goTo(Spot spot) {
        restoring = true;
        try {
            pages.getSelectionModel().select(spot.page()); // its listener closes any open album
            if (spot.page() == Page.FOLDERS) {
                folder = spot.folder();
            }
            openAlbum = spot.album();
            refresh();
        } finally {
            restoring = false;
        }
    }

    /** What runs when the user moves to another page, folder or album (the app's Back / Forward history). */
    public void setOnNavigate(Runnable onNavigate) {
        this.onNavigate = onNavigate;
    }

    private void navigated() {
        if (!restoring) {
            onNavigate.run();
        }
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
            case PEOPLE -> {
                if (request.album() == null) {
                    yield new Loaded(null, null, People.cards(c, request.options()), null, request.options(),
                            List.of());
                }
                // A person's own page shows all their items: the People filter is for choosing whom to see.
                ViewOptions options = request.options().withPeople(PeopleFilter.NONE);
                MediaScope scope = MediaScope.person(request.album().personId());
                yield new Loaded(null, null, null, scope, options, TimelineQuery.load(c, scope, options, zone));
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
            case FOLDERS, ALBUMS, PEOPLE -> MediaScope.LIBRARY;
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
            // No count here: the tree shows it next to the folder.
            title.setText(folder == null ? "Folders" : String.valueOf(name == null ? folder : name));
            hint.setText(folder == null ? "" : folder);
        }
        if (loaded.albums() != null) {
            boolean everyone = request.page() == Page.PEOPLE;
            (everyone ? peopleGrid : albumGrid).setAlbums(loaded.albums());
            title.setText(titled(everyone ? "People" : "Albums", loaded.albums().size()));
            return;
        }
        if (request.album() != null) {
            title.setText(titled(request.album().name(), loaded.items().size()));
        } else if (request.page() != Page.FOLDERS) {
            title.setText(titled(request.page().toString(), loaded.items().size()));
        }
        placeholder.setText(emptyText(request, loaded));
        // The View and Arrange go with the Folders page only; every other page shows as it always did.
        boolean folders = request.page() == Page.FOLDERS;
        GridView layout = folders ? view : GridView.TIMELINE;
        FolderArrange arranged = arrange;
        String top = folder;
        grid.setSectioner(layout != GridView.FOLDERS || top == null ? null
                : items -> FolderSections.build(items, List.of(Path.of(top)), false, arranged));
        Headers headers = layout.headers(loaded.options());
        boolean dateOrdered = layout.dateOrdered(loaded.options());
        // The File Explorer switch changes which items are in, not the order: stay on the same photo.
        Object key = List.of(String.valueOf(loaded.scope()), loaded.options().withOsHiddenShown(false));
        if (key.equals(shownKey)) {
            grid.setHeaders(headers, dateOrdered);
            grid.setItems(loaded.items()); // same view, new data: stay on the same photo
        } else {
            grid.showFromTop(loaded.items(), headers, dateOrdered);
        }
        shownKey = key;
    }

    /** The View and Arrange (shared with the Grid), used on the Folders page. */
    public void setLayout(GridView newView, FolderArrange newArrange) {
        if (newView != view || !newArrange.equals(arrange)) {
            view = newView;
            arrange = newArrange;
            if (page() == Page.FOLDERS) {
                refresh();
            }
        }
    }

    /** The page on screen; listen to it to follow page changes. */
    public ReadOnlyObjectProperty<Page> pageProperty() {
        return pages.getSelectionModel().selectedItemProperty();
    }

    /** "Favorites · 12"; an empty page shows only its name (the page itself says it is empty). */
    static String titled(String name, int count) {
        return count == 0 ? name : "%s · %,d".formatted(name, count);
    }

    private String emptyText(Request request, Loaded loaded) {
        if (loaded.scope() == null) {
            return "No library folders yet";
        }
        String filtered = !request.options().isFiltered() ? ""
                : " (showing only " + request.options().filterLabel() + ")";
        return switch (request.page()) {
            case FOLDERS -> request.recursive() ? "Nothing in this folder" + filtered
                    : "Nothing directly in this folder" + filtered;
            case ALBUMS -> "Nothing in this album" + filtered;
            case PEOPLE -> "No items tagged with " + request.album().name() + filtered;
            case FAVORITES -> "No favorites" + filtered;
            case RECENTLY_ADDED -> "Nothing added yet" + filtered;
            case HIDDEN -> "Nothing hidden" + filtered;
            case TRASH -> "Trash is empty" + filtered;
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
        hint.setText(""); // the Folders page shows the folder's place here (load)
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
            case PEOPLE -> {
                if (openAlbum == null) {
                    header.getChildren().addAll(title, spacer, newPerson);
                    setCenter(new BorderPane(peopleGrid, header, null, null, null));
                } else {
                    header.getChildren().addAll(backToPeople, title);
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

        /**
         * The thread lines (1.1.0, like YouTube's reply threads): under each parent's arrow a thin line runs down past
         * its folders, and each folder gets a rounded elbow from that line into it. The line stops at the last
         * folder's elbow. One path per row, redrawn at each layout.
         */
        private final javafx.scene.shape.Path guides = new javafx.scene.shape.Path();

        FolderCell() {
            name.getStyleClass().add("folder-name");
            count.getStyleClass().add("folder-count");
            row.setAlignment(Pos.CENTER_LEFT);
            guides.getStyleClass().add("tree-guide");
            guides.setManaged(false);
            guides.setMouseTransparent(true);
            // 1.2.0: right-click a folder \u203a People\u2026 tags the files in it (and the subfolders chosen).
            setOnContextMenuRequested(event -> {
                FolderTree.Node node = getItem();
                if (people != null && node != null && !isEmpty()) {
                    new ContextMenu(Menus.item("People\u2026", Icon.PEOPLE, null, () -> people.editFolder(node)))
                            .show(this, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            TreeItem<FolderTree.Node> item = getTreeItem();
            // The tree hides its root, so a library folder is level 1: no line; its folders get one, and so on.
            int depth = isEmpty() || item == null || getTreeView() == null ? 0
                    : Math.max(0, getTreeView().getTreeItemLevel(item) - 1);
            double indent = getSkin() instanceof TreeCellSkin<?> skin ? skin.getIndent() : 10;
            // A folder without subfolders has no arrow (width 0): its lines still go under its parents' arrows.
            Node arrow = getDisclosureNode();
            if (arrow != null && arrow.isVisible()) {
                arrowWidth = Math.max(arrowWidth, arrow.getLayoutBounds().getWidth());
            }
            if (!getChildren().contains(guides)) {
                getChildren().add(guides); // the skin rebuilds the children when the graphic changes
            }
            guides.getElements().setAll(threadLines(item, depth, indent, arrow != null && arrow.isVisible()));
        }

        /**
         * The lines for a row {@code depth} levels below its library folder. Level {@code depth - 1} is the row's own
         * parent: an elbow into the row, and the line on down if more folders follow. Higher levels: a straight line
         * only while that ancestor's branch has more folders below. x ends in .5 so a 1-pixel line stays sharp.
         */
        private List<PathElement> threadLines(TreeItem<FolderTree.Node> item, int depth, double indent,
                boolean hasArrow) {
            List<PathElement> lines = new ArrayList<>();
            double height = getHeight();
            double middle = Math.round(height / 2) + 0.5;
            TreeItem<FolderTree.Node> branch = item;
            for (int level = depth - 1; level >= 0 && branch != null; level--) {
                double x = Math.floor(snappedLeftInset() + indent * level + arrowWidth / 2) + 0.5;
                boolean more = branch.nextSibling() != null;
                if (level == depth - 1) {
                    // Into the arrow if the folder has one, else up to the folder icon.
                    double end = hasArrow ? snappedLeftInset() + indent * depth + 2 : row.getLayoutX() - 4;
                    double radius = Math.min(ELBOW_RADIUS, Math.max(0, end - x));
                    lines.add(new MoveTo(x, 0));
                    lines.add(new LineTo(x, middle - radius));
                    lines.add(new ArcTo(radius, radius, 0, x + radius, middle, false, false));
                    lines.add(new LineTo(Math.max(x + radius, end), middle));
                    if (more) {
                        lines.add(new MoveTo(x, middle - radius));
                        lines.add(new LineTo(x, height));
                    }
                } else if (more) {
                    lines.add(new MoveTo(x, 0));
                    lines.add(new LineTo(x, height));
                }
                branch = branch.getParent();
            }
            return lines;
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
            requestLayout(); // the lines depend on the folders below, which a refresh can change
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
