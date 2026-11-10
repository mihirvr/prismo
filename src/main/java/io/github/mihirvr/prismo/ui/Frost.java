package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frosted menus (2026-11-10, Mihir: "instead of clear glass, frosted, for all the drop-downs and right-clicks"):
 * every menu, drop-down list and the Go to date calendar shows a blurred copy of what is behind it, under its tint.
 *
 * <p>JavaFX can't blur what is behind a window, and each menu is its own small window. So when one opens, the part
 * of the window under it is copied once (a snapshot), blurred once, and laid under the menu's card. Cost: one small
 * picture and one blur per opening, nothing while it stays open. The copy doesn't follow a moving colour background
 * while the menu is open (menus are open for a moment). Tooltips are left as they are (they open all the time).
 * If anything fails the menu simply shows its plain tint.
 */
public final class Frost {

    private static final Logger log = LoggerFactory.getLogger(Frost.class);
    /** How soft the frost is, in pixels. */
    static final double BLUR = 18;
    /** The menu cards' corner radius (prismo.css: .context-menu, .combo-box-popup > .list-view). */
    static final double CORNER = 10;
    private static final String FROST_ID = "prismo-frost";
    private static boolean installed;

    private Frost() {
    }

    /** Frosts every menu and drop-down list that opens from now on. Once, at the start. */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof PopupWindow popup && !(window instanceof Tooltip)) {
                        Platform.runLater(() -> frost(popup)); // after it is laid out at its place
                    }
                }
            }
        });
    }

    private static void frost(PopupWindow popup) {
        try {
            Scene scene = popup.getScene();
            if (!popup.isShowing() || scene == null || !(scene.getRoot() instanceof Parent root)) {
                return;
            }
            ObservableList<Node> layers = children(root);
            Region card = card(root);
            Window base = popup.getOwnerWindow();
            while (base instanceof PopupWindow inner) { // a submenu: the window under all the menus
                base = inner.getOwnerWindow();
            }
            if (layers == null || card == null || base == null || base.getScene() == null) {
                return;
            }
            layers.removeIf(node -> FROST_ID.equals(node.getId()));
            Bounds onScreen = card.localToScreen(card.getLayoutBounds());
            Node under = base.getScene().getRoot();
            Bounds there = under.screenToLocal(onScreen);
            if (onScreen == null || there == null || there.getWidth() < 1 || there.getHeight() < 1) {
                return;
            }
            // The copy reaches BLUR past the card on every side, so the blur has real pixels at the edges.
            SnapshotParameters copy = new SnapshotParameters();
            copy.setFill(Color.TRANSPARENT);
            copy.setViewport(new Rectangle2D(there.getMinX() - BLUR, there.getMinY() - BLUR,
                    there.getWidth() + 2 * BLUR, there.getHeight() + 2 * BLUR));
            WritableImage behind = under.snapshot(copy, null);
            ImageView soft = new ImageView(behind);
            soft.setEffect(new GaussianBlur(BLUR));
            SnapshotParameters cut = new SnapshotParameters();
            cut.setFill(Color.TRANSPARENT);
            cut.setViewport(new Rectangle2D(BLUR, BLUR, there.getWidth(), there.getHeight()));
            WritableImage frosted = soft.snapshot(cut, null);

            ImageView frost = new ImageView(frosted);
            frost.setId(FROST_ID);
            frost.setManaged(false);
            frost.setMouseTransparent(true);
            frost.setFitWidth(card.getWidth());
            frost.setFitHeight(card.getHeight());
            Bounds inRoot = root.sceneToLocal(card.localToScene(card.getLayoutBounds()));
            frost.relocate(inRoot.getMinX(), inRoot.getMinY());
            Rectangle round = new Rectangle(card.getWidth(), card.getHeight());
            round.setArcWidth(CORNER * 2);
            round.setArcHeight(CORNER * 2);
            frost.setClip(round);
            // The same look wherever it opens (Mihir: the right-click menu over white photos looked lighter than the
            // drop-down over the dark background, with the same tint): when what is behind is much brighter or
            // darker than the card's tint, a little more of the tint goes under the card.
            Rectangle evenOut = new Rectangle(card.getWidth(), card.getHeight());
            evenOut.setId(FROST_ID);
            evenOut.setManaged(false);
            evenOut.setMouseTransparent(true);
            evenOut.relocate(inRoot.getMinX(), inRoot.getMinY());
            evenOut.setArcWidth(CORNER * 2);
            evenOut.setArcHeight(CORNER * 2);
            Color tint = tint(card);
            // Right-click menus (not opened from a button) are more solid still (Mihir: "still too light").
            boolean rightClick = popup instanceof javafx.scene.control.ContextMenu menu
                    && !(menu.getOwnerNode() instanceof javafx.scene.control.ButtonBase);
            double extra = extraTint(brightness(frosted), brightness(tint == null ? Color.BLACK : tint))
                    + (rightClick ? RIGHT_CLICK_EXTRA : 0);
            evenOut.setFill(tint == null ? Color.TRANSPARENT
                    : Color.color(tint.getRed(), tint.getGreen(), tint.getBlue(), Math.min(extra, 0.75)));
            layers.addFirst(evenOut);
            layers.addFirst(frost); // under the card: its see-through tint lies over the frost
        } catch (RuntimeException e) {
            log.debug("Could not frost a menu", e);
        }
    }

    /**
     * How much more of the tint to lay over the frost (0 to {@link #MAX_EXTRA}): none while what is behind is
     * about as bright as the tint, more as they grow apart.
     */
    static double extraTint(double behind, double tint) {
        return Math.clamp((Math.abs(behind - tint) - 0.25) * 0.8, 0, MAX_EXTRA);
    }

    static final double MAX_EXTRA = 0.30;
    /** Extra tint for right-click menus on top of that: they open over photos and must read easily. */
    static final double RIGHT_CLICK_EXTRA = 0.40;

    /** The card's tint: its last background colour (the first is the thin rim). */
    private static Color tint(Region card) {
        var fills = card.getBackground().getFills();
        return fills.getLast().getFill() instanceof Color colour ? colour : null;
    }

    private static double brightness(Color colour) {
        return 0.2126 * colour.getRed() + 0.7152 * colour.getGreen() + 0.0722 * colour.getBlue();
    }

    /** The average brightness of a picture, 0 to 1 (every 4th pixel each way: enough for a blurred picture). */
    static double brightness(javafx.scene.image.Image picture) {
        var reader = picture.getPixelReader();
        double sum = 0;
        int count = 0;
        for (int y = 0; y < (int) picture.getHeight(); y += 4) {
            for (int x = 0; x < (int) picture.getWidth(); x += 4) {
                sum += brightness(reader.getColor(x, y));
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /** The popup's own layer list (its root is a Pane or a Group), or null. */
    private static ObservableList<Node> children(Parent root) {
        if (root instanceof Pane pane) {
            return pane.getChildren();
        }
        if (root instanceof Group group) {
            return group.getChildren();
        }
        return null;
    }

    /** The menu's card: the first menu or list region that paints a background. */
    private static Region card(Parent root) {
        for (String selector : new String[] {".context-menu", ".combo-box-popup > .list-view"}) {
            for (Node node : new ArrayList<>(root.lookupAll(selector))) {
                if (node instanceof Region region && region.getBackground() != null
                        && !region.getBackground().getFills().isEmpty() && region.getWidth() > 0) {
                    return region;
                }
            }
        }
        return null;
    }
}
