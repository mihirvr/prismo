package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.collections.PeopleChoice.Box;
import io.github.mihirvr.prismo.collections.PeopleChoice.Decision;
import io.github.mihirvr.prismo.collections.PeopleChoice.State;
import io.github.mihirvr.prismo.ui.Appearance;
import io.github.mihirvr.prismo.ui.DialogTexts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * The People window (1.2.0): who is in an item, a selection, or a whole folder. One tick box per person; the rules
 * of a box are in {@link PeopleChoice} (it only adds unless the user unticks someone). A search box finds a person,
 * or adds a new one with Enter. Before saving, a line says what will change. For a folder: "Include subfolders" and
 * a tree of its subfolders, each with its own tick, to choose exactly which folders' files are tagged; the files are
 * counted again (in the background) when that choice changes. Files added to the folder later stay untagged: the
 * window tags the files there now.
 *
 * <p>Runs on the JavaFX Application Thread; counting goes to a virtual thread.
 */
final class PeopleDialog {

    /** How many files are chosen and, per person, how many of them have that person. */
    record Counts(int total, Map<Long, Integer> have) {
    }

    /** Counts the files of the folders chosen (folder mode); runs on a background thread. */
    @FunctionalInterface
    interface Counter {
        Counts count(List<String> folders) throws Exception;
    }

    /**
     * What to save: people to add to every chosen file, people to remove from every one, new names to make and add,
     * and (folder mode) the folders whose files are meant.
     */
    record Result(Set<Long> add, Set<Long> remove, List<String> newPeople, List<String> folders) {
    }

    private static final long NEW_PERSON = -1;

    /** One person's row. A new name typed in this window has no id yet ({@link #NEW_PERSON}). */
    private final class Row {
        final long id;
        final String name;
        final CheckBox box = new CheckBox();
        final Label note = new Label();
        final HBox node;
        State state = State.NONE;
        Decision decision = Decision.KEEP;

        Row(long id, String name) {
            this.id = id;
            this.name = name;
            box.setText(name);
            box.setAllowIndeterminate(true);
            box.setMnemonicParsing(false);
            box.setOnAction(_ -> {
                decision = PeopleChoice.click(state, decision);
                show();
                summarize();
            });
            note.getStyleClass().add("people-note");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            node = new HBox(8, box, gap, note);
            node.setAlignment(Pos.CENTER_LEFT);
        }

        void show() {
            Box shown = PeopleChoice.box(state, decision);
            box.setIndeterminate(shown == Box.DASH);
            box.setSelected(shown == Box.TICKED);
            int have = counts.have().getOrDefault(id, 0);
            note.setText(state == State.SOME && decision == Decision.KEEP
                    ? "in %,d of %,d".formatted(have, counts.total()) : "");
        }
    }

    private final Dialog<Result> dialog = new Dialog<>();
    private final List<Row> rows = new ArrayList<>();
    private final VBox list = new VBox(4);
    private final Label empty = new Label("No people yet. Type a name above to add someone.");
    /** Under the rows while the typed name is nobody yet: says that Enter makes a new person (1.2.0 app check). */
    private final Label newName = new Label();
    private final Label summary = new Label();
    private final TextField search = new TextField();
    private final Button add = new Button("Add");
    private final ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    private final AtomicLong latestCount = new AtomicLong();
    private Counts counts = new Counts(0, Map.of());
    /** Folder mode: the folders whose files are meant now. */
    private List<String> folders = List.of();
    private boolean counting;

    private PeopleDialog(Window owner, String header, List<People.Person> people) {
        dialog.initOwner(owner);
        Appearance.matchOwner(dialog);
        dialog.setTitle("People");
        dialog.setHeaderText(header);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().getStyleClass().add("people-dialog");
        for (People.Person person : people) {
            rows.add(new Row(person.id(), person.name()));
        }
        search.setPromptText("Find or add a person");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().subscribe(this::filter);
        search.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                addOrTick();
                event.consume();
            }
        });
        add.setOnAction(_ -> addOrTick());
        empty.getStyleClass().add("people-note");
        empty.setWrapText(true);
        newName.getStyleClass().add("people-note");
        newName.setWrapText(true);
        summary.getStyleClass().add("people-summary");
        summary.setWrapText(true);
        summary.setMaxWidth(420);
        dialog.setResultConverter(button -> button == save ? result() : null);
    }

    /** One item or several: their ids are known, so are the counts. */
    static Optional<Result> forItems(Window owner, String header, List<People.Person> people, Counts counts) {
        PeopleDialog window = new PeopleDialog(owner, header, people);
        window.counts = counts;
        window.layOut(null);
        window.refreshRows();
        return window.show();
    }

    /**
     * A folder (from the Collections tree): which of its subfolders count, then who is in their files.
     *
     * @param folder  the folder
     * @param counter counts the files of the folders chosen
     */
    static Optional<Result> forFolder(Window owner, FolderTree.Node folder, List<People.Person> people,
            Counter counter) {
        PeopleDialog window = new PeopleDialog(owner, "Who is in " + DialogTexts.quoted(name(folder.path())) + "?",
                people);
        window.layOut(window.folderChooser(folder, counter));
        window.recount(counter);
        return window.show();
    }

    private Optional<Result> show() {
        Platform.runLater(search::requestFocus);
        summarize();
        return dialog.showAndWait();
    }

    private void layOut(Node above) {
        HBox searchRow = new HBox(6, search, add);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(260);
        scroll.setPrefViewportWidth(360);
        scroll.getStyleClass().add("people-list");
        list.setPadding(new Insets(4));
        VBox content = new VBox(10);
        if (above != null) {
            content.getChildren().add(above);
        }
        content.getChildren().addAll(searchRow, scroll, summary);
        dialog.getDialogPane().setContent(content);
    }

    /** "Include subfolders" and the subfolder tree; each change counts the files again. */
    private Node folderChooser(FolderTree.Node folder, Counter counter) {
        CheckBox subfolders = new CheckBox("Include subfolders");
        subfolders.setSelected(true);
        CheckBoxTreeItem<FolderTree.Node> root = new CheckBoxTreeItem<>(folder, null, true, true);
        List<CheckBoxTreeItem<FolderTree.Node>> all = new ArrayList<>();
        addChildren(root, folder, all);
        root.setExpanded(true);
        TreeView<FolderTree.Node> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        tree.setPrefHeight(150);
        tree.getStyleClass().add("people-folders");
        tree.setCellFactory(_ -> new CheckBoxTreeCell<>(item -> ((CheckBoxTreeItem<FolderTree.Node>) item)
                .selectedProperty(), new StringConverter<>() {
                    @Override
                    public String toString(TreeItem<FolderTree.Node> item) {
                        FolderTree.Node node = item.getValue();
                        return name(node.path()) + "  (" + "%,d".formatted(node.ownCount()) + ")";
                    }

                    @Override
                    public TreeItem<FolderTree.Node> fromString(String text) {
                        return null;
                    }
                }));
        Runnable chosen = () -> {
            List<String> picked = new ArrayList<>(List.of(folder.path()));
            if (subfolders.isSelected()) {
                for (CheckBoxTreeItem<FolderTree.Node> item : all) {
                    if (item.isSelected()) {
                        picked.add(item.getValue().path());
                    }
                }
            }
            folders = picked;
        };
        subfolders.selectedProperty().addListener((_, _, on) -> {
            tree.setDisable(!on);
            chosen.run();
            recount(counter);
        });
        for (CheckBoxTreeItem<FolderTree.Node> item : all) {
            item.selectedProperty().addListener((_, _, _) -> {
                chosen.run();
                recount(counter);
            });
        }
        chosen.run();
        VBox box = new VBox(6, subfolders);
        if (all.isEmpty()) {
            subfolders.setDisable(true);
            subfolders.setSelected(false);
            subfolders.setText("No subfolders");
        } else {
            box.getChildren().add(tree);
        }
        return box;
    }

    private static void addChildren(CheckBoxTreeItem<FolderTree.Node> parent, FolderTree.Node node,
            List<CheckBoxTreeItem<FolderTree.Node>> all) {
        for (FolderTree.Node child : node.children()) {
            CheckBoxTreeItem<FolderTree.Node> item = new CheckBoxTreeItem<>(child, null, true, true); // independent
            all.add(item);
            parent.getChildren().add(item);
            addChildren(item, child, all);
        }
    }

    /** Counts the chosen folders' files again, in the background; a newer count replaces an older one. */
    private void recount(Counter counter) {
        long number = latestCount.incrementAndGet();
        List<String> picked = List.copyOf(folders);
        counting = true;
        summarize();
        Thread.ofVirtual().name("prismo-people-count").start(() -> {
            Counts fresh;
            try {
                fresh = counter.count(picked);
            } catch (Exception e) {
                fresh = new Counts(0, Map.of());
            }
            Counts done = fresh;
            Platform.runLater(() -> {
                if (number == latestCount.get()) {
                    counts = done;
                    counting = false;
                    refreshRows();
                }
            });
        });
    }

    private void refreshRows() {
        for (Row row : rows) {
            row.state = row.id == NEW_PERSON ? State.NONE
                    : PeopleChoice.state(counts.have().getOrDefault(row.id, 0), counts.total());
            row.show();
        }
        filter(search.getText());
        summarize();
    }

    /** Shows the rows whose name holds the search text; the Add button says what Enter does. */
    private void filter(String text) {
        String wanted = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        List<Node> shown = new ArrayList<>();
        for (Row row : rows) {
            if (wanted.isEmpty() || row.name.toLowerCase(Locale.ROOT).contains(wanted)) {
                shown.add(row.node);
            }
        }
        Optional<String> name = People.cleanName(text);
        boolean someoneNew = name.isPresent() && exact(name.get()) == null;
        if (someoneNew) {
            newName.setText("Press Enter or Add to add " + DialogTexts.quoted(name.get()) + " as a new person.");
            shown.add(newName);
        } else if (rows.isEmpty()) {
            shown.add(empty);
        }
        list.getChildren().setAll(shown);
        add.setDisable(name.isEmpty());
        add.setText(someoneNew ? "Add" : "Tick");
    }

    private Row exact(String name) {
        for (Row row : rows) {
            if (row.name.equalsIgnoreCase(name)) {
                return row;
            }
        }
        return null;
    }

    /** Enter or the button: ticks the person with that name, or adds a new one (made when saving). */
    private void addOrTick() {
        Optional<String> name = People.cleanName(search.getText());
        if (name.isEmpty()) {
            return;
        }
        Row row = exact(name.get());
        if (row == null) {
            row = new Row(NEW_PERSON, name.get());
            rows.add(row);
            rows.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        }
        if (PeopleChoice.box(row.state, row.decision) != Box.TICKED) {
            row.decision = Decision.ADD;
        }
        search.clear();
        refreshRows();
    }

    /** The line under the list: what saving will do. Save is off while nothing would change. */
    private void summarize() {
        Node ok = dialog.getDialogPane().lookupButton(save);
        if (counting) {
            summary.setText("Counting files…");
            ok.setDisable(true);
            return;
        }
        if (counts.total() == 0 && rows.stream().noneMatch(r -> r.id == NEW_PERSON)) {
            summary.setText(folders.isEmpty() ? "" : "No files in the folders chosen.");
            ok.setDisable(true);
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Row row : rows) {
            int have = counts.have().getOrDefault(row.id, 0);
            int changed = PeopleChoice.changes(row.decision, row.id == NEW_PERSON ? 0 : have, counts.total());
            if (changed == 0) {
                continue;
            }
            String files = changed == 1 ? "1 file" : "%,d files".formatted(changed);
            parts.add(row.decision == Decision.ADD ? "Adds " + row.name + " to " + files
                    : "Removes " + row.name + " from " + files);
        }
        summary.setText(parts.isEmpty() ? "No changes yet" : String.join("\n", parts));
        ok.setDisable(parts.isEmpty());
    }

    private Result result() {
        Set<Long> adding = new LinkedHashSet<>();
        Set<Long> removing = new LinkedHashSet<>();
        List<String> created = new ArrayList<>();
        for (Row row : rows) {
            if (row.id == NEW_PERSON) {
                if (row.decision == Decision.ADD) {
                    created.add(row.name);
                }
            } else if (row.decision == Decision.ADD && row.state != State.ALL) {
                adding.add(row.id);
            } else if (row.decision == Decision.REMOVE && row.state != State.NONE) {
                removing.add(row.id);
            }
        }
        return new Result(adding, removing, created, List.copyOf(folders));
    }

    private static String name(String folder) {
        Path name = Path.of(folder).getFileName();
        return name == null ? folder : name.toString();
    }
}
