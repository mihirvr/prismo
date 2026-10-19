package io.github.mihirvr.prismo.ui;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/**
 * The shared Filters + Sort controls: "Show [All ▾]  Sort [Date taken ▾] [Newest first]". One instance
 * drives every view (the Timeline and search results), so there is one definition of each filter and sort.
 *
 * <p>Picking a sort also resets the direction to that sort's natural one (newest / largest first, names
 * A to Z); the button flips it. The Sort can be locked ({@link #setSortLocked}) for a page with a fixed order:
 * the controls then show that order, greyed out, and go back to the user's choice when unlocked. All methods
 * run on the JavaFX Application Thread.
 */
public final class ViewOptionsBar extends HBox {

    private final ReadOnlyObjectWrapper<ViewOptions> options = new ReadOnlyObjectWrapper<>(ViewOptions.DEFAULT);
    private final ComboBox<MediaFilter> filter = new ComboBox<>();
    private final ComboBox<MediaSort> sort = new ComboBox<>();
    private final Button direction = new Button();
    private boolean sortLocked;
    /** True while the controls are set by code (locking), so their listeners don't change the choice. */
    private boolean quiet;

    public ViewOptionsBar() {
        getStyleClass().add("view-options-bar");
        filter.getItems().setAll(MediaFilter.values());
        filter.setValue(ViewOptions.DEFAULT.filter());
        filter.setTooltip(new Tooltip("Show only some kinds of media"));
        filter.getStyleClass().add("view-filter");
        sort.getItems().setAll(MediaSort.values());
        sort.setValue(ViewOptions.DEFAULT.sort());
        sort.setTooltip(new Tooltip("Sort order"));
        sort.getStyleClass().add("view-sort");
        direction.setTooltip(new Tooltip("Reverse the order"));
        direction.getStyleClass().add("view-direction");
        direction.setText(ViewOptions.DEFAULT.sort().directionLabel(ViewOptions.DEFAULT.descending()));

        filter.valueProperty().addListener((_, _, value) -> options.set(options.get().withFilter(value)));
        sort.valueProperty().addListener((_, _, value) -> {
            if (!quiet) {
                options.set(options.get().withSort(value));
            }
        });
        direction.setOnAction(_ -> options.set(options.get().withDescending(!options.get().descending())));
        options.addListener((_, _, value) -> {
            if (!sortLocked) {
                direction.setText(value.sort().directionLabel(value.descending()));
            }
        });

        getChildren().setAll(new Label("Show"), filter, new Label("Sort"), sort, direction);
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
        direction.setText(shown.sort().directionLabel(shown.descending()));
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
