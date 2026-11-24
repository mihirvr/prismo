package io.github.mihirvr.prismo.viewer;

/**
 * Turns scroll amounts into whole steps (1.4.0: the mouse wheel on the volume bar). Pure: no JavaFX.
 *
 * <p>One wheel click is one step. A touchpad sends many small scrolls instead; they are added up, and every time
 * they reach one click's worth, that is one step. Turning the other way drops what was left over, so a small
 * wobble back never eats the next step.
 */
final class WheelSteps {

    /** JavaFX's scroll amount for one wheel click on Windows (3 lines × 13.3 px); Linux sends the same order. */
    static final double CLICK = 40;

    private double leftOver;

    /**
     * Adds a scroll of {@code deltaY} (JavaFX: up / away from you is positive) and returns how many whole steps it
     * makes: positive = up, negative = down, 0 = not enough yet.
     */
    int add(double deltaY) {
        if (deltaY == 0 || Double.isNaN(deltaY)) {
            return 0;
        }
        if (Math.signum(deltaY) != Math.signum(leftOver)) {
            leftOver = 0;
        }
        leftOver += deltaY;
        int steps = (int) (leftOver / CLICK); // toward zero, so a part step stays in leftOver
        leftOver -= steps * CLICK;
        return steps;
    }
}
