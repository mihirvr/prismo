package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.Appearance;
import io.github.mihirvr.prismo.ui.DialogTexts;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.ViewOptions;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * People tags (1.2.0), the user's side: the People window for items or a folder, and making, renaming and deleting
 * people. Database reads and writes run on virtual threads; the windows on the JavaFX Application Thread. After a
 * change, {@code changed} reloads the views (counts, the People page, a People filter) and {@code status} says what
 * was done.
 */
public final class PeopleUi {

    private static final Logger log = LoggerFactory.getLogger(PeopleUi.class);

    private final Database database;
    private final Supplier<Window> owner;
    private final Runnable changed;
    private final Consumer<String> status;

    public PeopleUi(Database database, Supplier<Window> owner, Runnable changed, Consumer<String> status) {
        this.database = database;
        this.owner = owner;
        this.changed = changed;
        this.status = status;
    }

    /** Every person, A to Z (the People filter's menu). Any thread; empty if the database can't be read. */
    public List<People.Person> people() {
        try {
            return database.read(People::all);
        } catch (SQLException e) {
            log.warn("Could not read the people", e);
            return List.of();
        }
    }

    /** The People window for one item or a selection (right-click › People…). JavaFX thread. */
    public void editItems(List<TimelineItem> items) {
        if (items.isEmpty()) {
            return;
        }
        List<Long> ids = items.stream().map(item -> item.ref().id()).toList();
        String header = items.size() == 1 ? "Who is in this " + kind(items.getFirst()) + "?"
                : "Who is in these %,d items?".formatted(items.size());
        Thread.ofVirtual().name("prismo-people").start(() -> {
            try {
                List<People.Person> people = database.read(People::all);
                PeopleDialog.Counts counts = new PeopleDialog.Counts(ids.size(),
                        database.read(c -> People.counts(c, ids)));
                Platform.runLater(() -> PeopleDialog.forItems(owner.get(), header, people, counts)
                        .ifPresent(result -> save(ids, result)));
            } catch (SQLException e) {
                failed(e);
            }
        });
    }

    /** The People window for a folder (right-click on a folder in Collections › Folders). JavaFX thread. */
    public void editFolder(FolderTree.Node folder) {
        Thread.ofVirtual().name("prismo-people").start(() -> {
            try {
                List<People.Person> people = database.read(People::all);
                // Every subfolder, whatever filter the Collections tree follows (Mihir, 2026-11-08: "easy and
                // unconfusing"): with Filter = Videos the tree had dropped folders without videos, so they couldn't be
                // chosen and were left untagged. What is ticked here is what gets tagged.
                FolderTree.Node whole = find(database.read(c -> FolderTree.load(c, ViewOptions.DEFAULT)), folder.path());
                FolderTree.Node shown = whole == null ? folder : whole;
                Platform.runLater(() -> PeopleDialog.forFolder(owner.get(), shown, people, folders ->
                        database.read(c -> {
                            List<Long> ids = People.mediaInFolders(c, folders);
                            return new PeopleDialog.Counts(ids.size(), People.counts(c, ids));
                        })).ifPresent(result -> save(null, result)));
            } catch (SQLException e) {
                failed(e);
            }
        });
    }

    /** The node for {@code path} in {@code nodes} or below, or null. */
    static FolderTree.Node find(List<FolderTree.Node> nodes, String path) {
        for (FolderTree.Node node : nodes) {
            if (node.path().equals(path)) {
                return node;
            }
            FolderTree.Node below = find(node.children(), path);
            if (below != null) {
                return below;
            }
        }
        return null;
    }

    /**
     * Saves a People window's result in one transaction: makes the new people, then tags or untags. {@code ids} null:
     * the files of the result's folders, read in that same transaction (so files added meanwhile are included too).
     */
    private void save(List<Long> ids, PeopleDialog.Result result) {
        database.write(c -> {
            List<Long> files = ids != null ? ids : People.mediaInFolders(c, result.folders());
            Set<Long> add = new HashSet<>(result.add());
            for (String name : result.newPeople()) {
                add.add(People.create(c, name).id());
            }
            People.apply(c, files, add, result.remove());
            return files.size();
        }).whenComplete((files, error) -> Platform.runLater(() -> {
            if (error != null) {
                failed(error);
                return;
            }
            status.accept(files == 1 ? "People saved." : "People saved for %,d items.".formatted(files));
            changed.run();
        }));
    }

    /** "New person" on the People page. JavaFX thread. */
    public void newPerson() {
        // [0] = the name was already there (create then returns that person): say so, not "added" (1.2.0 app check).
        boolean[] known = {false};
        askName("New person", "Who is it?", "", "Add").ifPresent(name -> database.write(c -> {
            Optional<String> clean = People.cleanName(name);
            known[0] = clean.isPresent() && People.named(c, clean.get()).isPresent();
            return People.create(c, name);
        }).whenComplete((person, error) -> Platform.runLater(() -> {
            if (error != null) {
                failed(error);
                return;
            }
            status.accept(known[0] ? person.name() + " is already in People."
                    : person.name() + " added. Tag photos with right-click › People…");
            changed.run();
        })));
    }

    /** Rename on a person's card. JavaFX thread. */
    public void rename(Album card) {
        askName("Rename", "Rename " + DialogTexts.quoted(card.name()), card.name(), "Rename")
                .filter(name -> !name.equals(card.name()))
                .ifPresent(name -> database.write(c -> People.rename(c, card.personId(), name))
                        .whenComplete((renamed, error) -> Platform.runLater(() -> {
                            if (error != null) {
                                failed(error);
                            } else if (!renamed) {
                                status.accept("Someone is already called " + name + ".");
                            } else {
                                changed.run();
                            }
                        })));
    }

    /** Delete on a person's card: the tag comes off every item; the files stay. JavaFX thread. */
    public void delete(Album card) {
        ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, card.count() == 0 ? "No items are tagged with them."
                : "Their tag comes off %s. The files stay where they are."
                        .formatted(card.count() == 1 ? "1 item" : "%,d items".formatted(card.count())),
                delete, ButtonType.CANCEL);
        alert.initOwner(owner.get());
        Appearance.matchOwner(alert);
        alert.setTitle("Delete person");
        alert.setHeaderText("Delete " + card.name() + "?");
        if (alert.showAndWait().filter(delete::equals).isEmpty()) {
            return;
        }
        database.write(c -> {
            People.delete(c, card.personId());
            return null;
        }).whenComplete((_, error) -> Platform.runLater(() -> {
            if (error != null) {
                failed(error);
                return;
            }
            status.accept(card.name() + " deleted.");
            changed.run();
        }));
    }

    private Optional<String> askName(String title, String header, String current, String action) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(owner.get());
        Appearance.matchOwner(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        ButtonType ok = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        TextField field = new TextField(current);
        field.setPromptText("Name");
        field.setPrefColumnCount(24);
        Label problem = new Label();
        problem.getStyleClass().add("dialog-problem");
        dialog.getDialogPane().setContent(new VBox(8, field, problem));
        Node okButton = dialog.getDialogPane().lookupButton(ok);
        field.textProperty().subscribe(text -> okButton.setDisable(People.cleanName(text).isEmpty()));
        dialog.setResultConverter(button -> button == ok ? People.cleanName(field.getText()).orElse(null) : null);
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        return dialog.showAndWait();
    }

    private static String kind(TimelineItem item) {
        return MediaType.fromExtension(item.ref().extension()).orElse(null) == MediaType.VIDEO ? "video" : "photo";
    }

    private void failed(Throwable error) {
        log.warn("People: could not save", error);
        Platform.runLater(() -> status.accept("Could not save the people: " + error.getMessage()));
    }

    /** For the filter menu: the ids of every person (a deleted person is dropped from a saved filter). */
    public static Set<Long> ids(List<People.Person> people) {
        Set<Long> ids = new HashSet<>();
        for (People.Person person : people) {
            ids.add(person.id());
        }
        return ids;
    }
}
