package io.github.mihirvr.prismo.search;

import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.TimelineView;
import java.sql.SQLException;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
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
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class SearchBar extends HBox {

    /** How long typing must pause before searching. Not tuned: short enough to feel instant. */
    private static final Duration TYPING_PAUSE = Duration.millis(150);

    private final Searcher searcher;
    private final TimelineView results;
    private final TextField field = new TextField();
    private final Label count = new Label();
    private final Label noMatches = new Label();
    private final PauseTransition typing = new PauseTransition(TYPING_PAUSE);
    private final ReadOnlyBooleanWrapper active = new ReadOnlyBooleanWrapper(false);
    /** The text whose results are on screen, or null when none are. */
    private String shownText;
    private Runnable onLeave = () -> { };

    /** @param results the view to show results in; its owner places it in the window */
    public SearchBar(Searcher searcher, TimelineView results) {
        this.searcher = searcher;
        this.results = results;
        getChildren().setAll(field, count);
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

    /** The text in the box. For tests and tools. */
    String text() {
        return field.getText();
    }

    private void search() {
        String text = field.getText();
        searcher.search(text,
                result -> Platform.runLater(() -> show(result)),
                error -> Platform.runLater(() -> failed(text, error)));
    }

    private void show(Searcher.Result result) {
        if (!result.text().equals(field.getText())) {
            return; // the text changed since; that search is on its way
        }
        if (result.text().equals(shownText)) {
            results.setItems(result.items()); // same search, newer data (indexing): stay where the user is
        } else {
            results.showFromTop(result.items());
        }
        shownText = result.text();
        int n = result.items().size();
        count.setText(n == 1 ? "1 result" : "%,d results".formatted(n));
        noMatches.setText("No matches for “" + result.text().strip() + "”");
        active.set(true);
    }

    private void failed(String text, SQLException error) {
        if (text.equals(field.getText())) {
            count.setText("Search failed: " + error.getMessage());
        }
    }

    private void clearResults() {
        shownText = null;
        active.set(false);
        count.setText("");
        results.showFromTop(List.of()); // drops the tiles, which cancels their thumbnail loads
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
