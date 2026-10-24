package io.github.mihirvr.prismo.ui;

import java.util.function.Supplier;
import javafx.animation.AnimationTimer;
import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Middle-click auto-scroll, as in Windows browsers and Explorer (1.0.1): click the wheel, then move the mouse up or
 * down and the grid scrolls on its own, faster the further the pointer is from where it was clicked. Any click or Esc
 * stops it. Holding the wheel down and dragging works too; letting go then stops it. JavaFX has no such thing built in.
 * UI thread only.
 */
final class AutoScroll {

    /** Pointer distance (px) from the click that doesn't scroll yet: a hand never holds a mouse perfectly still. */
    static final double DEAD_ZONE = 12;
    /** Pixels scrolled per frame for each pixel beyond the dead zone (60 frames a second). */
    private static final double SPEED = 0.18;
    /** The fastest it scrolls, in pixels per frame. */
    private static final double MAX_SPEED = 120;

    private final Node target;
    private final Supplier<VirtualFlow<?>> flow;
    private boolean active;
    private boolean draggedWhileHeld;
    private double anchorY;
    private double pointerY;
    private Scene scene;
    private Cursor cursorBefore;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            VirtualFlow<?> current = flow.get();
            double step = pixelsPerFrame(pointerY - anchorY);
            if (current != null && step != 0) {
                current.scrollPixels(step);
            }
        }
    };
    private final EventHandler<MouseEvent> moved = event -> {
        pointerY = event.getSceneY();
        if (event.isMiddleButtonDown() && Math.abs(pointerY - anchorY) > DEAD_ZONE) {
            draggedWhileHeld = true;
        }
    };
    /** Any press stops it (consumed, so that click does nothing else); a press of the wheel itself too. */
    private final EventHandler<MouseEvent> pressed = event -> {
        stop();
        event.consume();
    };
    private final EventHandler<MouseEvent> released = event -> {
        if (event.getButton() == MouseButton.MIDDLE && draggedWhileHeld) {
            stop(); // held and dragged: letting go ends it
        }
    };
    private final EventHandler<KeyEvent> keys = event -> {
        if (event.getCode() == KeyCode.ESCAPE) {
            stop();
            event.consume();
        }
    };

    AutoScroll(Node target, Supplier<VirtualFlow<?>> flow) {
        this.target = target;
        this.flow = flow;
        target.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (!active && event.getButton() == MouseButton.MIDDLE) {
                start(event.getSceneY());
                event.consume();
            }
        });
    }

    /**
     * How far to scroll in one frame when the pointer is {@code offset} pixels below (positive) or above (negative)
     * where the wheel was clicked: nothing inside the dead zone, then faster the further it is, up to a limit.
     */
    static double pixelsPerFrame(double offset) {
        double beyond = Math.abs(offset) - DEAD_ZONE;
        if (beyond <= 0) {
            return 0;
        }
        return Math.copySign(Math.min(MAX_SPEED, beyond * SPEED), offset);
    }

    boolean isActive() {
        return active;
    }

    private void start(double sceneY) {
        scene = target.getScene();
        if (scene == null) {
            return;
        }
        active = true;
        draggedWhileHeld = false;
        anchorY = sceneY;
        pointerY = sceneY;
        cursorBefore = scene.getCursor();
        scene.setCursor(Cursor.V_RESIZE);
        // On the scene, so it keeps working when the pointer leaves the grid; filters, so they run first.
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, moved);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, moved);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, pressed);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, released);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, keys);
        timer.start();
    }

    /** Ends auto-scroll if it is on (also when the window loses focus or the grid is hidden). */
    void stop() {
        if (!active) {
            return;
        }
        active = false;
        timer.stop();
        scene.removeEventFilter(MouseEvent.MOUSE_MOVED, moved);
        scene.removeEventFilter(MouseEvent.MOUSE_DRAGGED, moved);
        scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, pressed);
        scene.removeEventFilter(MouseEvent.MOUSE_RELEASED, released);
        scene.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
        scene.setCursor(cursorBefore);
        scene = null;
    }
}
