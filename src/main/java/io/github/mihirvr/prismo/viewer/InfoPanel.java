package io.github.mihirvr.prismo.viewer;

import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import io.github.mihirvr.prismo.platform.FileReads;
import io.github.mihirvr.prismo.ui.FileItems;
import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.MediaDetails;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.video.VideoPlayer;
import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The viewer's Info panel, on the right of the photo or video: the file's name and folder, then
 * {@link InfoSections} (details from the index, a photo's camera settings and GPS from its EXIF, a video's formats
 * and tracks from the player, the full path). It follows the app's look (light or dark), unlike the dark viewer.
 *
 * <p>The index and the file are read on a virtual thread; a result for an item that is no longer shown is dropped
 * (a generation counter, as in {@link VideoPane}). A video's part fills in when the player has opened it
 * ({@link #setVideo}). All methods run on the JavaFX Application Thread.
 */
final class InfoPanel extends VBox {

    private static final Logger log = LoggerFactory.getLogger(InfoPanel.class);
    static final double WIDTH = 340;
    private static final double LABEL_WIDTH = 104;
    private static final Executor BACKGROUND = task -> Thread.ofVirtual().name("prismo-info").start(task);

    private final FileItems fileItems;
    private final Label name = new Label();
    private final Label folder = new Label();
    private final VBox sections = new VBox(18);
    private final Label message = new Label();

    private TimelineItem item;
    private MediaDetails details;
    private Metadata metadata;
    private VideoPlayer.Info video;
    /** Bumped for every item shown: reads for an older one are dropped. */
    private long generation;

    /** @param onClose the close button (the viewer closes the panel) */
    InfoPanel(FileItems fileItems, Runnable onClose) {
        this.fileItems = fileItems;
        getStyleClass().add("info-panel");
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);

        Label title = new Label("Info");
        title.getStyleClass().add("info-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button(null, Icon.CLOSE.node(18));
        close.getStyleClass().add("icon-button");
        close.setTooltip(new Tooltip("Close the info (I or Esc)"));
        close.setFocusTraversable(false);
        close.setOnAction(_ -> onClose.run());
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("info-header");

        name.getStyleClass().add("info-name");
        name.setWrapText(true);
        name.setMinHeight(Region.USE_PREF_SIZE);
        folder.getStyleClass().add("info-folder");
        folder.setWrapText(true);
        folder.setMinHeight(Region.USE_PREF_SIZE);
        message.getStyleClass().add("info-message");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(4, name, folder, sections);
        VBox.setMargin(sections, new Insets(16, 0, 0, 0));
        content.getStyleClass().add("info-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        scroll.getStyleClass().add("info-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll);
        // The viewer's right-click menu is about the photo, not this panel.
        addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, ContextMenuEvent::consume);
    }

    /**
     * Shows {@code shown}: its name at once, the rest when it's read. {@code videoInfo}: what the player already
     * knows about it, or null.
     */
    void show(TimelineItem shown, VideoPlayer.Info videoInfo) {
        long mine = ++generation;
        item = shown;
        details = null;
        metadata = null;
        video = videoInfo;
        name.setText(String.valueOf(shown.ref().path().getFileName()));
        folder.setText(String.valueOf(shown.ref().path().getParent()));
        sections.getChildren().clear();
        boolean isVideo = ViewMode.of(shown.ref().extension()) == ViewMode.VIDEO;
        CompletableFuture<Metadata> exif = isVideo ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> readMetadata(shown), BACKGROUND);
        fileItems.details(shown.ref().id()).thenCombine(exif, Loaded::new).whenComplete((loaded, error) ->
                Platform.runLater(() -> {
                    if (mine == generation) {
                        loaded(loaded, error);
                    }
                }));
    }

    /** What the player found in the shown video (or null once it's closed): the Video section fills in. */
    void setVideo(VideoPlayer.Info info) {
        video = info;
        if (details != null) {
            render();
        }
    }

    /** Stops any read in progress from showing, and forgets the item (the panel is closed). */
    void clear() {
        generation++;
        item = null;
        details = null;
        metadata = null;
        video = null;
        sections.getChildren().clear();
    }

    private record Loaded(Optional<MediaDetails> details, Metadata metadata) {
    }

    /** A photo's metadata, or null if it has none Prismo can read (any problem is only logged). */
    private static Metadata readMetadata(TimelineItem item) {
        try {
            return FileReads.metadata(item.ref().path());
        } catch (IOException | ImageProcessingException | RuntimeException e) {
            log.debug("No metadata for the Info panel from {}: {}", item.ref().path(), e.toString());
            return null;
        }
    }

    private void loaded(Loaded loaded, Throwable error) {
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            log.warn("Could not read the details of {}", item.ref().path(), cause);
            showMessage("Could not read the details: " + cause.getMessage());
            return;
        }
        if (loaded.details().isEmpty()) {
            showMessage("This file is no longer in the library.");
            return;
        }
        details = loaded.details().get();
        metadata = loaded.metadata();
        render();
    }

    private void showMessage(String text) {
        message.setText(text);
        sections.getChildren().setAll(message);
    }

    private void render() {
        sections.getChildren().clear();
        for (InfoSections.Section section : InfoSections.build(details, ZoneId.systemDefault(), metadata, video)) {
            Label heading = new Label(section.title().toUpperCase(Locale.ROOT));
            heading.getStyleClass().add("info-heading");
            VBox box = new VBox(8, heading, section.title().equals("Path") ? pathLine(section) : grid(section.lines()));
            sections.getChildren().add(box);
        }
    }

    /** Label on the left, value on the right; long values wrap. */
    private static GridPane grid(List<MediaDetails.Line> lines) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(7);
        grid.getColumnConstraints().addAll(new ColumnConstraints(LABEL_WIDTH, LABEL_WIDTH, LABEL_WIDTH),
                new ColumnConstraints(0, 100, Double.MAX_VALUE, Priority.ALWAYS, HPos.LEFT, true));
        int row = 0;
        for (MediaDetails.Line line : lines) {
            Label label = new Label(line.label());
            label.getStyleClass().add("info-label");
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            Label value = new Label(line.value());
            value.getStyleClass().add("info-value");
            value.setWrapText(true);
            value.setMinHeight(Region.USE_PREF_SIZE);
            GridPane.setValignment(label, VPos.TOP);
            grid.addRow(row++, label, value);
        }
        return grid;
    }

    private static Label pathLine(InfoSections.Section section) {
        Label path = new Label(section.lines().getFirst().value());
        path.getStyleClass().addAll("info-value", "info-path");
        path.setWrapText(true);
        path.setMinHeight(Region.USE_PREF_SIZE);
        return path;
    }

    // --- For tests and tools ---

    /** The labels and values on show, "label: value" (the path as ": value"), in order. */
    List<String> shownLines() {
        return sections.lookupAll(".info-value").stream().map(node -> {
            Label value = (Label) node;
            int at = value.getParent().getChildrenUnmodifiable().indexOf(value);
            String label = at > 0 && value.getParent() instanceof GridPane
                    ? ((Label) value.getParent().getChildrenUnmodifiable().get(at - 1)).getText() : "";
            return label + ": " + value.getText();
        }).toList();
    }

    boolean loading() {
        return item != null && details == null && !sections.getChildren().contains(message);
    }
}
