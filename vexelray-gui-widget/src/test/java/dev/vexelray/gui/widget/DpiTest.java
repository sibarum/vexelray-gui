package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Display density, and the coordinate-space contract that comes with it.
 *
 * <p>Density is the factor that historically breaks a GUI framework on its first Retina-class display and not one
 * moment sooner, because on a 1:1 display points and pixels are the same number and every space confusion is
 * invisible. These tests exist so the confusion is reproducible on a 1:1 machine — the failure is arithmetic, not
 * hardware, and there is no reason to wait for a port to find it.
 *
 * <p>Density is deliberately a separate factor from {@link dev.vexelray.gui.core.Gui#zoom}: it keeps a UI the same
 * <em>physical</em> size on a denser screen, where zoom is the user asking for bigger. A length can honour one and
 * not the other, which is only expressible while the two stay apart.
 */
class DpiTest {

    @Test
    void lengthsScaleWithDensity() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().width(Length.rem(2)).height(Length.rem(2));
            h.gui.root().children(box);
            h.frame();
            assertEquals(32f, box.layout().rect().w(), 0.5f, "2rem = 32px at density 1");

            h.gui.dpi(2f);
            h.frame();
            assertEquals(64f, box.layout().rect().w(), 0.5f,
                    "at density 2 the same 2rem occupies twice the pixels — and the same physical size");
        }
    }

    /** Density and zoom multiply, and are tracked independently. */
    @Test
    void densityAndZoomCompose() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().width(Length.rem(2)).height(Length.rem(2));
            h.gui.root().children(box);

            h.gui.dpi(2f);
            h.gui.zoom(1.5f);
            h.frame();
            assertEquals(96f, box.layout().rect().w(), 0.5f, "32 * 2 * 1.5");

            h.gui.zoom(1f);
            h.frame();
            assertEquals(64f, box.layout().rect().w(), 0.5f, "zoom back out, density stays");
        }
    }

    /**
     * The whole {@code dp} contract, as a 2x2: density moves it, zoom does not, and {@code em} answers both.
     * That difference is the only reason the unit exists, so it is the thing to pin down.
     */
    @Test
    void dpHonoursDensityAndIgnoresZoom() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node dp = h.gui.box().width(Length.dp(20)).height(Length.dp(20));
            Node em = h.gui.box().width(Length.rem(1.25f)).height(Length.rem(1.25f));   // also 20px at 1x/1x
            h.gui.root().children(dp, em);
            h.frame();
            assertEquals(20f, dp.layout().rect().w(), 0.5f, "they start the same size");
            assertEquals(20f, em.layout().rect().w(), 0.5f);

            h.gui.zoom(2f);
            h.frame();
            assertEquals(20f, dp.layout().rect().w(), 0.5f, "zoom does not move dp — this is the point of it");
            assertEquals(40f, em.layout().rect().w(), 0.5f, "while em follows the user's zoom");

            h.gui.zoom(1f);
            h.gui.dpi(2f);
            h.frame();
            assertEquals(40f, dp.layout().rect().w(), 0.5f,
                    "but density does move it: dp is density-independent, not device pixels");
            assertEquals(40f, em.layout().rect().w(), 0.5f, "and em honours density too");

            h.gui.zoom(2f);
            h.frame();
            assertEquals(40f, dp.layout().rect().w(), 0.5f, "density only, at any zoom");
            assertEquals(80f, em.layout().rect().w(), 0.5f, "em takes both factors");
        }
    }

    /**
     * dp is a fixed unit, so it resolves with no containing basis — a dp width is a real size, not
     * measure-to-content. Checked through a parent that sizes to its child rather than stretching over it.
     */
    @Test
    void dpResolvesWithoutABasis() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node inner = h.gui.box().width(Length.dp(50)).height(Length.dp(10));
            Node outer = h.gui.column().children(inner);
            h.gui.root().alignItems(dev.vexelray.gui.core.layout.LayoutEnums.AlignItems.START).children(outer);
            h.frame();
            assertEquals(50f, inner.layout().rect().w(), 0.5f, "the dp child is 50px");
            assertEquals(50f, outer.layout().rect().w(), 0.5f,
                    "and an auto parent measures to it, so dp carries an intrinsic size like em does");
        }
    }

    /** A density change relays out, the same way zoom and a resize do. */
    @Test
    void changingDensityRepublishesTheReadModel() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().width(Length.rem(2)).height(Length.rem(2));
            h.gui.root().children(box);
            h.frame();
            long version = h.gui.layoutSnapshot().version();

            h.gui.dpi(2f);
            h.frame();

            org.junit.jupiter.api.Assertions.assertTrue(h.gui.layoutSnapshot().version() > version);
        }
    }

    /**
     * <b>The macOS bug, on a 1:1 machine.</b>
     *
     * <p>The framework lays out in whatever viewport it is handed and hit-tests input against those rects, so the
     * two must be the same coordinate space. On a Retina-class display they are not the same number: a window is
     * 900x560 <em>points</em> and 1800x1120 <em>pixels</em>. Lay out in pixels and deliver input in point space —
     * which is what {@code CoordinateSpace.CLIENT} gives you — and every press lands at half its true position.
     *
     * <p>Here that is arithmetic rather than hardware: lay out at density 2 and press once at the framebuffer
     * coordinate and once at the point coordinate for the same visual spot. One hits, the other misses. On a 1:1
     * display the two calls would be identical, which is precisely how this survives development.
     */
    /** Two stacked 4rem boxes and which of them a press reached; -1 for neither. */
    private static final class Stack {
        Node first;
        Node second;
        int hit = -1;

        float secondCentre() {
            return second.layout().rect().y() + second.layout().rect().h() / 2f;
        }
    }

    private static Stack stack(HeadlessGui h) {
        Stack s = new Stack();
        s.first = h.gui.box().width(Length.rem(4)).height(Length.rem(4));
        s.second = h.gui.box().width(Length.rem(4)).height(Length.rem(4));
        h.gui.root().children(s.first, s.second);
        h.gui.onClick(s.first, () -> s.hit = 0);
        h.gui.onClick(s.second, () -> s.hit = 1);
        h.frame();
        return s;
    }

    @Test
    void inputMustArriveInTheSpaceTheLayoutRanIn() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dpi(2f);
            Stack s = stack(h);
            assertEquals(128f, s.first.layout().rect().h(), 0.5f, "4rem is 128 framebuffer px at density 2");

            // Aim at the second box. In framebuffer space that is its centre; in point space it is half that —
            // which is what CoordinateSpace.CLIENT reports on a Retina-class display.
            float aimFramebuffer = s.secondCentre();

            h.click(10f, aimFramebuffer / 2f);
            assertEquals(0, s.hit,
                    "a point-space coordinate lands on the *first* box: the user aimed at one control and hit "
                            + "the one above it. This is the Retina bug, reproduced by arithmetic alone");

            s.hit = -1;
            h.click(10f, aimFramebuffer);
            assertEquals(1, s.hit, "the same aim in the layout's own space reaches the intended box");
        }
    }

    /**
     * And why a 1:1 machine cannot notice: at density 1 the point coordinate and the pixel coordinate are the
     * same number, so the call that misdirected above now lands correctly. Nothing about the framework changed —
     * only the display it is imagined to be running on.
     */
    @Test
    void theSameMistakeIsInvisibleAtDensityOne() {
        try (HeadlessGui h = new HeadlessGui()) {
            Stack s = stack(h);
            assertEquals(64f, s.first.layout().rect().h(), 0.5f, "4rem is 64px at density 1");

            float aimFramebuffer = s.secondCentre();
            h.click(10f, aimFramebuffer);
            assertEquals(1, s.hit, "correct, as it would be on any 1:1 display");
            assertNotEquals(0, s.hit, "the mistake above simply cannot be made here — the numbers coincide");
        }
    }
}
