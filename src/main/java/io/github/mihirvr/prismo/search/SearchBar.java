package io.github.mihirvr.prismo.search;

import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.MediaFilter;
import io.github.mihirvr.prismo.ui.TimelineView;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.sql.SQLException;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * The search box (in the top bar) and the results it shows.
 *
 * <p>Search as you type: after a short pause in typing, the text goes to the {@link Searcher}; its result
 * replaces what the results view shows. While there is text in the box the bar is <i>active</i> and the
 * owner shows the results view instead of the Timeline; clearing the box (or Esc) brings the Timeline back
 * exactly where it was, because the two are separate views.
 *
 * <p>Keys in the box: Esc clears it (a second Esc hands the keyboard back to the grid); Enter or Down moves the
 * keyboard focus to the first result.
 *
 * <p><b>Back / Forward</b> (P8): {@link #setOnSearchChanged} hears when other results come on screen or the search
 * is cleared; {@link #showSearch} puts a search back (or clears it).
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class SearchBar extends HBox {

    /** How long typing must pause before searching. Not tuned: short enough to feel instant. */
    private static final Duration TYPING_PAUSE = Duration.millis(150);
    private static final double ICON_SIZE = 16;

    private final Searcher searcher;
    private final TimelineView results;
    private final TextField field = new TextField();
    private final Label count = new Label();
    /** Empties the box; shown only while there is text in it. */
    private final Button clear = new Button();
    private final Label noMatches = new Label();
    private final PauseTransition typing = new PauseTransition(TYPING_PAUSE);
    private final ReadOnlyBooleanWrapper active = new ReadOnlyBooleanWrapper(false);
    /** The shared Filters + Sort, applied to every search. */
    private ViewOptions options = ViewOptions.DEFAULT;
    /** The text and options whose results are on screen; null when none are. */
    private String shownText;
    private ViewOptions shownOptions;
    private Runnable onLeave = () -> { };
    /** Told when the search on screen changes: results for another text, or none (Back / Forward history). */
    private Runnable onSearchChanged = () -> { };

    /** @param results the view to show results in; its owner places it in the window */
    public SearchBar(Searcher searcher, TimelineView results) {
        this.searcher = searcher;
        this.results = results;
        // A magnifier inside the box on the left and a clear button on the right, both over the text field.
        Node magnifier = Icon.SEARCH.node(ICON_SIZE);
        magnifier.setMouseTransparent(true);
        magnifier.getStyleClass().add("search-icon");
        clear.setGraphic(Icon.CLOSE.node(ICON_SIZE - 2));
        clear.getStyleClass().addAll("icon-button", "search-clear");
        clear.setTooltip(new Tooltip("Clear the search (Esc)"));
        clear.setFocusTraversable(false);
        clear.setOnAction(_ -> {
            field.clear();
            field.requestFocus();
        });
        clear.visibleProperty().bind(field.textProperty().isNotEmpty());
        StackPane box = new StackPane(field, magnifier, clear);
        StackPane.setAlignment(magnifier, Pos.CENTER_LEFT);
        StackPane.setAlignment(clear, Pos.CENTER_RIGHT);
        StackPane.setMargin(magnifier, new Insets(0, 0, 0, 12));
        StackPane.setMargin(clear, new Insets(0, 4, 0, 0));
        box.getStyleClass().add("search-box");
        getChildren().setAll(box, count);
        getStyleClass().add("search-bar");
        field.getStyleClass().add("search-field");
        field.setPromptText("Search name, folder, camera, date…  (Ctrl+F)");
        count.getStyleClass().add("search-count");
        noMatches.getStyleClass().add("search-no-matches");
        results.setPlaceholder(noMatches);

        typing.setOnFinished(_ -> search());
        field.textProperty().addListener((_, _, text) -> {
            if (text.isBlank()) {
                typing.stop();
                clearResults();
            } else {
                typing.playFromStart();
            }
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** True while results are shown (there is text in the box and its first results have arrived). */
    public ReadOnlyBooleanProperty activeProperty() {
        return active.getReadOnlyProperty();
    }

    public boolean isActive() {
        return active.get();
    }

    /** Runs when the keyboard should go back to the grid (Esc on an empty box). */
    public void setOnLeave(Runnable onLeave) {
        this.onLeave = onLeave;
    }

    /** What runs when results for another text come on screen, or the search is cleared. */
    public void setOnSearchChanged(Runnable onSearchChanged) {
        this.onSearchChanged = onSearchChanged;
    }

    /** The text whose results are on screen, or null when no search is shown. */
    public String shownSearch() {
        return shownText;
    }

    /** Back / Forward: searches for {@code text} at once (null or blank: clears the box, the open tab shows again). */
    public void showSearch(String text) {
        if (text == null || text.isBlank()) {
            if (!field.getText().isEmpty()) {
                field.clear();
            }
            return;
        }
        if (!text.equals(field.getText())) {
            field.setText(text);
        }
        typing.stop();
        search();
    }

    /** Puts the keyboard in the box with its text selected (Ctrl+F). */
    public void focusField() {
        field.requestFocus();
        field.selectAll();
    }

    /** Searches again for the same text, e.g. after the index changed; does nothing when the box is empty. */
    public void refresh() {
        if (!field.getText().isBlank()) {
            typing.stop();
            search();
        }
    }

    /** New Filters + Sort: searches again if there is text in the box. */
    public void setOptions(ViewOptions newOptions) {
        options = newOptions;
        refresh();
    }

    /** The text in the box. For tests and tools. */
    String text() {
        return field.getText();
    }

    private void search() {
        String text = field.getText();
        searcher.search(text, options,
                result -> Platform.runLater(() -> show(result)),
                error -> Platform.runLater(() -> failed(text, error)));
    }

    private void show(Searcher.Result result) {
        if (!result.text().equals(field.getText()) || !result.options().equals(options)) {
            return; // the text or the options changed since; that search is on its way
        }
        if (result.text().equals(shownText) && result.options().sameOrder(shownOptions)) {
            // Same search, newer data (indexing, or the File Explorer switch): stay where the user is.
            results.setItems(result.items());
        } else {
            results.showFromTop(result.items(), result.options().grouped());
        }
        boolean otherText = !result.text().equals(shownText);
        shownText = result.text();
        shownOptions = result.options();
        int n = result.items().size();
        count.setText(n == 1 ? "1 result" : "%,d results".formatted(n));
        MediaFilter filter = result.options().filter();
        noMatches.setText("No matches for “" + result.text().strip() + "”"
                + (filter == MediaFilter.ALL ? "" : " in " + filter));
        active.set(true);
        if (otherText) {
            onSearchChanged.run();
        }
    }

    private void failed(String text, SQLException error) {
        if (text.equals(field.getText())) {
            count.setText("Search failed: " + error.getMessage());
        }
    }

    private void clearResults() {
        boolean wasShown = shownText != null;
        shownText = null;
        shownOptions = null;
        active.set(false);
        count.setText("");
        results.showFromTop(List.of(), true); // drops the tiles, which cancels their thumbnail loads
        if (wasShown) {
            onSearchChanged.run();
        }
    }

    private void onKey(KeyEvent event) {
        switch (event.getCode()) {
            case ESCAPE -> {
                if (field.getText().isEmpty()) {
                    onLeave.run();
                } else {
                    field.clear();
                }
                event.consume();
            }
            case ENTER, DOWN -> {
                TimelineItem first = results.firstItem();
                if (isActive() && first != null) {
                    results.reveal(first.ref().id());
                    results.requestFocus();
                }
                event.consume();
            }
            default -> { }
        }
    }
}
