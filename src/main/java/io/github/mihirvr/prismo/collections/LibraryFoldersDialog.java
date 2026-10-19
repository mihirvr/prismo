package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.db.LibraryRootStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Library folders…": the folders Prismo shows, each with its count, an Enabled box and Remove; plus Add.
 *
 * <p>The dialog only asks; the app does the work ({@link Operations}) on its indexing thread, so a change never
 * runs at the same time as an index run or a watcher batch. While one runs, the controls are disabled; then the
 * list is read again. Reading the list (counts, "can it be reached?") is I/O, so it runs on a virtual thread.
 * All methods run on the JavaFX Application Thread.
 */
public final class LibraryFoldersDialog {

    private static final Logger log = LoggerFactory.getLogger(LibraryFoldersDialog.class);

    /** What the dialog asks the app to do. Each completes when done (on any thread). */
    public interface Operations {

        /** Adds a folder; completes with the message to show ("Added …", "… is already in the library …"). */
        CompletableFuture<String> add(Path folder);

        /** Turns a library folder on (watched, indexed, shown) or off (none of these; its rows are kept). */
        CompletableFuture<Void> setEnabled(Path root, boolean enabled);

        /** Removes a library folder and forgets its rows; completes with how many were forgotten. */
        CompletableFuture<Integer> remove(Path root);
    }

    /** One row of the list, read off the UI thread. */
    record Row(LibraryRootStore.Root root, int count, boolean reachable) {
    }

    private final LibraryRootStore store;
    private final Operations operations;
    private final Stage stage = new Stage();
    private final VBox rows = new VBox(6);
    private final Label message = new Label();
    private final Button add = new Button("Add folder…");
    private List<Row> shown = List.of();

    public LibraryFoldersDialog(Window owner, LibraryRootStore store, Operations operations) {
        this.store = store;
        this.operations = operations;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Library folders");

        Label intro = new Label("Prismo shows the photos and videos in these folders and every folder inside them."
                + " It only reads them: your files are never changed.");
        intro.setWrapText(true);
        rows.getStyleClass().add("library-rows");
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(220);
        message.setWrapText(true);
        message.getStyleClass().add("library-message");
        add.setOnAction(_ -> chooseFolder());
        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(_ -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, add, spacer, close);
        VBox bottom = new VBox(8, message, buttons);
        BorderPane root = new BorderPane(scroll, intro, null, bottom, null);
        BorderPane.setMargin(intro, new Insets(0, 0, 10, 0));
        BorderPane.setMargin(bottom, new Insets(10, 0, 0, 0));
        root.setPadding(new Insets(14));
        root.getStyleClass().add("library-dialog");
        Scene scene = new Scene(root, 640, 380);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.getProperties().put(LibraryFoldersDialog.class, this); // lets tools find the dialog from its window
    }

    /** Shows the dialog (not blocking) and reads the list. */
    public void show() {
        stage.show();
        reload();
    }

    // --- Reading the list ---

    private void reload() {
        Thread.ofVirtual().name("prismo-library-list").start(() -> {
            try {
                List<LibraryRootStore.Root> roots = store.all();
                List<Integer> counts = store.counts(roots);
                List<Row> read = new ArrayList<>();
                for (int i = 0; i < roots.size(); i++) {
                    read.add(new Row(roots.get(i), counts.get(i), Files.isDirectory(roots.get(i).path())));
                }
                Platform.runLater(() -> showRows(read));
            } catch (Exception e) {
                log.warn("Could not read the library folders", e);
                Platform.runLater(() -> message.setText("Could not read the library folders: " + e.getMessage()));
            }
        });
    }

    private void showRows(List<Row> read) {
        shown = List.copyOf(read);
        rows.getChildren().clear();
        if (read.isEmpty()) {
            rows.getChildren().add(new Label("No library folders yet. Click \"Add folder…\" to choose one."));
        }
        for (Row row : read) {
            rows.getChildren().add(rowNode(row));
        }
        setBusy(false);
    }

    private HBox rowNode(Row row) {
        Path path = row.root().path();
        CheckBox enabled = new CheckBox();
        enabled.setSelected(row.root().enabled());
        enabled.setTooltip(new Tooltip("On: shown, watched and kept up to date. Off: hidden everywhere and not"
                + " watched; nothing is forgotten (favorites, Trash…), and turning it on again brings it all back."));
        enabled.setOnAction(_ -> run(operations.setEnabled(path, enabled.isSelected()),
                _ -> (enabled.isSelected() ? "Turned on " : "Turned off ") + path + "."));
        Label name = new Label(path.toString());
        name.getStyleClass().add("library-path");
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS); // the end of a path is what tells folders apart
        name.setTooltip(new Tooltip(path.toString()));
        String info = "%,d items".formatted(row.count()) + (row.root().enabled() ? "" : " · off")
                + (row.reachable() ? "" : " · can't be reached right now (unplugged?)");
        Label details = new Label(info);
        details.getStyleClass().add("library-info");
        VBox text = new VBox(2, name, details);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button remove = new Button("Remove…");
        remove.setMinWidth(Region.USE_PREF_SIZE);
        remove.setOnAction(_ -> askToRemove(row.root()));
        HBox node = new HBox(10, enabled, text, remove);
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("library-row");
        return node;
    }

    // --- Actions ---

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add a library folder");
        File folder = chooser.showDialog(stage);
        if (folder != null) {
            addFolder(folder.toPath());
        }
    }

    /** What "Add folder…" does once a folder is chosen. Package-private: tools can't drive the native chooser. */
    void addFolder(Path folder) {
        run(operations.add(folder), text -> text);
    }

    /**
     * Counts the folder's items again (the watcher may have added some since the list was read), then asks, so
     * the number in the question is the number that will be forgotten.
     */
    private void askToRemove(LibraryRootStore.Root root) {
        setBusy(true);
        Thread.ofVirtual().name("prismo-library-count").start(() -> {
            int count;
            try {
                count = store.counts(List.of(root)).getFirst();
            } catch (Exception e) {
                log.warn("Could not count {}", root.path(), e);
                Platform.runLater(() -> {
                    message.setText("Could not count the items in " + root.path() + ": " + e.getMessage());
                    setBusy(false);
                });
                return;
            }
            Platform.runLater(() -> {
                setBusy(false);
                if (confirmRemove(root.path(), count)) {
                    run(operations.remove(root.path()), forgotten -> "Removed " + root.path() + "; Prismo forgot "
                            + "%,d items. The files on disk were not touched.".formatted(forgotten));
                }
            });
        });
    }

    /** Asks before forgetting a folder's items. Cancel is the default button. */
    private boolean confirmRemove(Path root, int count) {
        ButtonType remove = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, ("Prismo forgets its %,d items, including favorites,"
                + " hidden items and Trash. The files on disk are not touched.\n\nTo hide the folder for now and"
                + " keep all that, untick it instead.").formatted(count), remove, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setTitle("Remove library folder");
        alert.setHeaderText("Remove " + root + " from Prismo?");
        ((Button) alert.getDialogPane().lookupButton(remove)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return alert.showAndWait().filter(remove::equals).isPresent();
    }

    private <T> void run(CompletableFuture<T> work, Function<T, String> done) {
        setBusy(true);
        message.setText("Working…");
        work.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                log.warn("Library folder change failed", cause);
                message.setText("That didn't work: " + cause.getMessage());
            } else {
                message.setText(done.apply(value));
            }
            reload();
        }));
    }

    private void setBusy(boolean busy) {
        rows.setDisable(busy);
        add.setDisable(busy);
    }

    // --- For tests and tools ---

    Stage stage() {
        return stage;
    }

    List<Row> shown() {
        return shown;
    }

    Label message() {
        return message;
    }
}
