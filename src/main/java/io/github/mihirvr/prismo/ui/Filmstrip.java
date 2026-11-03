package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javafx.geometry.Pos;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/**
 * The filmstrip (1.1.0): a row of small thumbnails under the photo, the current one in the middle and outlined, the
 * edges fading out. A click opens that item, the mouse wheel steps one item at a time. Shared by the main viewer
 * and the standalone viewer; only the tiles that fit are made, so a list of 100,000 costs the same as one of 20.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class Filmstrip extends StackPane {

    /** The thumbnails' size; the strip is {@link #HEIGHT} tall. */
    static final double TILE_SIZE = 64;
    private static final double GAP = 6;
    /** The strip's height: the tiles and a little room above and below. */
    public static final double HEIGHT = TILE_SIZE + 16;
    /** About one notch of a mouse wheel (JavaFX reports about 40 on Windows). */
    private static final double WHEEL_NOTCH = 40;

    private final ThumbnailLoader loader;
    private final Pane track = new Pane();
    private final List<ThumbnailTile> tiles = new ArrayList<>();
    private List<TimelineItem> items = List.of();
    private int index = -1;
    private IntConsumer onPick = _ -> { };
    private double wheel;

    public Filmstrip(ThumbnailLoader loader) {
        this.loader = loader;
        getStyleClass().add("filmstrip");
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        setMinWidth(0);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        Region fadeLeft = new Region();
        fadeLeft.getStyleClass().addAll("filmstrip-fade", "filmstrip-fade-left");
        Region fadeRight = new Region();
        fadeRight.getStyleClass().addAll("filmstrip-fade", "filmstrip-fade-right");
        for (Region fade : List.of(fadeLeft, fadeRight)) {
            fade.setMouseTransparent(true);
            fade.setMaxWidth(USE_PREF_SIZE);
            fade.setPrefWidth(TILE_SIZE * 1.5);
        }
        StackPane.setAlignment(fadeLeft, Pos.CENTER_LEFT);
        StackPane.setAlignment(fadeRight, Pos.CENTER_RIGHT);
        track.setManaged(true);
        getChildren().addAll(track, fadeLeft, fadeRight);
        widthProperty().addListener((_, _, _) -> place());
        addEventHandler(ScrollEvent.SCROLL, event -> {
            double delta = event.getDeltaX() != 0 && Math.abs(event.getDeltaX()) > Math.abs(event.getDeltaY())
                    ? event.getDeltaX() : event.getDeltaY();
            wheel += delta;
            int steps = (int) (wheel / WHEEL_NOTCH);
            if (steps != 0) {
                wheel -= steps * WHEEL_NOTCH;
                pick(index - steps); // wheel down (negative) = next item
            }
            event.consume(); // not a zoom of the photo above
        });
    }

    /** Called with the index of the item to show (a click on a tile, or the wheel). */
    public void setOnPick(IntConsumer onPick) {
        this.onPick = onPick;
    }

    /** Shows the strip for {@code items} with {@code index} in the middle. Cheap when only the index moved. */
    public void show(List<TimelineItem> newItems, int newIndex) {
        items = newItems;
        index = newIndex;
        place();
    }

    /** The current item's index in the list shown. */
    public int index() {
        return index;
    }

    private void pick(int wanted) {
        int clamped = Math.clamp(wanted, 0, Math.max(0, items.size() - 1));
        if (clamped != index && !items.isEmpty()) {
            onPick.accept(clamped);
        }
    }

    /** Lays the tiles out around the middle: as many as fit, plus one each side so the fade has something to fade. */
    private void place() {
        double width = getWidth();
        if (items.isEmpty() || index < 0 || width <= 0) {
            for (ThumbnailTile tile : tiles) {
                tile.clear();
                tile.setVisible(false);
            }
            return;
        }
        double step = TILE_SIZE + GAP;
        int side = (int) Math.ceil(width / 2 / step) + 1;
        int first = Math.max(0, index - side);
        int last = Math.min(items.size() - 1, index + side);
        int needed = last - first + 1;
        while (tiles.size() < needed) {
            ThumbnailTile tile = new ThumbnailTile(loader);
            tile.getStyleClass().add("filmstrip-tile");
            tile.setTooltipShown(false);
            tiles.add(tile);
            track.getChildren().add(tile);
        }
        double middle = width / 2 - TILE_SIZE / 2;
        double top = (HEIGHT - TILE_SIZE) / 2;
        for (int t = 0; t < tiles.size(); t++) {
            ThumbnailTile tile = tiles.get(t);
            int at = first + t;
            if (t >= needed) {
                tile.clear();
                tile.setVisible(false);
                continue;
            }
            TimelineItem item = items.get(at);
            tile.setVisible(true);
            tile.show(item.ref(), TILE_SIZE);
            tile.setVideoLength(0);
            tile.setFocusRing(at == index);
            tile.relocate(middle + (at - index) * step, top);
            tile.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    pick(at);
                    event.consume();
                }
            });
        }
    }

    /** For tests and tools: the tiles on screen. */
    List<ThumbnailTile> tiles() {
        return tiles;
    }
}
