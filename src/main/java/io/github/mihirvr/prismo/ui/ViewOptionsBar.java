package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/**
 * The shared Filters + Sort controls: "View [Timeline ▾]  Show [All ▾]  Sort [Date taken ▾] [Newest first]". One
 * instance drives every view (the Grid, search results and Collections), so there is one definition of each filter
 * and sort. The View menu belongs to the Grid tab only and is hidden elsewhere.
 *
 * <p>Picking a sort also resets the direction to that sort's natural one (newest / largest first, names
 * A to Z); the button flips it. The Sort can be locked ({@link #setSortLocked}) for a page with a fixed order:
 * the controls then show that order, greyed out, and go back to the user's choice when unlocked. All methods
 * run on the JavaFX Application Thread.
 */
public final class ViewOptionsBar extends HBox {

    private final ReadOnlyObjectWrapper<ViewOptions> options = new ReadOnlyObjectWrapper<>(ViewOptions.DEFAULT);
    /** The Grid's View; shown only on the Grid tab ({@link #setViewShown}). */
    private final ComboBox<GridView> view = new ComboBox<>();
    private final ComboBox<MediaFilter> filter = new ComboBox<>();
    private final ComboBox<MediaSort> sort = new ComboBox<>();
    private final Button direction = new Button();
    /** The Folders view's Arrange menu, next to the View menu while Folders is chosen. */
    private final MenuButton arrangeButton = new MenuButton("Arrange");
    private final ReadOnlyObjectWrapper<FolderArrange> arrange = new ReadOnlyObjectWrapper<>(FolderArrange.DEFAULT);
    private boolean viewShown = true;
    /** The Types menu: several file types can be ticked at once; none ticked = all. */
    private final MenuButton typesButton = new MenuButton();
    private List<String> availableTypes = List.of();
    private final Map<String, CheckBox> typeBoxes = new HashMap<>();
    /**
     * The People menu (1.2.0) and the Categories menu (1.3.0): a tick per tag, "No people" / "No category", and all of
     * them together or any of them.
     */
    private final Map<TagKind, MenuButton> tagButtons = new EnumMap<>(TagKind.class);
    /** Every tag of each kind, id to name, A to Z; read each time its menu opens. */
    private final Map<TagKind, Supplier<Map<Long, String>>> tagNames = new EnumMap<>(TagKind.class);
    /** The word "Sort" before the sort box; the first thing left out when the bar is short of room (1.3.1). */
    private final Label sortLabel = new Label("Sort");
    /** Shown as their icon only when the bar is short of room, after the "Sort" word ({@link #fit}). */
    private final List<Labeled> iconWhenNarrow = new ArrayList<>();
    /** Left out when the bar is still short of room after that. */
    private final List<Node> hiddenWhenNarrow = new ArrayList<>();
    /** True while {@link #fit} changes the bar, so the layout pass it causes doesn't start it again. */
    private boolean fitting;
    private boolean sortLocked;
    /** True while the controls are set by code (locking), so their listeners don't change the choice. */
    private boolean quiet;

    public ViewOptionsBar() {
        this(ViewOptions.DEFAULT, GridView.DEFAULT, FolderArrange.DEFAULT);
    }

    /** Starting with the Filters + Sort, View and Arrange saved last time. */
    public ViewOptionsBar(ViewOptions start, GridView startView, FolderArrange startArrange) {
        options.set(start);
        getStyleClass().add("view-options-bar");
        view.getItems().setAll(GridView.values());
        view.setValue(startView);
        view.getStyleClass().add("view-layout");
        view.setTooltip(new Tooltip("View"));
        filter.getItems().setAll(MediaFilter.values());
        filter.setValue(start.filter());
        filter.setTooltip(new Tooltip("Show only some kinds of media"));
        filter.getStyleClass().add("view-filter");
        sort.getItems().setAll(MediaSort.values());
        sort.setValue(start.sort());
        sort.setTooltip(new Tooltip("Sort order"));
        sort.getStyleClass().add("view-sort");
        direction.setTooltip(new Tooltip("Reverse the order"));
        direction.getStyleClass().add("view-direction");
        showDirection(start.sort(), start.descending());

        filter.valueProperty().addListener((_, _, value) -> options.set(options.get().withFilter(value)));
        sort.valueProperty().addListener((_, _, value) -> {
            if (!quiet) {
                options.set(options.get().withSort(value));
            }
        });
        direction.setOnAction(_ -> options.set(options.get().withDescending(!options.get().descending())));
        options.addListener((_, _, value) -> {
            if (!sortLocked) {
                showDirection(value.sort(), value.descending());
            }
        });

        arrange.set(startArrange);
        arrangeButton.getStyleClass().add("view-arrange");
        fillArrangeMenu();
        arrange.addListener((_, _, _) -> fillArrangeMenu());
        view.valueProperty().addListener((_, _, _) -> updateArrangeShown());

        typesButton.getStyleClass().add("view-types");
        fillTypesMenu();
        options.addListener((_, old, value) -> {
            if (!old.types().equals(value.types())) {
                showChosenTypes();
            }
        });

        for (TagKind kind : TagKind.values()) {
            MenuButton button = new MenuButton();
            button.getStyleClass().add(kind == TagKind.PEOPLE ? "view-people" : "view-categories");
            // 1.3.1: the kind's icon and a short text ("All", "2", "None"), so the bar fits a smaller window; the
            // full wording is in the tooltip.
            button.setGraphic(Icon.of(kind).node(16));
            button.setTooltip(new Tooltip(kind.filterTip));
            button.setOnShowing(_ -> fillTagMenu(kind));
            tagButtons.put(kind, button);
            tagNames.put(kind, Map::of);
            showChosenTags(kind);
        }
        options.addListener((_, old, value) -> {
            for (TagKind kind : TagKind.values()) {
                if (!old.tags(kind).equals(value.tags(kind))) {
                    showChosenTags(kind);
                }
            }
        });

        getChildren().setAll(view, arrangeButton, filter, typesButton, tagButtons.get(TagKind.PEOPLE),
                tagButtons.get(TagKind.CATEGORIES), sortLabel, sort, direction);
        updateArrangeShown();
    }

    /**
     * The file types in the library, for the Types menu ({@link FileTypes}: {@code jpg}, {@code png}…). A ticked
     * type that is no longer in the library stays listed, so it can be unticked.
     */
    public void setAvailableTypes(List<String> types) {
        List<String> sorted = FileTypes.typesOf(types);
        if (!sorted.equals(availableTypes)) {
            availableTypes = sorted;
            fillTypesMenu();
        }
    }

    /** "All", then one tick box per type. The menu stays open while boxes are ticked. */
    private void fillTypesMenu() {
        Set<String> chosen = options.get().types();
        typeBoxes.clear();
        MenuItem all = new MenuItem("All types");
        all.setOnAction(_ -> options.set(options.get().withTypes(Set.of())));
        List<MenuItem> items = new ArrayList<>(List.of(all, new SeparatorMenuItem()));
        Set<String> listed = new TreeSet<>(availableTypes);
        listed.addAll(chosen);
        for (String type : listed) {
            CheckBox box = new CheckBox(FileTypes.label(type));
            box.setSelected(chosen.contains(type));
            typeBoxes.put(type, box);
            box.selectedProperty().addListener((_, _, on) -> {
                if (quiet) {
                    return;
                }
                Set<String> now = new TreeSet<>(options.get().types());
                if (on) {
                    now.add(type);
                } else {
                    now.remove(type);
                }
                options.set(options.get().withTypes(now));
            });
            items.add(tickItem(box));
        }
        typesButton.getItems().setAll(items);
        showChosenTypes();
    }

    /**
     * A menu row with a tick box that stays open when used (2026-11-10). The mouse ticks the box itself, as before;
     * Enter and Space on the highlighted row fire the row (a mouse click on the box does not), and the row's action
     * flips the box. Before, the row had no action, so Enter and Space did nothing.
     */
    private static CustomMenuItem tickItem(CheckBox box) {
        box.setFocusTraversable(false);
        CustomMenuItem item = new CustomMenuItem(box, false);
        item.getStyleClass().add("type-item");
        item.setOnAction(_ -> box.setSelected(!box.isSelected()));
        return item;
    }

    /** The button text and the ticks follow the chosen types, without rebuilding the (maybe open) menu. */
    private void showChosenTypes() {
        Set<String> chosen = options.get().types();
        typesButton.setText(chosen.isEmpty() ? "All types"
                : chosen.size() <= 2 ? new ViewOptions(MediaFilter.ALL, MediaSort.DATE_TAKEN, true, false, chosen)
                        .filterLabel()
                : chosen.size() + " types");
        if (!typeBoxes.keySet().containsAll(chosen)) {
            fillTypesMenu(); // a type chosen elsewhere (a saved setting) that the menu doesn't list yet
            return;
        }
        quiet = true;
        try {
            typeBoxes.forEach((type, box) -> box.setSelected(chosen.contains(type)));
        } finally {
            quiet = false;
        }
    }

    MenuButton typesButton() {
        return typesButton;
    }

    /** Where the People menu reads the people from (1.2.0): id to name, A to Z. */
    public void setPeople(Supplier<Map<Long, String>> people) {
        setTags(TagKind.PEOPLE, people);
    }

    /** Where the menu of {@code kind} reads its tags from: id to name, A to Z. */
    public void setTags(TagKind kind, Supplier<Map<Long, String>> names) {
        tagNames.put(kind, names);
    }

    MenuButton tagButton(TagKind kind) {
        return tagButtons.get(kind);
    }

    /**
     * The People or Categories menu, made fresh each time it opens (tags come and go): Everyone / Every category; all
     * of them together or any of them; No people / No category; one tick per tag. Ticking stays in the menu; a tag
     * deleted meanwhile drops out of the filter.
     */
    private void fillTagMenu(TagKind kind) {
        Map<Long, String> every = tagNames.get(kind).get();
        TagFilter chosen = options.get().tags(kind).keepOnly(every.keySet());
        if (!chosen.equals(options.get().tags(kind))) {
            options.set(options.get().withTags(chosen));
        }
        MenuItem all = new MenuItem(kind.everything);
        all.setOnAction(_ -> options.set(options.get().withTags(TagFilter.none(kind))));
        ToggleGroup mode = new ToggleGroup();
        RadioMenuItem together = new RadioMenuItem("All of them together");
        RadioMenuItem any = new RadioMenuItem("Any of them");
        together.setToggleGroup(mode);
        any.setToggleGroup(mode);
        (chosen.together() ? together : any).setSelected(true);
        together.setOnAction(_ -> options.set(options.get().withTags(options.get().tags(kind).withTogether(true))));
        any.setOnAction(_ -> options.set(options.get().withTags(options.get().tags(kind).withTogether(false))));
        List<MenuItem> items = new ArrayList<>(List.of(all, new SeparatorMenuItem(), together, any,
                new SeparatorMenuItem()));
        CheckBox untagged = new CheckBox(kind.untagged);
        untagged.setSelected(chosen.untagged());
        untagged.selectedProperty().addListener((_, _, on) ->
                options.set(options.get().withTags(options.get().tags(kind).withUntagged(on))));
        items.add(tickItem(untagged));
        if (every.isEmpty()) {
            MenuItem none = new MenuItem(kind.noneYet());
            none.setDisable(true);
            items.add(none);
        }
        List<CheckBox> boxes = new ArrayList<>();
        boolean[] clearing = {false}; // the boxes are being unticked for "No people": not the user's own clicks
        every.forEach((id, name) -> {
            CheckBox box = new CheckBox(name);
            box.setMnemonicParsing(false);
            box.setSelected(chosen.ids().contains(id));
            box.selectedProperty().addListener((_, _, on) -> {
                if (clearing[0]) {
                    return;
                }
                options.set(options.get().withTags(options.get().tags(kind).withTag(id, on)));
                untagged.setSelected(options.get().tags(kind).untagged());
            });
            boxes.add(box);
            items.add(tickItem(box));
        });
        untagged.selectedProperty().addListener((_, _, on) -> {
            if (on) {
                clearing[0] = true;
                boxes.forEach(box -> box.setSelected(false)); // "No people" and people don't mix
                clearing[0] = false;
            }
        });
        tagButtons.get(kind).getItems().setAll(items);
    }

    /**
     * The button's text, short next to its icon (1.3.1): "All", the number ticked, or "None"; the tooltip says it in
     * full: "Everyone", "People: 2", "No people"; "Every category", "Categories: 1", "No category".
     */
    private void showChosenTags(TagKind kind) {
        TagFilter chosen = options.get().tags(kind);
        String label = chosen.label();
        String full = label == null ? kind.everything : label;
        MenuButton button = tagButtons.get(kind);
        button.setText(shortTagText(chosen));
        button.setAccessibleText(full);
        button.getTooltip().setText(full + "\n" + kind.filterTip);
    }

    /** "All" (nothing filtered), the number of tags ticked, or "None" (only untagged items). */
    static String shortTagText(TagFilter chosen) {
        if (chosen.untagged()) {
            return "None";
        }
        return chosen.ids().isEmpty() ? "All" : String.valueOf(chosen.ids().size());
    }

    /** Shown as its icon only when the bar is short of room (the main window's "Go to date"). */
    public void iconWhenNarrow(Labeled control) {
        iconWhenNarrow.add(control);
        requestLayout();
    }

    /** Left out when the bar is short of room even then (the main window's thumbnail size slider). */
    public void hideWhenNarrow(Node node) {
        hiddenWhenNarrow.add(node);
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        fit();
        super.layoutChildren();
    }

    /**
     * Makes the bar fit its width (1.3.1) in steps, stopping at the first that fits: everything; without the word
     * "Sort"; then the {@link #iconWhenNarrow} controls as icons; then without the {@link #hiddenWhenNarrow} ones; then
     * (a half-screen window) the People and Categories buttons as icons and the direction as an arrow.
     * Only if even that is too wide do the controls shrink. Run before each layout, so it follows the window's size
     * and the buttons' changing texts, and everything comes back when there is room again.
     */
    private void fit() {
        if (fitting || getWidth() <= 0) {
            return;
        }
        fitting = true;
        try {
            for (int level = 0; level <= 4; level++) {
                narrow(level);
                if (level == 4 || prefWidth(-1) <= getWidth()) {
                    return;
                }
            }
        } finally {
            fitting = false;
        }
    }

    private void narrow(int level) {
        boolean sortWord = level < 1;
        sortLabel.setVisible(sortWord);
        sortLabel.setManaged(sortWord);
        for (Labeled control : iconWhenNarrow) {
            control.setContentDisplay(level >= 2 ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
        }
        for (Node node : hiddenWhenNarrow) {
            node.setVisible(level < 3);
            node.setManaged(level < 3);
        }
        ContentDisplay tags = level >= 4 ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT;
        tagButtons.values().forEach(button -> button.setContentDisplay(tags));
        direction.setContentDisplay(level >= 4 ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.TEXT_ONLY);
        if (level >= 4) {
            if (!direction.getStyleClass().contains("compact")) {
                direction.getStyleClass().add("compact"); // no minimum width (prismo.css)
            }
        } else {
            direction.getStyleClass().remove("compact");
        }
    }

    /** The direction button: its text, an arrow for the icon-only form, and the tooltip saying which (1.3.1). */
    private void showDirection(MediaSort shownSort, boolean descending) {
        String label = shownSort.directionLabel(descending);
        direction.setText(label);
        direction.setGraphic((descending ? Icon.ARROW_DOWN : Icon.ARROW_UP).node(16));
        direction.getTooltip().setText(label + "\nReverse the order");
    }

    /** Which steps {@link #fit} took, for tests: the word "Sort" shown, icons only, the others hidden. */
    String narrowState() {
        boolean icons = !iconWhenNarrow.isEmpty()
                && iconWhenNarrow.getFirst().getContentDisplay() == ContentDisplay.GRAPHIC_ONLY;
        boolean hidden = !hiddenWhenNarrow.isEmpty() && !hiddenWhenNarrow.getFirst().isManaged();
        return "sort=" + sortLabel.isManaged() + " icons=" + icons + " hidden=" + hidden + " compact="
                + direction.getStyleClass().contains("compact");
    }

    /** Shows the View menu (the Grid, Collections › Folders) or hides it and its space (elsewhere). */
    public void setViewShown(boolean shown) {
        viewShown = shown;
        view.setVisible(shown);
        view.setManaged(shown);
        updateArrangeShown();
    }

    /** The Folders view's Arrange; listen to it to re-arrange. Changed only through the menu. */
    public ReadOnlyObjectProperty<FolderArrange> arrangeProperty() {
        return arrange.getReadOnlyProperty();
    }

    /** Arrange only goes with the Folders view. */
    private void updateArrangeShown() {
        boolean shown = viewShown && view.getValue() == GridView.FOLDERS;
        arrangeButton.setVisible(shown);
        arrangeButton.setManaged(shown);
    }

    /** Rebuilt on each change, so the ticks always show the current choice. */
    private void fillArrangeMenu() {
        FolderArrange now = arrange.get();
        CheckMenuItem foldersFirst = new CheckMenuItem("Folders first");
        foldersFirst.setSelected(now.foldersFirst());
        foldersFirst.selectedProperty().addListener((_, _, on) -> arrange.set(arrange.get().withFoldersFirst(on)));
        arrangeButton.getItems().setAll(foldersFirst, new SeparatorMenuItem(),
                orderMenu("Folders", now.folders(), false, arrange.get()::withFolders),
                orderMenu("Top-level files", now.topFiles(), true, arrange.get()::withTopFiles),
                orderMenu("Files in folders", now.folderFiles(), true, arrange.get()::withFolderFiles));
    }

    private Menu orderMenu(String name, FolderArrange.Order chosen, boolean withSort,
            Function<FolderArrange.Order, FolderArrange> change) {
        Menu menu = new Menu(name);
        ToggleGroup group = new ToggleGroup();
        for (FolderArrange.Order order : FolderArrange.Order.values()) {
            if (order == FolderArrange.Order.SORT && !withSort) {
                continue;
            }
            RadioMenuItem item = new RadioMenuItem(order.toString());
            item.setToggleGroup(group);
            item.setSelected(order == chosen);
            item.setOnAction(_ -> arrange.set(change.apply(order)));
            menu.getItems().add(item);
        }
        return menu;
    }

    /** The Grid's View; listen to it to change the Grid's layout. Changed only through the menu. */
    public ReadOnlyObjectProperty<GridView> gridViewProperty() {
        return view.valueProperty();
    }

    /**
     * Locks the Sort to {@code fixed}'s sort and direction (shown, greyed out), or unlocks it (null) and shows the
     * user's own choice again, which locking never changes.
     */
    public void setSortLocked(ViewOptions fixed) {
        sortLocked = fixed != null;
        ViewOptions shown = sortLocked ? fixed : options.get();
        quiet = true;
        try {
            sort.setValue(shown.sort());
        } finally {
            quiet = false;
        }
        showDirection(shown.sort(), shown.descending());
        sort.setDisable(sortLocked);
        direction.setDisable(sortLocked);
    }

    /** The current choice; listen to it to reload a view. Changed only through the controls. */
    public ReadOnlyObjectProperty<ViewOptions> optionsProperty() {
        return options.getReadOnlyProperty();
    }

    /** The controls, for tests and tools. */
    ComboBox<MediaFilter> filterBox() {
        return filter;
    }

    ComboBox<MediaSort> sortBox() {
        return sort;
    }

    Button directionButton() {
        return direction;
    }
}
