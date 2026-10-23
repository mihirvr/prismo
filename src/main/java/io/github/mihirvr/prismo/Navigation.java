package io.github.mihirvr.prismo;

import io.github.mihirvr.prismo.collections.CollectionsPage;
import io.github.mihirvr.prismo.search.SearchBar;
import io.github.mihirvr.prismo.ui.NavigationHistory;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.viewer.MediaViewer;
import java.util.List;
import java.util.Objects;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Back and Forward like a web browser (P8): the mouse's side buttons ({@link MouseButton#BACK} /
 * {@link MouseButton#FORWARD}) and Alt+Left / Alt+Right, anywhere in the main window.
 *
 * <ul>
 *   <li>A <b>place</b> ({@link Place}) is the open tab (Timeline or Collections), on Collections the page and the
 *       folder or album open in it ({@link CollectionsPage.Spot}), the search on screen, and an open viewer.</li>
 *   <li>A place is recorded when the <b>user</b> goes there: a tab, a page in the Collections list, a folder in the tree,
 *       an album, a search run or cleared, or opening the viewer. Typing more of the same search changes the place
 *       without a new step. Showing a place again (Back / Forward) records nothing.</li>
 *   <li><b>In the viewer</b>, Back closes it (every way of closing it counts as going back), and Forward right after
 *       opens it again on the item that was shown (a video carries on where it was left: watch progress).</li>
 * </ul>
 * The rules are in {@link NavigationHistory} (pure, unit-tested); this class only reads and restores the screens.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
final class Navigation {

    private static final KeyCombination BACK_KEY = new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN);
    private static final KeyCombination FORWARD_KEY = new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.ALT_DOWN);

    /** The viewer as it was: the list it showed and the item on screen. Each opening is its own step (identity). */
    static final class Opened {
        private final List<TimelineItem> items;
        private final int index;

        Opened(List<TimelineItem> items, int index) {
            this.items = List.copyOf(items);
            this.index = index;
        }
    }

    /**
     * One place. {@code spot} only on the Collections tab; {@code search} the text whose results are shown, or null;
     * {@code viewer} while the viewer is open, else null.
     */
    record Place(boolean collections, CollectionsPage.Spot spot, String search, Opened viewer) {

        Place withViewer(Opened opened) {
            return new Place(collections, spot, search, opened);
        }

        /** The same tab and page (any search, no viewer). */
        boolean sameScreen(Place other) {
            return collections == other.collections && Objects.equals(spot, other.spot);
        }
    }

    private final ToggleButton timelineTab;
    private final ToggleButton collectionsTab;
    private final CollectionsPage collections;
    private final SearchBar searchBar;
    private final MediaViewer viewer;
    private final NavigationHistory<Place> history;
    /** True while a place is being shown again: the screens' own change reports are not the user's moves. */
    private boolean restoring;

    Navigation(ToggleButton timelineTab, ToggleButton collectionsTab, CollectionsPage collections, SearchBar searchBar,
            MediaViewer viewer) {
        this.timelineTab = timelineTab;
        this.collectionsTab = collectionsTab;
        this.collections = collections;
        this.searchBar = searchBar;
        this.viewer = viewer;
        this.history = new NavigationHistory<>(placeNow());
        viewer.setOnLeave(this::viewerLeaving);
    }

    /** Listens for the side buttons and Alt+Left / Alt+Right in the main window (before anything else sees them). */
    void install(Scene scene) {
        scene.addEventFilter(MouseEvent.ANY, this::onMouse);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (BACK_KEY.match(event)) {
                back();
                event.consume();
            } else if (FORWARD_KEY.match(event)) {
                forward();
                event.consume();
            }
        });
    }

    /**
     * A side button goes back or forward when pressed; its press, release and click are used up, so nothing else
     * (a tile, the video) takes them as a click.
     */
    void onMouse(MouseEvent event) {
        MouseButton button = event.getButton();
        if (button != MouseButton.BACK && button != MouseButton.FORWARD) {
            return;
        }
        EventType<? extends MouseEvent> type = event.getEventType();
        if (type == MouseEvent.MOUSE_PRESSED) {
            if (button == MouseButton.BACK) {
                back();
            } else {
                forward();
            }
        }
        if (type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_RELEASED || type == MouseEvent.MOUSE_CLICKED) {
            event.consume();
        }
    }

    /** Where the user is now (without the viewer). */
    private Place placeNow() {
        boolean onCollections = collectionsTab.isSelected();
        return new Place(onCollections, onCollections ? collections.spot() : null, searchBar.shownSearch(), null);
    }

    /** The user went to another tab, Collections page, folder or album. */
    void userNavigated() {
        if (restoring || viewer.isOpen()) {
            return;
        }
        history.visit(placeNow());
    }

    /**
     * Other search results are on screen, or the search was cleared. More letters of the same search (the same
     * screen, a search before and after) change the place without a new step; a search shown again by Back /
     * Forward arrives here when its results come in, matches the place, and does nothing.
     */
    void searchChanged() {
        if (restoring || viewer.isOpen()) {
            return;
        }
        Place now = placeNow();
        Place current = history.current();
        if (Objects.equals(now.search(), current.search())) {
            return;
        }
        if (now.search() != null && current.search() != null && current.viewer() == null && now.sameScreen(current)) {
            history.replace(now);
        } else {
            history.visit(now);
        }
    }

    /** A grid opens the viewer on {@code items.get(index)} (with the Info panel if {@code withInfo}). */
    void open(List<TimelineItem> items, int index, boolean withInfo) {
        history.visit(placeNow()); // in case the screen changed without telling (then Back comes here)
        if (withInfo) {
            viewer.openWithInfo(items, index);
        } else {
            viewer.open(items, index);
        }
        if (viewer.isOpen()) {
            history.visit(placeNow().withViewer(new Opened(items, index)));
        }
    }

    /**
     * The viewer is closing (any way: Back, Esc, its Back button): remember the item it shows for Forward, and step
     * back to the screen it was opened from, which is already under it (nothing to show).
     */
    private void viewerLeaving(List<TimelineItem> items, int index) {
        Place current = history.current();
        if (current.viewer() != null && !restoring) {
            history.replace(current.withViewer(new Opened(items, index)));
            history.back();
        }
    }

    /**
     * The viewer closed. If it did so without {@link #viewerLeaving} (its last item left the list: hidden, trashed),
     * there is nothing to open again: step back and forget Forward.
     */
    void viewerClosed() {
        if (history.current().viewer() != null) {
            history.back();
            history.clearForward();
        }
    }

    /** Back: closes the viewer if it is open, else shows the place before. */
    void back() {
        if (viewer.isOpen()) {
            viewer.close(); // viewerLeaving steps the history back
            return;
        }
        history.back().ifPresent(this::show);
    }

    /** Forward: the place Back left (the viewer again, if Back closed it). Nothing while the viewer is open. */
    void forward() {
        if (!viewer.isOpen()) {
            history.forward().ifPresent(this::show);
        }
    }

    /** Puts {@code place} on screen without recording it. */
    private void show(Place place) {
        restoring = true;
        try {
            if (place.collections()) {
                if (place.spot() != null) {
                    collections.goTo(place.spot());
                }
                collectionsTab.setSelected(true);
            } else {
                timelineTab.setSelected(true);
            }
            searchBar.showSearch(place.search());
            if (place.viewer() != null) {
                viewer.open(place.viewer().items, place.viewer().index);
            }
        } finally {
            restoring = false;
        }
    }

    // --- For tests and tools ---

    NavigationHistory<Place> history() {
        return history;
    }
}
