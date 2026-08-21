package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.os.HitRegions;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChromeRegions}: what the host tells the OS about a window whose chrome the GUI draws. The regions are a
 * derivation of the laid-out tree, so every case here is about the tree, not about a protocol.
 */
class ChromeRegionsTest {

    /** Fixed-size boxes only — no text metrics needed. */
    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** A title bar 400 wide and 32 tall with a 46-wide button at its right end, laid out and read back. */
    private static HitRegions oneBarWithButton(Gui gui) {
        Node button = gui.box().width(Length.dp(46)).height(Length.FILL)
                .windowRegion(WindowRegion.INTERACTIVE);
        Node spacer = gui.box().width(Length.dp(354)).height(Length.FILL);
        Node bar = gui.box().width(Length.FILL).height(Length.dp(32))
                .windowRegion(WindowRegion.DRAG)
                .children(spacer, button);
        gui.root().children(bar);
        return ChromeRegions.of(gui.frame(400f, 300f, ChromeRegionsTest::noText));
    }

    @Test
    void aDeclaredBarBecomesACaptionRectangleInClientPixels() {
        try (Gui gui = new Gui(Atchung.create())) {
            HitRegions regions = oneBarWithButton(gui);

            assertEquals(1, regions.caption().size(), "one strip declared, one caption rectangle published");
            assertEquals(new HitRegions.Rect(0, 0, 400, 32), regions.caption().get(0));
        }
    }

    @Test
    void aButtonOnTheBarBecomesAHoleInIt() {
        try (Gui gui = new Gui(Atchung.create())) {
            HitRegions regions = oneBarWithButton(gui);

            assertEquals(1, regions.interactive().size());
            assertEquals(new HitRegions.Rect(354, 0, 46, 32), regions.interactive().get(0));
            // And the rule that matters end to end: the click lands on the button, the drag starts anywhere else.
            assertEquals(HitRegions.Zone.CLIENT, regions.zone(370, 16, 400, 300, false, 0));
            assertEquals(HitRegions.Zone.CAPTION, regions.zone(100, 16, 400, 300, false, 0));
        }
    }

    @Test
    void theMaximizeButtonIsBothInteractiveAndNamed() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node max = gui.box().width(Length.dp(46)).height(Length.dp(32))
                    .windowRegion(WindowRegion.MAXIMIZE_BUTTON);
            gui.root().children(gui.box().width(Length.FILL).height(Length.dp(32))
                    .windowRegion(WindowRegion.DRAG).children(max));

            HitRegions regions = ChromeRegions.of(gui.frame(400f, 300f, ChromeRegionsTest::noText));

            assertNotNull(regions.maximizeButton(), "reported to the window manager as the maximize affordance");
            assertTrue(regions.interactive().contains(regions.maximizeButton()),
                    "and still a hole in the caption, or its own click would start a window drag");
            assertEquals(HitRegions.Zone.MAXIMIZE_BUTTON, regions.zone(20, 16, 400, 300, false, 0));
        }
    }

    @Test
    void aHiddenBarPublishesNothing() {
        // A hidden node keeps its last rect but was not laid out, so publishing it would hand the window manager
        // a title bar that is not on screen — draggable empty space.
        try (Gui gui = new Gui(Atchung.create())) {
            Node bar = gui.box().width(Length.FILL).height(Length.dp(32)).windowRegion(WindowRegion.DRAG);
            gui.root().children(bar);
            ChromeRegions.of(gui.frame(400f, 300f, ChromeRegionsTest::noText));

            bar.visible(false);
            HitRegions after = ChromeRegions.of(gui.frame(400f, 300f, ChromeRegionsTest::noText));

            assertSame(HitRegions.NONE, after, "nothing declared once the bar is hidden");
        }
    }

    @Test
    void aTreeWithoutDeclarationsIsEntirelyContent() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.box().width(Length.FILL).height(Length.dp(32)));

            HitRegions regions = ChromeRegions.of(gui.frame(400f, 300f, ChromeRegionsTest::noText));

            assertSame(HitRegions.NONE, regions);
            assertNull(regions.maximizeButton());
        }
    }

    @Test
    void theRegionsFollowTheLayoutRatherThanBeingRegistered() {
        // The point of deriving them each frame: a bar that moves because something above it appeared reports its
        // new rectangle without anything having told it to.
        try (Gui gui = new Gui(Atchung.create())) {
            Node banner = gui.box().width(Length.FILL).height(Length.dp(20)).visible(false);
            Node bar = gui.box().width(Length.FILL).height(Length.dp(32)).windowRegion(WindowRegion.DRAG);
            gui.root().children(banner, bar);

            HitRegions before = ChromeRegions.of(gui.frame(400f, 300f, ChromeRegionsTest::noText));
            assertEquals(0, before.caption().get(0).y());

            banner.visible(true);
            HitRegions after = ChromeRegions.of(gui.frame(400f, 300f, ChromeRegionsTest::noText));

            assertEquals(20, after.caption().get(0).y(), "the caption moved down with the layout, unprompted");
        }
    }

    @Test
    void nullRootIsNoRegions() {
        assertSame(HitRegions.NONE, ChromeRegions.of(null));
    }
}
