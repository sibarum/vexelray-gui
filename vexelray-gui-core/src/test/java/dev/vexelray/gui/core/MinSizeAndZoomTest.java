package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The minimum layout canvas, and the configurable zoom range.
 *
 * <p>A minimum is what stops a UI being asked to represent itself in a size it cannot: below it a flexible region
 * absorbs the entire deficit and disappears, which is a worse outcome than showing part of a correct layout.
 * Expressed in {@code em} it scales with zoom, so one setting covers both a small window and a high factor — a 3x
 * UI genuinely needs three times the room.
 */
class MinSizeAndZoomTest {

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    @Test
    void theLayoutRunsAtTheMinimumWhenTheWindowIsSmaller() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.minSize(Length.em(50), Length.em(30));   // 800 x 480 at 1x
            gui.frame(400f, 200f, MinSizeAndZoomTest::noText);

            assertEquals(800f, gui.root().layout().rect().w(), 0.5f,
                    "the window is 400 wide, but the UI is laid out on the 800 it needs");
            assertEquals(480f, gui.root().layout().rect().h(), 0.5f);
        }
    }

    @Test
    void aWindowLargerThanTheMinimumIsUnaffected() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.minSize(Length.em(50), Length.em(30));
            gui.frame(1200f, 900f, MinSizeAndZoomTest::noText);

            assertEquals(1200f, gui.root().layout().rect().w(), 0.5f, "the minimum is a floor, not a size");
            assertEquals(900f, gui.root().layout().rect().h(), 0.5f);
        }
    }

    /** The reason the unit is em: the floor rises with zoom, because a zoomed UI needs proportionally more room. */
    @Test
    void theMinimumScalesWithZoom() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.minSize(Length.em(50), Length.em(30));
            gui.zoom(2f);
            gui.frame(900f, 560f, MinSizeAndZoomTest::noText);

            assertEquals(1600f, gui.root().layout().rect().w(), 0.5f,
                    "at 2x the same 50em minimum is 1600px, so a 900px window is below it");
            assertEquals(960f, gui.root().layout().rect().h(), 0.5f);
        }
    }

    /** Default: no minimum, so nothing changes for a UI that does not ask for one. */
    @Test
    void thereIsNoMinimumByDefault() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.frame(300f, 150f, MinSizeAndZoomTest::noText);
            assertEquals(300f, gui.root().layout().rect().w(), 0.5f);
            assertEquals(150f, gui.root().layout().rect().h(), 0.5f);
        }
    }

    // --- zoom range ---

    @Test
    void zoomStepsByTheConfiguredFactorAndClampsToTheRange() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.zoomRange(0.5f, 2f, 1.25f);

            gui.zoomIn();
            assertEquals(1.25f, gui.zoom().value(), 0.001f, "a step is a factor, not an increment");
            gui.zoomIn();
            assertEquals(1.5625f, gui.zoom().value(), 0.001f);

            for (int i = 0; i < 20; i++) {
                gui.zoomIn();
            }
            assertEquals(2f, gui.zoom().value(), 0.001f, "clamped to the configured maximum");

            for (int i = 0; i < 40; i++) {
                gui.zoomOut();
            }
            assertEquals(0.5f, gui.zoom().value(), 0.001f, "and to the minimum");

            gui.resetZoom();
            assertEquals(1f, gui.zoom().value(), 0.001f);
        }
    }

    /** Narrowing the range re-clamps immediately, so it cannot leave the UI outside its own limits. */
    @Test
    void narrowingTheRangeReclampsTheCurrentFactor() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.zoom(4f);
            assertEquals(4f, gui.zoom().value(), 0.001f, "allowed by the default range");

            gui.zoomRange(0.5f, 1.5f, 1.25f);
            assertEquals(1.5f, gui.zoom().value(), 0.001f, "pulled back into the new range at once");
        }
    }
}
