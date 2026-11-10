package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mihirvr.prismo.ui.MotionRules.Inputs;
import io.github.mihirvr.prismo.ui.MotionRules.Reason;
import org.junit.jupiter.api.Test;

class MotionRulesTest {

    private static final long LONG_AGO = Long.MAX_VALUE;

    /** Idle, on mains power, a quick computer, the window in front: may move. */
    private static Inputs idle(boolean movingNow) {
        return new Inputs(true, true, false, true, false, false, LONG_AGO, false, 8, 0.10, 10, movingNow);
    }

    @Test
    void anIdleQuickComputerMoves() {
        assertEquals(Reason.MOVING, MotionRules.decide(idle(false)));
        assertEquals(Reason.MOVING, MotionRules.decide(idle(true)));
    }

    @Test
    void theSwitchAndTheColoursComeFirst() {
        Inputs off = new Inputs(false, true, false, true, false, false, LONG_AGO, false, 8, 0.1, 10, false);
        assertEquals(Reason.OFF, MotionRules.decide(off));
        Inputs plain = new Inputs(true, false, false, true, false, false, LONG_AGO, false, 8, 0.1, 10, false);
        assertEquals(Reason.NO_COLOURS, MotionRules.decide(plain), "colours off or AMOLED: nothing to move");
    }

    @Test
    void aSmallOrSlowComputerStaysStill() {
        Inputs fewCores = new Inputs(true, true, false, true, false, false, LONG_AGO, false, 2, 0.1, 10, false);
        assertEquals(Reason.FEW_CORES, MotionRules.decide(fewCores));
        Inputs slow = new Inputs(true, true, false, true, false, false, LONG_AGO, false, 8, 0.1,
                MotionRules.SLOW_FRAME_MS + 5, true);
        assertEquals(Reason.TOO_SLOW, MotionRules.decide(slow));
        Inputs justQuickEnough = new Inputs(true, true, false, true, false, false, LONG_AGO, false, 4, 0.1,
                MotionRules.SLOW_FRAME_MS, true);
        assertEquals(Reason.MOVING, MotionRules.decide(justQuickEnough));
    }

    @Test
    void stillWhileTheWindowIsAwayOrCovered() {
        Inputs minimised = new Inputs(true, true, true, false, false, false, LONG_AGO, false, 8, 0.1, 10, true);
        assertEquals(Reason.HIDDEN, MotionRules.decide(minimised));
        Inputs viewer = new Inputs(true, true, false, true, true, false, LONG_AGO, false, 8, 0.1, 10, true);
        assertEquals(Reason.VIEWER, MotionRules.decide(viewer));
        Inputs elsewhere = new Inputs(true, true, false, false, false, false, LONG_AGO, false, 8, 0.1, 10, true);
        assertEquals(Reason.UNFOCUSED, MotionRules.decide(elsewhere));
    }

    @Test
    void stillWhileWorkRunsThenAFewQuietSeconds() {
        Inputs busy = new Inputs(true, true, false, true, false, true, 0, false, 8, 0.1, 10, false);
        assertEquals(Reason.BUSY, MotionRules.decide(busy));
        Inputs justDone = new Inputs(true, true, false, true, false, false, MotionRules.QUIET_NANOS - 1, false, 8,
                0.1, 10, false);
        assertEquals(Reason.SETTLING, MotionRules.decide(justDone));
        Inputs quietLongEnough = new Inputs(true, true, false, true, false, false, MotionRules.QUIET_NANOS, false,
                8, 0.1, 10, false);
        assertEquals(Reason.MOVING, MotionRules.decide(quietLongEnough));
    }

    @Test
    void batteryKeepsItStill() {
        Inputs battery = new Inputs(true, true, false, true, false, false, LONG_AGO, true, 8, 0.1, 10, true);
        assertEquals(Reason.BATTERY, MotionRules.decide(battery));
    }

    @Test
    void startingNeedsAQuieterCpuThanStaying() {
        double between = (MotionRules.START_CPU + MotionRules.STOP_CPU) / 2;
        Inputs startBetween = new Inputs(true, true, false, true, false, false, LONG_AGO, false, 8, between, 10,
                false);
        assertEquals(Reason.CPU_BUSY, MotionRules.decide(startBetween), "too busy to start");
        Inputs keepBetween = new Inputs(true, true, false, true, false, false, LONG_AGO, false, 8, between, 10, true);
        assertEquals(Reason.MOVING, MotionRules.decide(keepBetween), "but not busy enough to stop: no flicker");
        Inputs veryBusy = new Inputs(true, true, false, true, false, false, LONG_AGO, false, 8,
                MotionRules.STOP_CPU + 0.05, 10, true);
        assertEquals(Reason.CPU_BUSY, MotionRules.decide(veryBusy));
        Inputs unknown = new Inputs(true, true, false, true, false, false, LONG_AGO, false, 8, -1, 10, false);
        assertEquals(Reason.MOVING, MotionRules.decide(unknown), "an unknown CPU use doesn't block it");
    }

    @Test
    void slowPicturesOrALaggingWindowCountAsSlowingPrismoDown() {
        assertEquals(false, MotionRules.struggling(10, 2), "a quick computer");
        assertEquals(false, MotionRules.struggling(MotionRules.SLOW_FRAME_MS, MotionRules.LAG_MS), "at the limits");
        assertEquals(true, MotionRules.struggling(MotionRules.SLOW_FRAME_MS + 1, 2), "pictures too slow to draw");
        assertEquals(true, MotionRules.struggling(10, MotionRules.LAG_MS + 1), "the window lags");
    }

    @Test
    void everyStillReasonSaysSomethingInSettings() {
        for (Reason reason : Reason.values()) {
            if (reason != Reason.OFF && reason != Reason.NO_COLOURS) {
                assertEquals(false, reason.text.isBlank(), reason.name());
            }
        }
    }
}
