package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VideoGeometryTest {

    @Test
    void bigVideosShrinkToTheScreenSmallOnesStay() {
        assertArrayEquals(new int[] {1920, 1080}, VideoGeometry.decodeSize(3840, 2160, 0, 1920, 1080));
        assertArrayEquals(new int[] {1280, 720}, VideoGeometry.decodeSize(1280, 720, 0, 1920, 1080));
        assertArrayEquals(new int[] {1440, 1080}, VideoGeometry.decodeSize(1920, 1440, 0, 1920, 1080), "keeps 4:3");
    }

    @Test
    void aSidewaysVideoMustFitWithItsWidthAlongTheScreensHeight() {
        // A phone video stored 1920×1080, shown turned (1080 wide, 1920 tall), on a 1920×1080 screen.
        assertArrayEquals(new int[] {1080, 606}, VideoGeometry.decodeSize(1920, 1080, 90, 1920, 1080));
        assertArrayEquals(new int[] {1920, 1080}, VideoGeometry.decodeSize(1920, 1080, 180, 1920, 1080));
    }

    @Test
    void sidesAreEvenRoundedDownAndAtLeastTwo() {
        assertArrayEquals(new int[] {1000, 500}, VideoGeometry.decodeSize(1001, 501, 0, 4000, 4000));
        assertArrayEquals(new int[] {2, 2}, VideoGeometry.decodeSize(1, 1, 0, 4000, 4000));
    }

    @Test
    void ffmpegsCounterClockwiseAngleBecomesAClockwiseQuarterTurn() {
        assertEquals(0, VideoGeometry.clockwiseQuarterTurn(0));
        assertEquals(90, VideoGeometry.clockwiseQuarterTurn(-90));
        assertEquals(270, VideoGeometry.clockwiseQuarterTurn(90));
        assertEquals(180, VideoGeometry.clockwiseQuarterTurn(180));
        assertEquals(180, VideoGeometry.clockwiseQuarterTurn(-180));
        assertEquals(90, VideoGeometry.clockwiseQuarterTurn(-89.6), "rounded to the nearest quarter");
        assertEquals(0, VideoGeometry.clockwiseQuarterTurn(-360));
    }
}
