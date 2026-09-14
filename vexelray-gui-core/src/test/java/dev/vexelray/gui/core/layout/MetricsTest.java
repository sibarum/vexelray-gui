package dev.vexelray.gui.core.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Size and scale, with no {@code Gui}, no window and no bus — {@code new Metrics()} and nothing else.
 *
 * <p>That is the whole proof the extraction happened rather than the code merely moving file
 * (docs/plans/gui-decomposition.md §4). These three {@code State}s used to be built in the {@code Gui}
 * constructor, so reaching zoom clamping or the frame dirty-check meant standing up a composition root; the
 * clamp was therefore only ever observed through {@code MinSizeAndZoomTest} driving whole frames.
 */
class MetricsTest {

    @Test
    void zoomIsClampedToTheConfiguredRange() {
        Metrics m = new Metrics();

        m.zoom(99f);
        assertEquals(3f, m.zoom().value(), 0.0001f, "the default maximum");
        m.zoom(0.01f);
        assertEquals(0.5f, m.zoom().value(), 0.0001f, "and the default minimum");
    }

    /** A step is a factor, not an increment: zoom is perceived as a ratio. */
    @Test
    void steppingMultipliesRatherThanAdds() {
        Metrics m = new Metrics();

        m.zoomIn();
        assertEquals(1.25f, m.zoom().value(), 0.0001f);
        m.zoomIn();
        assertEquals(1.5625f, m.zoom().value(), 0.0001f, "1.25 twice over, not 1.5");
        m.resetZoom();
        assertEquals(1f, m.zoom().value(), 0.0001f);
    }

    /** Narrowing the range cannot leave the UI outside it, so the current factor is re-clamped at once. */
    @Test
    void narrowingTheRangePullsTheCurrentFactorIntoIt() {
        Metrics m = new Metrics();
        m.zoom(3f);

        m.zoomRange(1f, 1.5f, 1.1f);

        assertEquals(1.5f, m.zoom().value(), 0.0001f);
    }

    @Test
    void densityIsClampedBecauseBelowOneIsNotARealDisplay() {
        Metrics m = new Metrics();

        m.dpi(0.25f);
        assertEquals(1f, m.dpi().value(), 0.0001f);
        m.dpi(9f);
        assertEquals(4f, m.dpi().value(), 0.0001f);
    }

    // --- what a frame asks for -----------------------------------------------------------------------------

    @Test
    void theFirstFrameHasMovedInEveryWayThereIs() {
        Metrics m = new Metrics();

        Metrics.Frame f = m.measure(800f, 600f);

        assertTrue(f.moved(), "nothing has been laid out yet, so everything is new");
        assertTrue(f.viewportMoved());
        assertTrue(f.scaleMoved());
        assertTrue(f.clampMoved());
        assertEquals(800f, f.layoutW(), 0.0001f, "no minimum configured, so the canvas is the window");
        assertEquals(600f, f.layoutH(), 0.0001f);
    }

    /**
     * A second measure at the same size has moved only if a layout was reported in between. This is the whole
     * reason the remembered values are here rather than in the frame loop: they are answers about this
     * component, and a frame that decided not to lay out must not be recorded as one that did.
     */
    @Test
    void nothingHasMovedOnceALayoutHasBeenReported() {
        Metrics m = new Metrics();

        Metrics.Frame first = m.measure(800f, 600f);
        m.layoutRan(first);

        assertFalse(m.measure(800f, 600f).moved(), "same window, same scale, and a pass has run at it");
    }

    @Test
    void aFrameThatDidNotLayOutIsNotRecordedAsOneThatDid() {
        Metrics m = new Metrics();

        m.measure(800f, 600f);   // measured, but no tree to lay out, so no layoutRan

        assertTrue(m.measure(800f, 600f).moved(), "still owed a pass at this size");
    }

    @Test
    void resizingMovesTheViewportButNotTheScale() {
        Metrics m = new Metrics();
        m.layoutRan(m.measure(800f, 600f));

        Metrics.Frame f = m.measure(1024f, 600f);

        assertTrue(f.viewportMoved());
        assertFalse(f.scaleMoved(), "the window changed, the zoom did not");
    }

    @Test
    void zoomingMovesTheScaleButNotTheViewport() {
        Metrics m = new Metrics();
        m.layoutRan(m.measure(800f, 600f));

        m.zoomIn();
        Metrics.Frame f = m.measure(800f, 600f);

        assertTrue(f.scaleMoved());
        assertFalse(f.viewportMoved());
        assertEquals(1.25f, f.layoutCtx().zoom(), 0.0001f, "and the layout runs at the new factor");
    }

    /**
     * The clamped canvas is never smaller than the configured minimum, and {@code viewport()} keeps reporting
     * the real window — the two are different questions and a caller asking one must not get the other.
     */
    @Test
    void aWindowUnderTheMinimumIsLaidOutOnTheMinimum() {
        Metrics m = new Metrics();
        m.minSize(Length.dp(1000), Length.dp(700));

        Metrics.Frame f = m.measure(800f, 600f);

        assertEquals(1000f, f.layoutW(), 0.0001f);
        assertEquals(700f, f.layoutH(), 0.0001f);
        assertEquals(800, m.viewport().value().width(), "the window is still 800 wide, and says so");
        assertEquals(600, m.viewport().value().height());
    }

    /**
     * A minimum in a zoom-dependent unit can change the clamped canvas on its own, with the window untouched.
     * That is why {@code clampMoved} is asked separately rather than inferred from the other two.
     */
    @Test
    void theClampCanMoveWithTheWindowStandingStill() {
        Metrics m = new Metrics();
        m.minSize(Length.em(100), Length.ZERO);   // 100em = 1600px at zoom 1, well over the window
        m.layoutRan(m.measure(800f, 600f));

        m.zoomIn();
        Metrics.Frame f = m.measure(800f, 600f);

        assertFalse(f.viewportMoved());
        assertTrue(f.clampMoved(), "the minimum grew with the zoom, so the canvas did");
        assertEquals(2000f, f.layoutW(), 0.0001f, "100em at 16px and 1.25 zoom");
    }

    /**
     * The grip is re-resolved every frame, so a zoomed UI whose gutter grew has a grip that grew with it. It is
     * measured against the window rather than the clamped canvas, like every other length here that must not
     * define itself in terms of what it decides.
     */
    @Test
    void theResizeGripIsResolvedAtThisFrameZoom() {
        Metrics m = new Metrics();
        m.resizeBorder(Length.em(1));

        m.measure(800f, 600f);
        assertEquals(16, m.resizeBorderPx(), "1em at the root 16px");

        m.zoom(2f);
        m.measure(800f, 600f);
        assertEquals(32, m.resizeBorderPx(), "and it doubles with the zoom rather than staying put");
    }

    @Test
    void noGripIsAskedForByDefault() {
        Metrics m = new Metrics();

        m.measure(800f, 600f);

        assertEquals(0, m.resizeBorderPx(), "0 means the platform keeps its own metric");
    }
}
