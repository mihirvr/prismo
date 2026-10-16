package io.github.mihirvr.prismo.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewTransformTest {

    private static final double EPS = 1e-9;

    /** 4000 × 3000 photo in a 1000 × 800 view on a 100% screen: fit = 0.25. */
    private static ViewTransform landscape() {
        ViewTransform t = new ViewTransform();
        t.setView(1000, 800, 1);
        t.setImage(4000, 3000);
        return t;
    }

    /** Which image pixel (from the image centre) is under view point (x, y). Only for 0° rotation. */
    private static double imageXAt(ViewTransform t, double x) {
        return (x - t.centreX()) / t.scale();
    }

    private static double imageYAt(ViewTransform t, double y) {
        return (y - t.centreY()) / t.scale();
    }

    @Test
    void fitShowsTheWholeImageCentred() {
        ViewTransform t = landscape();

        assertEquals(0.25, t.scale(), EPS);
        assertEquals(1000, t.displayWidth(), EPS);
        assertEquals(750, t.displayHeight(), EPS);
        assertEquals(500, t.centreX(), EPS);
        assertEquals(400, t.centreY(), EPS);
        assertTrue(t.isFitted());
        assertFalse(t.canPan());
    }

    @Test
    void smallImagesAreNotBlownUpAndHundredPercentFollowsTheScreenScale() {
        ViewTransform t = new ViewTransform();
        t.setView(1000, 800, 1);
        t.setImage(200, 100);
        assertEquals(1, t.scale(), EPS);

        // 125% screen: one image pixel per physical pixel = 0.8 layout units.
        t.setView(1000, 800, 1.25);
        assertEquals(0.8, t.scale(), EPS);
        assertEquals(100, t.zoomPercent(), EPS);
    }

    @Test
    void wheelZoomKeepsThePointUnderTheCursorStill() {
        ViewTransform t = landscape();
        double beforeX = imageXAt(t, 700);
        double beforeY = imageYAt(t, 300);

        t.zoomAt(2, 700, 300);

        assertEquals(0.5, t.scale(), EPS);
        assertEquals(beforeX, imageXAt(t, 700), EPS);
        assertEquals(beforeY, imageYAt(t, 300), EPS);
        assertFalse(t.isFitted());
        assertTrue(t.canPan());
    }

    @Test
    void zoomStopsAtFitAndAtEightTimesActualSize() {
        ViewTransform t = landscape();

        t.zoomAt(0.1, 500, 400);
        assertEquals(0.25, t.scale(), EPS);
        assertTrue(t.isFitted());

        t.zoomAt(1000, 500, 400);
        assertEquals(ViewTransform.MAX_ZOOM, t.scale(), EPS);

        t.zoomAt(0, 500, 400);   // ignored
        t.zoomAt(-2, 500, 400);  // ignored
        t.zoomAt(Double.NaN, 500, 400);
        assertEquals(ViewTransform.MAX_ZOOM, t.scale(), EPS);
    }

    @Test
    void zoomingBackOutToFitRecentresAndCountsAsFitted() {
        ViewTransform t = landscape();
        t.zoomAt(3, 900, 100);

        t.zoomAt(1.0 / 3, 900, 100);

        assertTrue(t.isFitted());
        assertEquals(500, t.centreX(), EPS);
        assertEquals(400, t.centreY(), EPS);
    }

    @Test
    void panningStopsAtTheImageEdges() {
        ViewTransform t = landscape();
        t.panBy(300, 300);
        assertEquals(500, t.centreX(), EPS); // fitted: nothing to drag
        assertEquals(400, t.centreY(), EPS);

        t.zoomAtCentre(2); // 2000 × 1500 on screen
        t.panBy(10_000, -10_000);

        // Left edge at 0, bottom edge at 800: centre = 0 + 1000, 800 - 750.
        assertEquals(1000, t.centreX(), EPS);
        assertEquals(50, t.centreY(), EPS);
    }

    @Test
    void aSideThatFitsStaysCentredWhileTheOtherPans() {
        ViewTransform t = new ViewTransform();
        t.setView(1000, 800, 1);
        t.setImage(4000, 500); // panorama: fit = 0.25 → 1000 × 125
        t.zoomAtCentre(2);     // 2000 × 250: wider than the view, still shorter

        t.panBy(-200, 200);

        assertEquals(300, t.centreX(), EPS);
        assertEquals(400, t.centreY(), EPS);
    }

    @Test
    void doubleClickTogglesFitAndHundredPercentAroundTheClick() {
        ViewTransform t = landscape();
        double beforeX = imageXAt(t, 600);

        t.toggleFitActual(600, 400);
        assertEquals(1, t.scale(), EPS);
        assertEquals(beforeX, imageXAt(t, 600), EPS);

        t.toggleFitActual(600, 400);
        assertTrue(t.isFitted());
        assertEquals(0.25, t.scale(), EPS);
    }

    @Test
    void rotatingTurnsAQuarterAndFitsTheTurnedImage() {
        ViewTransform t = landscape();
        t.zoomAtCentre(3);

        t.rotateClockwise();

        assertEquals(90, t.rotationDegrees(), EPS);
        // Turned: 3000 wide × 4000 tall → fit = min(1000/3000, 800/4000) = 0.2.
        assertEquals(0.2, t.scale(), EPS);
        assertTrue(t.isFitted());
        // The node keeps its unturned size; it's turned about its centre.
        assertEquals(800, t.displayWidth(), EPS);
        assertEquals(600, t.displayHeight(), EPS);

        t.rotateClockwise();
        t.rotateClockwise();
        t.rotateClockwise();
        assertEquals(0, t.rotationDegrees(), EPS);
    }

    @Test
    void panLimitsUseTheTurnedSize() {
        ViewTransform t = landscape();
        t.rotateClockwise();     // 3000 × 4000 turned, fit 0.2
        t.zoomAtCentre(2);       // scale 0.4 → turned on screen 1200 × 1600

        t.panBy(10_000, 10_000);

        assertEquals(500 + 100, t.centreX(), EPS); // (1200 - 1000) / 2
        assertEquals(400 + 400, t.centreY(), EPS); // (1600 - 800) / 2
    }

    @Test
    void aNewImageResetsRotationAndZoom() {
        ViewTransform t = landscape();
        t.rotateClockwise();
        t.zoomAtCentre(4);

        t.setImage(3000, 2000);

        assertEquals(0, t.rotationDegrees(), EPS);
        assertTrue(t.isFitted());
        assertEquals(1.0 / 3, t.scale(), EPS);
    }

    @Test
    void resizingRefitsWhenFittedAndKeepsTheZoomOtherwise() {
        ViewTransform t = landscape();
        t.setView(2000, 1600, 1);
        assertEquals(0.5, t.scale(), EPS);

        t.zoomAt(2, 1000, 800); // scale 1
        t.panBy(-10_000, 0);
        t.setView(3000, 1600, 1);

        assertEquals(1, t.scale(), EPS);
        // Still in bounds for the wider view: image 4000 wide, view 3000 → centre at most 500 left of 1500.
        assertEquals(1000, t.centreX(), EPS);
    }
}
