package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.index.FileOperations;
import io.github.mihirvr.prismo.platform.FileManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What can be done with an item's file, the same from every grid and the viewer (right-click menus, the viewer's
 * "More" button, keys): Show in folder, Open with default app and Copy (the file, for pasting in Explorer or another
 * app: no cut or paste), which don't change the file; and Rename (F2) and Move to folder, which do, only when asked
 * ({@link FileOperations}). Also reads an item's details for the viewer's Info panel.
 *
 * <p>All methods run on the JavaFX Application Thread; disk and database work goes to a virtual thread.
 */
public final class FileItems {

    private static final Logger log = LoggerFactory.getLogger(FileItems.class);

    /** Ctrl+C (Cmd+C on macOS): copy the file. */
    public static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);

    /** F2: rename, as in File Explorer. */
    public static final KeyCombination RENAME = new KeyCodeCombination(KeyCode.F2);

    /**
     * Told around a rename or move: first which items' files are about to change (so a video that's playing can
     * let go of its file; Windows won't rename an open file), then where they are now (empty if nothing moved).
     */
    public interface Listener {
        void releasing(Set<Long> ids);

        void moved(Map<Long, Path> newPaths);
    }

    private static final Listener NO_LISTENER = new Listener() {
        @Override
        public void releasing(Set<Long> ids) {
        }

        @Override
        public void moved(Map<Long, Path> newPaths) {
        }
    };

    /** Does nothing (tests and tools without a database). */
    public static final FileItems NONE = new FileItems(null, _ -> { }, _ -> { });

    private final Database database;
    private final Consumer<String> openExternally;
    private final Consumer<String> status;
    /** Rename and Move; null where there is no library (tests and tools): then they aren't offered. */
    private FileOperations operations;
    private Supplier<Window> owner = () -> null;
    private Runnable changed = () -> { };
    private Listener listener = NO_LISTENER;

    /**
     * @param openExternally opens a file URI in the system's default app ({@code HostServices::showDocument})
     * @param status         shows a short message (the status bar)
     */
    public FileItems(Database database, Consumer<String> openExternally, Consumer<String> status) {
        this.database = database;
        this.openExternally = openExternally;
        this.status = status;
    }

    /**
     * Turns on Rename and Move.
     *
     * @param owner   the window their dialogs belong to
     * @param changed called after a file moved, so the views reload
     */
    public void setOperations(FileOperations operations, Supplier<Window> owner, Runnable changed) {
        this.operations = operations;
        this.owner = owner;
        this.changed = changed;
    }

    /** The viewer: lets go of a playing video before its file changes, and follows it to its new path. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The menu entries for {@code item}. */
    public List<MenuItem> menuItems(TimelineItem item) {
        List<MenuItem> entries = new ArrayList<>(List.of(
                Menus.item("Show in folder", Icon.FOLDER, null, () -> showInFolder(item)),
                Menus.item("Open with default app", Icon.OPEN_EXTERNAL, null, () -> openWithDefaultApp(item)),
                Menus.item("Copy", Icon.COPY, COPY, () -> copy(item))));
        if (operations != null && !item.trashed()) {
            entries.add(Menus.item("Rename\u2026", Icon.RENAME, RENAME, () -> rename(item)));
            entries.add(Menus.item("Move to folder\u2026", Icon.MOVE, null, () -> move(List.of(item))));
        }
        return entries;
    }

    /** The menu entries for a selection of several items (P7): Copy and Move to folder (not for Trash items). */
    public List<MenuItem> menuItems(List<TimelineItem> items) {
        List<MenuItem> entries = new ArrayList<>(List.of(Menus.item("Copy", Icon.COPY, COPY, () -> copy(items))));
        if (canMove(items)) {
            entries.add(Menus.item("Move to folder\u2026", Icon.MOVE, null, () -> move(items)));
        }
        return entries;
    }

    /** True if Move to folder is offered for {@code items}: there is a library, and none of them is in Trash. */
    public boolean canMove(List<TimelineItem> items) {
        return operations != null && !items.isEmpty() && items.stream().noneMatch(TimelineItem::trashed);
    }

    /**
     * Handles Ctrl+C for {@code item} (null: nothing selected).
     *
     * @return true if the key was Ctrl+C
     */
    public boolean handleKey(KeyEvent event, TimelineItem item) {
        if (COPY.match(event)) {
            if (item != null) {
                copy(item);
            }
            return true;
        }
        if (RENAME.match(event) && operations != null) {
            if (item != null && !item.trashed()) {
                rename(item);
            }
            return true;
        }
        return false;
    }

    /** Opens the file's folder in File Explorer with the file selected (elsewhere: just the folder). */
    public void showInFolder(TimelineItem item) {
        Path file = item.ref().path();
        Thread.ofVirtual().name("prismo-show-in-folder").start(() -> {
            if (!Files.exists(file)) {
                Platform.runLater(() -> status.accept("File not found: " + file));
                return;
            }
            if (FileManager.canSelect()) {
                try {
                    FileManager.showSelected(file);
                    return;
                } catch (IOException e) {
                    log.warn("Could not show {} in its folder: {}", file, e.toString());
                }
            }
            Path folder = file.getParent();
            if (folder != null) {
                Platform.runLater(() -> openExternally.accept(folder.toUri().toString()));
            }
        });
    }

    public void openWithDefaultApp(TimelineItem item) {
        log.info("Opening {} in the default app", item.ref().path());
        openExternally.accept(item.ref().path().toUri().toString());
    }

    /** Puts the file (not its picture) on the clipboard, as Explorer's Copy does; Paste in a folder copies it. */
    public void copy(TimelineItem item) {
        copy(List.of(item));
    }

    /** Puts all the files on the clipboard at once (a selection). */
    public void copy(List<TimelineItem> items) {
        if (items.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putFiles(items.stream().map(item -> item.ref().path().toFile()).toList());
        Clipboard.getSystemClipboard().setContent(content);
        status.accept(items.size() == 1
                ? "Copied " + items.getFirst().ref().path().getFileName() + ". Paste it in a folder or another app."
                : "Copied %,d files. Paste them in a folder or another app.".formatted(items.size()));
    }

    /** Asks for a new name (the extension stays), then renames the file; the row follows it. */
    public void rename(TimelineItem item) {
        if (operations == null) {
            return;
        }
        long id = item.ref().id();
        Path file = item.ref().path();
        Optional<String> newName = askName(file.getFileName().toString());
        if (newName.isEmpty()) {
            return;
        }
        listener.releasing(Set.of(id));
        Thread.ofVirtual().name("prismo-rename").start(() -> {
            try {
                Path renamed = operations.rename(id, file, newName.get());
                Platform.runLater(() -> {
                    listener.moved(Map.of(id, renamed));
                    status.accept("Renamed to " + renamed.getFileName() + ".");
                    changed.run();
                });
            } catch (IOException | SQLException e) {
                log.warn("Rename of {} failed: {}", file, e.toString());
                Platform.runLater(() -> {
                    listener.moved(Map.of());
                    status.accept("Not renamed: " + e.getMessage());
                });
            }
        });
    }

    /** Asks for a folder (and, on a name clash, what to do), then moves the files there; their rows follow. */
    public void move(List<TimelineItem> items) {
        if (operations == null || items.isEmpty()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(items.size() == 1 ? "Move " + DialogTexts.quoted(items.getFirst().ref().path().getFileName()
                .toString()) + " to" : "Move %,d items to".formatted(items.size()));
        Path from = items.getFirst().ref().path().getParent();
        if (from != null && from.toFile().isDirectory()) {
            chooser.setInitialDirectory(from.toFile());
        }
        File chosen = chooser.showDialog(owner.get());
        if (chosen == null) {
            return;
        }
        Path folder = chosen.toPath();
        List<Long> ids = items.stream().map(i -> i.ref().id()).toList();
        List<Path> files = items.stream().map(i -> i.ref().path()).toList();
        Thread.ofVirtual().name("prismo-move").start(() -> {
            int clashes = FileOperations.conflicts(files, folder);
            Platform.runLater(() -> {
                Optional<FileOperations.Conflict> conflict = clashes == 0
                        ? Optional.of(FileOperations.Conflict.KEEP_BOTH) : askConflict(clashes, folder);
                if (conflict.isEmpty()) {
                    return;
                }
                listener.releasing(Set.copyOf(ids));
                Thread.ofVirtual().name("prismo-move").start(() -> {
                    try {
                        FileOperations.Outcome outcome = operations.move(ids, files, folder, conflict.get());
                        Platform.runLater(() -> {
                            listener.moved(outcome.newPaths());
                            status.accept(moveText(outcome, folder));
                            changed.run();
                        });
                    } catch (SQLException e) {
                        log.warn("Move to {} failed: {}", folder, e.toString());
                        Platform.runLater(() -> {
                            listener.moved(Map.of());
                            status.accept("Couldn't finish moving: " + e.getMessage());
                            changed.run();
                        });
                    }
                });
            });
        });
    }

    /** "Moved 3 items to Trip. 1 skipped (same name there)." */
    static String moveText(FileOperations.Outcome outcome, Path folder) {
        StringBuilder text = new StringBuilder(outcome.moved() == 1 ? "Moved 1 item" : "Moved %,d items".formatted(
                outcome.moved())).append(" to ").append(DialogTexts.folderName(folder)).append('.');
        if (outcome.leftLibrary() > 0) {
            text.append(" That folder isn't in your library, so Prismo no longer shows ")
                    .append(outcome.leftLibrary() == 1 ? "it." : "them.");
        }
        if (outcome.skipped() > 0) {
            text.append(" %,d skipped (same name there, or already there).".formatted(outcome.skipped()));
        }
        if (outcome.failed() > 0) {
            text.append(" %,d not moved: %s".formatted(outcome.failed(), outcome.problem().orElse("")));
        }
        return text.toString();
    }

    private Optional<String> askName(String current) {
        int dot = current.lastIndexOf('.');
        String base = dot > 0 ? current.substring(0, dot) : current;
        String extension = dot > 0 ? current.substring(dot) : "";
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(owner.get());
        Appearance.matchOwner(dialog);
        dialog.setTitle("Rename");
        dialog.setHeaderText("Rename " + DialogTexts.quoted(current));
        ButtonType rename = new ButtonType("Rename", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(rename, ButtonType.CANCEL);
        TextField field = new TextField(base);
        field.setPrefColumnCount(28);
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(6, field, new Label(extension)); // the extension stays: the file keeps its type
        row.setAlignment(Pos.CENTER_LEFT);
        Label problem = new Label();
        problem.getStyleClass().add("dialog-problem");
        problem.setWrapText(true);
        problem.setMaxWidth(420);
        dialog.getDialogPane().setContent(new VBox(8, row, problem));
        Node ok = dialog.getDialogPane().lookupButton(rename);
        field.textProperty().subscribe(text -> {
            Optional<String> why = FileOperations.nameProblem(text + extension);
            problem.setText(why.orElse(""));
            ok.setDisable(why.isPresent() || (text.strip() + extension).equals(current));
        });
        dialog.setResultConverter(button -> button == rename ? field.getText().strip() + extension : null);
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        return dialog.showAndWait();
    }

    private Optional<FileOperations.Conflict> askConflict(int clashes, Path folder) {
        ButtonType keepBoth = new ButtonType("Keep both", ButtonBar.ButtonData.OK_DONE);
        ButtonType skip = new ButtonType("Skip them", ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Keep both: the moved file gets a number, like"
                + " \u201cphoto (2).jpg\u201d. Skip: those stay where they are; the rest are moved.", keepBoth, skip,
                ButtonType.CANCEL);
        alert.initOwner(owner.get());
        Appearance.matchOwner(alert);
        alert.setGraphic(null);
        alert.setTitle("Same name");
        alert.setHeaderText((clashes == 1 ? "A file with the same name is" : "%,d files with the same names are"
                .formatted(clashes)) + " already in " + DialogTexts.quoted(DialogTexts.folderName(folder)));
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) {
            return Optional.empty();
        }
        return Optional.of(answer.get() == skip ? FileOperations.Conflict.SKIP : FileOperations.Conflict.KEEP_BOTH);
    }

    /**
     * What the index knows about media {@code id}, read on a virtual thread; empty if it's no longer in the library
     * (or there is no database: tests and tools). Completes with the error if the database can't be read.
     */
    public CompletableFuture<Optional<MediaDetails>> details(long id) {
        if (database == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return database.read(c -> MediaDetails.load(c, id));
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, task -> Thread.ofVirtual().name("prismo-details").start(task));
    }
}
