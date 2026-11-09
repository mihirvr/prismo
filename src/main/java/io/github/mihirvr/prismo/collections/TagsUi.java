package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.index.MediaType;
import io.github.mihirvr.prismo.ui.Album;
import io.github.mihirvr.prismo.ui.Appearance;
import io.github.mihirvr.prismo.ui.DialogTexts;
import io.github.mihirvr.prismo.ui.TagKind;
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
 * Manual tags, the user's side, for one {@link TagKind} (People 1.2.0, Categories 1.3.0): the tag window for items or
 * a folder, and making, renaming and deleting tags. Database reads and writes run on virtual threads; the windows on
 * the JavaFX Application Thread. After a change, {@code changed} reloads the views (counts, the page, a filter) and
 * {@code status} says what was done.
 */
public final class TagsUi {

    private static final Logger log = LoggerFactory.getLogger(TagsUi.class);

    private final TagKind kind;
    private final Database database;
    private final Supplier<Window> owner;
    private final Runnable changed;
    private final Consumer<String> status;

    public TagsUi(TagKind kind, Database database, Supplier<Window> owner, Runnable changed, Consumer<String> status) {
        this.kind = kind;
        this.database = database;
        this.owner = owner;
        this.changed = changed;
        this.status = status;
    }

    public TagKind kind() {
        return kind;
    }

    /** Every tag of this kind, A to Z (the filter's menu). Any thread; empty if the database can't be read. */
    public List<Tags.Tag> tags() {
        try {
            return database.read(c -> Tags.all(c, kind));
        } catch (SQLException e) {
            log.warn("Could not read the {}", kind.many, e);
            return List.of();
        }
    }

    /** The tag window for one item or a selection (right-click › People… / Categories…). JavaFX thread. */
    public void editItems(List<TimelineItem> items) {
        if (items.isEmpty()) {
            return;
        }
        List<Long> ids = items.stream().map(item -> item.ref().id()).toList();
        String header = items.size() == 1 ? kind.itemQuestion(what(items.getFirst())) : kind.itemsQuestion(items.size());
        Thread.ofVirtual().name("prismo-tags").start(() -> {
            try {
                List<Tags.Tag> tags = database.read(c -> Tags.all(c, kind));
                TagDialog.Counts counts = new TagDialog.Counts(ids.size(),
                        database.read(c -> Tags.counts(c, kind, ids)));
                Platform.runLater(() -> TagDialog.forItems(kind, owner.get(), header, tags, counts)
                        .ifPresent(result -> save(ids, result)));
            } catch (SQLException e) {
                failed(e);
            }
        });
    }

    /** The tag window for a folder (right-click on a folder in Collections › Folders). JavaFX thread. */
    public void editFolder(FolderTree.Node folder) {
        Thread.ofVirtual().name("prismo-tags").start(() -> {
            try {
                List<Tags.Tag> tags = database.read(c -> Tags.all(c, kind));
                // Every subfolder, whatever filter the Collections tree follows (1.2.0 app check): with Filter =
                // Videos the tree had dropped folders without videos, so they couldn't be chosen and were left
                // untagged. What is ticked here is what gets tagged.
                FolderTree.Node whole = find(database.read(c -> FolderTree.load(c, ViewOptions.DEFAULT)), folder.path());
                FolderTree.Node shown = whole == null ? folder : whole;
                Platform.runLater(() -> TagDialog.forFolder(kind, owner.get(), shown, tags, folders ->
                        database.read(c -> {
                            List<Long> ids = Tags.mediaInFolders(c, folders);
                            return new TagDialog.Counts(ids.size(), Tags.counts(c, kind, ids));
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
     * Saves a tag window's result in one transaction: makes the new tags, then tags or untags. {@code ids} null: the
     * files of the result's folders, read in that same transaction (so files added meanwhile are included too).
     */
    private void save(List<Long> ids, TagDialog.Result result) {
        database.write(c -> {
            List<Long> files = ids != null ? ids : Tags.mediaInFolders(c, result.folders());
            Set<Long> add = new HashSet<>(result.add());
            for (String name : result.newTags()) {
                add.add(Tags.create(c, kind, name).id());
            }
            Tags.apply(c, kind, files, add, result.remove());
            return files.size();
        }).whenComplete((files, error) -> Platform.runLater(() -> {
            if (error != null) {
                failed(error);
                return;
            }
            status.accept(files == 1 ? kind.title + " saved." : "%s saved for %,d items.".formatted(kind.title, files));
            changed.run();
        }));
    }

    /** "New person" / "New category" on the page. JavaFX thread. */
    public void newTag() {
        // [0] = the name was already there (create then returns that tag): say so, not "added" (1.2.0 app check).
        boolean[] known = {false};
        askName(kind.newButton(), kind.newQuestion(), "", "Add").ifPresent(name -> database.write(c -> {
            Optional<String> clean = Tags.cleanName(name);
            known[0] = clean.isPresent() && Tags.named(c, kind, clean.get()).isPresent();
            return Tags.create(c, kind, name);
        }).whenComplete((tag, error) -> Platform.runLater(() -> {
            if (error != null) {
                failed(error);
                return;
            }
            status.accept(known[0] ? tag.name() + " is already in " + kind.title + "."
                    : tag.name() + " added. Tag photos with right-click › " + kind.menuText());
            changed.run();
        })));
    }

    /** Rename on a tag's card. JavaFX thread. */
    public void rename(Album card) {
        askName("Rename", "Rename " + DialogTexts.quoted(card.name()), card.name(), "Rename")
                .filter(name -> !name.equals(card.name()))
                .ifPresent(name -> database.write(c -> Tags.rename(c, kind, card.tagId(), name))
                        .whenComplete((renamed, error) -> Platform.runLater(() -> {
                            if (error != null) {
                                failed(error);
                            } else if (!renamed) {
                                status.accept(kind.nameTaken(name));
                            } else {
                                changed.run();
                            }
                        })));
    }

    /** Delete on a tag's card: the tag comes off every item; the files stay. JavaFX thread. */
    public void delete(Album card) {
        ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        String items = card.count() == 1 ? "1 item" : "%,d items".formatted(card.count());
        String text = kind == TagKind.PEOPLE
                ? card.count() == 0 ? "No items are tagged with them."
                        : "Their tag comes off %s. The files stay where they are.".formatted(items)
                : card.count() == 0 ? "No items have this category."
                        : "It comes off %s. The files stay where they are.".formatted(items);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, delete, ButtonType.CANCEL);
        alert.initOwner(owner.get());
        Appearance.matchOwner(alert);
        alert.setTitle("Delete " + kind.one);
        alert.setHeaderText("Delete " + card.name() + "?");
        if (alert.showAndWait().filter(delete::equals).isEmpty()) {
            return;
        }
        database.write(c -> {
            Tags.delete(c, kind, card.tagId());
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
        field.textProperty().subscribe(text -> okButton.setDisable(Tags.cleanName(text).isEmpty()));
        dialog.setResultConverter(button -> button == ok ? Tags.cleanName(field.getText()).orElse(null) : null);
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        return dialog.showAndWait();
    }

    private static String what(TimelineItem item) {
        return MediaType.fromExtension(item.ref().extension()).orElse(null) == MediaType.VIDEO ? "video" : "photo";
    }

    private void failed(Throwable error) {
        log.warn("{}: could not save", kind.title, error);
        Platform.runLater(() -> status.accept("Could not save the " + kind.many + ": " + error.getMessage()));
    }

    /** For the filter menu: the ids of every tag (a deleted one is dropped from a saved filter). */
    public static Set<Long> ids(List<Tags.Tag> tags) {
        Set<Long> ids = new HashSet<>();
        for (Tags.Tag tag : tags) {
            ids.add(tag.id());
        }
        return ids;
    }
}
