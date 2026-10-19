package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The Albums page: one card per album (cover, name, count), in rows that fill the width.
 *
 * <p>Virtualized the same way as {@link TimelineView}: a {@link ListView} whose items are rows of cards, so
 * thousands of albums are still a few dozen nodes. The card size follows the shared thumbnail size.
 * All methods run on the JavaFX Application Thread.
 */
public final class AlbumGrid extends StackPane {

    private static final double SCROLL_BAR_ALLOWANCE = 16;

    private final ListView<List<Album>> list = new ListView<>();
    private final ThumbnailLoader loader;
    private final DoubleProperty thumbnailSize = new SimpleDoubleProperty(TimelineView.DEFAULT_THUMBNAIL_SIZE);
    private List<Album> albums = List.of();
    private GridLayout layout = new GridLayout(1, TimelineView.DEFAULT_THUMBNAIL_SIZE);
    private Consumer<Album> onOpen = _ -> { };

    public AlbumGrid(ThumbnailLoader loader) {
        this.loader = loader;
        getStyleClass().add("album-grid");
        list.getStyleClass().add("timeline-list");
        list.setCellFactory(_ -> new RowCell());
        list.setFocusTraversable(false);
        getChildren().add(list);
        list.widthProperty().addListener((_, _, _) -> relayout());
        thumbnailSize.addListener((_, _, _) -> relayout());
    }

    /** Replaces the albums shown, staying at the same scroll position if the rows are the same. */
    public void setAlbums(List<Album> newAlbums) {
        albums = List.copyOf(newAlbums);
        list.getItems().setAll(rows());
    }

    /** Called with the album clicked. */
    public void setOnOpen(Consumer<Album> onOpen) {
        this.onOpen = onOpen;
    }

    public DoubleProperty thumbnailSizeProperty() {
        return thumbnailSize;
    }

    public void setPlaceholder(Node placeholder) {
        list.setPlaceholder(placeholder);
    }

    /** For tests and tools. */
    ListView<List<Album>> list() {
        return list;
    }

    private void relayout() {
        double width = list.getWidth() - list.snappedLeftInset() - list.snappedRightInset() - SCROLL_BAR_ALLOWANCE;
        GridLayout next = GridLayout.fit(width, thumbnailSize.get(), TimelineView.GAP);
        if (!next.equals(layout)) {
            layout = next;
            list.getItems().setAll(rows());
        }
    }

    private List<List<Album>> rows() {
        List<List<Album>> rows = new ArrayList<>();
        for (int i = 0; i < albums.size(); i += layout.columns()) {
            rows.add(albums.subList(i, Math.min(albums.size(), i + layout.columns())));
        }
        return rows;
    }

    /** One row of cards; cards are kept and reused. */
    private final class RowCell extends ListCell<List<Album>> {
        private final HBox cards = new HBox(TimelineView.GAP);
        private final List<Card> pool = new ArrayList<>();

        RowCell() {
            getStyleClass().add("timeline-cell");
            setText(null);
        }

        @Override
        protected boolean isItemChanged(List<Album> oldItem, List<Album> newItem) {
            return oldItem != newItem; // rebuilt as new lists on every change, like the Timeline's rows
        }

        @Override
        protected void updateItem(List<Album> row, boolean empty) {
            super.updateItem(row, empty);
            int count = empty || row == null ? 0 : row.size();
            while (pool.size() < count) {
                pool.add(new Card());
            }
            for (int i = 0; i < pool.size(); i++) {
                if (i < count) {
                    pool.get(i).show(row.get(i), layout.cellSize());
                } else {
                    pool.get(i).clear();
                }
            }
            cards.getChildren().setAll(pool.subList(0, count));
            setGraphic(count == 0 ? null : cards);
        }
    }

    /** Cover tile, then the folder's name and its count. A click opens the album. */
    private final class Card extends VBox {
        private final ThumbnailTile cover = new ThumbnailTile(loader);
        private final Label name = new Label();
        private final Label count = new Label();
        private final Tooltip path = new Tooltip();
        private Album album;

        Card() {
            getStyleClass().add("album-card");
            name.getStyleClass().add("album-name");
            count.getStyleClass().add("album-count");
            getChildren().addAll(cover, name, count);
            Tooltip.install(this, path);
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress() && album != null) {
                    onOpen.accept(album);
                }
            });
        }

        void show(Album newAlbum, double size) {
            album = newAlbum;
            if (newAlbum.cover() == null) {
                cover.showText("No photo", size);
            } else {
                cover.show(newAlbum.cover(), size);
            }
            name.setText(newAlbum.name());
            name.setMaxWidth(size);
            count.setText("%,d".formatted(newAlbum.count()));
            path.setText(newAlbum.folder());
        }

        void clear() {
            album = null;
            cover.clear();
        }
    }
}
