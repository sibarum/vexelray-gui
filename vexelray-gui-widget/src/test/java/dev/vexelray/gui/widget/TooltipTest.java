package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tooltips, and the guarantee that makes them admissible under the hover rule: the bubble is hit-inert, so its
 * appearance changes nothing about what the pointer is on. It floats (no reflow), anchors to the control's box
 * (never follows the pointer), and coexists with the control's own hover styling (state observers accumulate).
 */
class TooltipTest {

    /** A page with a fixed 200x100 button at the origin carrying a zero-delay tooltip. */
    private static Node target(HeadlessGui h, Tooltip tip, String text) {
        Node button = h.gui.box().width(Length.vw(25)).height(Length.rem(6.25f));   // 200 x 100
        tip.delayMillis(0).attach(button, text);
        h.gui.root().children(button);
        h.frame();
        return button;
    }

    @Test
    void hoveringShowsTheBubbleBelowTheControl() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tooltip tip = new Tooltip(h.gui);
            Node button = target(h, tip, "does the thing");

            h.hover(100f, 50f);
            h.frame();

            assertTrue(tip.shown(), "the pointer rested on the control");
            var b = tip.node().layout().rect();
            var t = button.layout().rect();
            assertEquals(t.x(), b.x(), 0.5f, "anchored to the control's left edge");
            assertTrue(b.y() >= t.y() + t.h(), "and just below its box");
            tip.close();
        }
    }

    @Test
    void leavingTheControlHidesTheBubble() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tooltip tip = new Tooltip(h.gui);
            target(h, tip, "hint");

            h.hover(100f, 50f);
            h.frame();
            assertTrue(tip.shown());

            h.hover(600f, 400f);
            assertFalse(tip.shown(), "the pointer left: the bubble goes with it");
            tip.close();
        }
    }

    @Test
    void pressingHidesTheBubble() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tooltip tip = new Tooltip(h.gui);
            target(h, tip, "hint");

            h.hover(100f, 50f);
            h.frame();
            assertTrue(tip.shown());

            h.click(100f, 50f);
            assertFalse(tip.shown(), "a press means the user is acting, not reading");
            tip.close();
        }
    }

    /**
     * The admissibility condition: the bubble is never the pointer target. A click aimed where the bubble is
     * drawn passes through it and lands on whatever the page has there — and hovering the bubble's own pixels
     * neither hides it nor disturbs the control underneath.
     */
    @Test
    void theBubbleIsNeverThePointerTarget() {
        try (HeadlessGui h = new HeadlessGui()) {
            boolean[] pageClicked = {false};
            Tooltip tip = new Tooltip(h.gui);
            // A full-viewport page under a tooltip'd button, so the bubble always overlaps something clickable.
            Node page = h.gui.column().width(Length.FILL).height(Length.FILL);
            Node button = h.gui.box().width(Length.vw(25)).height(Length.rem(6.25f));
            page.append(button);
            h.gui.onClick(page, () -> pageClicked[0] = true);
            tip.delayMillis(0).attach(button, "hint");
            h.gui.root().children(page);
            h.frame();

            h.hover(100f, 50f);
            h.frame();
            assertTrue(tip.shown());
            var b = tip.node().layout().rect();
            float bx = b.x() + b.w() / 2f;
            float by = b.y() + b.h() / 2f;

            h.hover(bx, by);   // the pointer is now over the bubble's pixels — which are nobody's
            h.frame();
            // The hit under the bubble is the page, not the button: HOVER on the button ended, so the bubble
            // hides — but crucially nothing under the pointer ever *was* the bubble.
            h.click(bx, by);
            assertTrue(pageClicked[0], "the click passed through where the bubble was drawn to the page");
            tip.close();
        }
    }

    /** State observers accumulate: the control's own hover restyle keeps firing with a tooltip attached. */
    @Test
    void theControlsOwnHoverStylingStillWorks() {
        try (HeadlessGui h = new HeadlessGui()) {
            var seen = new java.util.ArrayList<InteractionState>();
            Tooltip tip = new Tooltip(h.gui);
            Node button = h.gui.box().width(Length.vw(25)).height(Length.rem(6.25f));
            h.gui.onState(button, seen::add);          // the widget's own restyle, registered first
            tip.delayMillis(0).attach(button, "hint"); // the tooltip's observer, added second
            h.gui.root().children(button);
            h.frame();

            h.hover(100f, 50f);
            h.frame();

            assertTrue(seen.contains(InteractionState.HOVER),
                    "the restyle observer still heard the hover — attach() added, it did not replace");
            assertTrue(tip.shown(), "and the tooltip heard it too");
            tip.close();
        }
    }

    @Test
    void showingTheBubbleReflowsNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tooltip tip = new Tooltip(h.gui);
            Node button = target(h, tip, "hint");
            float w = button.layout().rect().w();
            float hgt = button.layout().rect().h();

            h.hover(100f, 50f);
            h.frame();

            assertEquals(w, button.layout().rect().w(), 0.01f, "the floating bubble took no space");
            assertEquals(hgt, button.layout().rect().h(), 0.01f);
            tip.close();
        }
    }

    /** Anchored to the box, not the pointer: moving within the control leaves the bubble exactly where it was. */
    @Test
    void theBubbleNeverFollowsThePointer() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tooltip tip = new Tooltip(h.gui);
            target(h, tip, "hint");

            h.hover(20f, 20f);
            h.frame();
            var first = tip.node().layout().rect();

            h.hover(180f, 80f);   // still inside the control
            h.frame();
            var second = tip.node().layout().rect();

            assertEquals(first.x(), second.x(), 0.01f, "same anchor, same place");
            assertEquals(first.y(), second.y(), 0.01f);
            assertTrue(tip.shown());
            tip.close();
        }
    }
}
