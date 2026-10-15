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
 * UI genuinely needs three times the room. The minimum is the content's: the root stays the window, and scrolls.
 */
class MinSizeAndZoomTest {

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    /** A child that takes all the room its parent's flow has: what the canvas is, measured. */
    private static Node filler(Gui gui) {
        Node fill = gui.box().size(Length.FILL, Length.FILL);
        gui.root().children(fill);
        return fill;
    }

    @Test
    void theContentIsLaidOutAtTheMinimumWhenTheWindowIsSmaller() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().scroll(false, false);   // the canvas alone, without scrollbars narrowing the window
            Node content = filler(gui);
            gui.minSize(Length.rem(50), Length.rem(30));   // 800 x 480 at 1x
            gui.frame(400f, 200f, MinSizeAndZoomTest::noText);

            assertEquals(800f, content.layout().rect().w(), 0.5f,
                    "the window is 400 wide, but the UI is laid out on the 800 it needs");
            assertEquals(480f, content.layout().rect().h(), 0.5f);
        }
    }

    /**
     * The root is the window whatever the minimum, so what is anchored to its frame is anchored to the window. A
     * root the size of the canvas put a title bar's controls past the window's edge once a zoom raised the floor
     * above it, with nothing on screen to scroll them back.
     */
    @Test
    void theRootStaysTheWindowAndItsContentScrolls() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node content = filler(gui);
            gui.minSize(Length.rem(50), Length.rem(30));
            gui.frame(400f, 200f, MinSizeAndZoomTest::noText);

            assertEquals(400f, gui.root().layout().rect().w(), 0.5f, "the root is the window");
            assertEquals(200f, gui.root().layout().rect().h(), 0.5f);
            assertEquals(800f, content.layout().rect().w(), 0.5f, "its content is the canvas, and overflows it");
        }
    }

    /** A docked node is the root's frame, not its flow: it spans the window, and the canvas starts below it. */
    @Test
    void aDockedNodeSpansTheWindowAndNotTheCanvas() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node bar = gui.box().size(Length.FILL, Length.dp(32)).floatAt(Length.ZERO, Length.ZERO).dock(true);
            Node content = gui.box().size(Length.FILL, Length.FILL);
            gui.root().scroll(false, false).children(bar, content);
            gui.minSize(Length.rem(50), Length.rem(30));
            gui.zoom(2f);
            gui.frame(900f, 560f, MinSizeAndZoomTest::noText);

            assertEquals(900f, bar.layout().rect().w(), 0.5f, "the bar is as wide as the window");
            assertEquals(1600f, content.layout().rect().w(), 0.5f, "while the content is as wide as the canvas");
            assertEquals(32f, content.layout().rect().y(), 0.5f, "and starts below the bar");
        }
    }

    @Test
    void aWindowLargerThanTheMinimumIsUnaffected() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node content = filler(gui);
            gui.minSize(Length.rem(50), Length.rem(30));
            gui.frame(1200f, 900f, MinSizeAndZoomTest::noText);

            assertEquals(1200f, gui.root().layout().rect().w(), 0.5f, "the minimum is a floor, not a size");
            assertEquals(900f, gui.root().layout().rect().h(), 0.5f);
            assertEquals(1200f, content.layout().rect().w(), 0.5f);
            assertEquals(900f, content.layout().rect().h(), 0.5f);
        }
    }

    /** The reason the unit is em: the floor rises with zoom, because a zoomed UI needs proportionally more room. */
    @Test
    void theMinimumScalesWithZoom() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().scroll(false, false);
            Node content = filler(gui);
            gui.minSize(Length.rem(50), Length.rem(30));
            gui.zoom(2f);
            gui.frame(900f, 560f, MinSizeAndZoomTest::noText);

            assertEquals(1600f, content.layout().rect().w(), 0.5f,
                    "at 2x the same 50em minimum is 1600px, so a 900px window is below it");
            assertEquals(960f, content.layout().rect().h(), 0.5f);
        }
    }

    /** Default: no minimum, so nothing changes for a UI that does not ask for one. */
    @Test
    void thereIsNoMinimumByDefault() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node content = filler(gui);
            gui.frame(300f, 150f, MinSizeAndZoomTest::noText);
            assertEquals(300f, gui.root().layout().rect().w(), 0.5f);
            assertEquals(150f, gui.root().layout().rect().h(), 0.5f);
            assertEquals(300f, content.layout().rect().w(), 0.5f);
            assertEquals(150f, content.layout().rect().h(), 0.5f);
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
            gui.zoom(3f);
            assertEquals(3f, gui.zoom().value(), 0.001f, "allowed by the default range");

            gui.zoomRange(0.5f, 1.5f, 1.25f);
            assertEquals(1.5f, gui.zoom().value(), 0.001f, "pulled back into the new range at once");
        }
    }
}
