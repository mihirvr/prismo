package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WheelStepsTest {

    @Test
    void oneWheelClickIsOneStep() {
        WheelSteps wheel = new WheelSteps();
        assertEquals(1, wheel.add(WheelSteps.CLICK));
        assertEquals(-1, wheel.add(-WheelSteps.CLICK));
        assertEquals(3, wheel.add(3 * WheelSteps.CLICK));
    }

    @Test
    void touchpadScrollsAddUp() {
        WheelSteps wheel = new WheelSteps();
        assertEquals(0, wheel.add(15));
        assertEquals(0, wheel.add(15));
        assertEquals(1, wheel.add(15)); // 45: one step, 5 left
        assertEquals(0, wheel.add(30)); // 35
        assertEquals(1, wheel.add(5));  // 40
    }

    @Test
    void turningBackDropsTheLeftOver() {
        WheelSteps wheel = new WheelSteps();
        assertEquals(0, wheel.add(35));
        assertEquals(0, wheel.add(-10)); // not 25 left: starts again downward
        assertEquals(-1, wheel.add(-30));
    }

    @Test
    void nothingForNothing() {
        WheelSteps wheel = new WheelSteps();
        assertEquals(0, wheel.add(0));
        assertEquals(0, wheel.add(Double.NaN));
    }
}
