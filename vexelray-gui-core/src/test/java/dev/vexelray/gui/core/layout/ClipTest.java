package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NodeLayout#visibleRect}: where a node <em>is</em>, as opposed to where it was put.
 *
 * <p>These are compliance conditions rather than features (the shape {@code LabelGeometryTest} is in). A
 * virtualised list realizes rows that are laid out beyond its viewport and clipped away entirely, and every
 * reader outside the renderer — an automation agent, a thin client, a devtools overlay — was previously handed
 * a rect for one of those with nothing to say it could not be seen. What it did with that rect was aim at it,
 * and land on whatever the clip had put there instead.
 *
 * <p>The load-bearing claim is the last test here: this rectangle is computed by the same rule
 * {@link dev.vexelray.gui.core.input.HitTest} descends by, so "the centre of {@code visibleRect}" is a point
 * that really does address the node. Two statements of one rule is one statement too many, and the only thing
 * that keeps them honest is a test that asks the input path what it thinks.
 */
class ClipTest {

    private static final float W = 400f;
    private static final float H = 300f;

    /** No text anywhere in these trees: every box here is sized by its declared length. */
    private static final TextMeasurer NO_TEXT = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            return 0f;
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            return 0;
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            return new float[(text == null ? 0 : text.length()) + 1];
        }
    };

    private static Gui deterministic() {
        return new Gui(sibarum.atchung.Atchung.create(), Runnable::run);
    }

    /**
     * A list 100px tall holding four 40px rows. Rows 0 and 1 are wholly inside it, row 2 straddles its bottom
     * edge, and row 3 is laid out below it entirely — the arrangement every virtualised list is in, on the
     * frame before it drops the rows it no longer needs.
     */
    private record Rows(Gui gui, Node scroller, Node[] rows) { }

    private static Rows list(Gui gui) {
        Node[] rows = new Node[4];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = gui.box().width(Length.FILL).height(Length.dp(40));
        }
        Node scroller = gui.column().width(Length.dp(200)).height(Length.dp(100)).scroll(false, true)
                .children(rows);
        gui.root().children(scroller);
        gui.frame(W, H, NO_TEXT);
        return new Rows(gui, scroller, rows);
    }

    @Test
    void anUnclippedNodeIsVisibleExactlyWhereItWasPut() {
        try (Gui gui = deterministic()) {
            Rows list = list(gui);
            NodeLayout row = list.rows()[0].layout();

            assertFalse(row.clipped(), "nothing takes anything off the first row");
            assertEquals(row.rect(), row.visibleRect(), "so the two rectangles are the same rectangle");
        }
    }

    @Test
    void aRowStraddlingTheViewportEdgeIsVisibleOnlyAsFarAsTheEdge() {
        try (Gui gui = deterministic()) {
            Rows list = list(gui);
            NodeLayout scroller = list.scroller().layout();
            NodeLayout row = list.rows()[2].layout();

            assertTrue(row.clipped(), "half of it is past the bottom of the list");
            assertFalse(row.clippedAway(), "and half of it is not");
            assertEquals(row.rect().y(), row.visibleRect().y(), 0.01f, "it is cut at the bottom, not the top");
            assertEquals(scroller.content().y() + scroller.content().h(),
                    row.visibleRect().y() + row.visibleRect().h(), 0.01f,
                    "and cut exactly at the viewport, which is the rectangle the renderer clips to");
        }
    }

    @Test
    void aRowLaidOutBelowTheViewportIsVisibleNowhere() {
        try (Gui gui = deterministic()) {
            Rows list = list(gui);
            NodeLayout row = list.rows()[3].layout();

            assertTrue(row.present(), "it is laid out, and a reader can still find it");
            assertTrue(row.rect().h() > 0f, "with a rect that says where it would be");
            assertTrue(row.clippedAway(), "and no part of that rect is anywhere on screen");
            assertTrue(row.visibleRect().empty(), "which is one representation of nothing, not a negative box");
        }
    }

    /**
     * A node whose edge coincides with the edge of the box holding it is not clipped, though the two numbers
     * are not bit-identical: they are different sums that agree in exact arithmetic, and a report that took
     * the last bit of a {@code float} seriously would call almost every node in a rem-sized tree clipped.
     */
    @Test
    void aSubPixelDifferenceIsNotAClip() {
        // Straight from a real header cell, whose grip sits flush against a column boundary at 686px: the
        // intersection came back six ten-millionths of a pixel narrow, and the listing said "clipped".
        Rect rect = new Rect(679.6f, 0f, 6.4f, 32f);
        Rect visible = new Rect(679.6f, 0f, 686f - 679.6f, 32f);
        NodeLayout box = new NodeLayout(true, rect, rect, visible,
                0f, 0f, 0f, 0f, 0f, 0f, false, false, 0f, null);

        assertFalse(box.clipped(), "nothing a person could see is missing, so nothing is reported");
        assertFalse(box.clippedAway());
    }

    /**
     * A float is not scrolled, so the rectangle that hides scrolled content says nothing about it — the rule
     * {@code HitTest} states by trying floating children before it consults the viewport at all. The visible
     * case is the strip a scrollbar reserved: a bar floating over a scrolling list draws across it, and is hit
     * across it, though no scrolled content there can be either.
     */
    @Test
    void aFloatingChildIsNotSubjectToTheScrollClip() {
        try (Gui gui = deterministic()) {
            Node tall = gui.box().width(Length.FILL).height(Length.dp(400));
            Node overlay = gui.box().width(Length.dp(200)).height(Length.dp(20))
                    .floatAt(Length.dp(0), Length.dp(10));
            Node scroller = gui.column().width(Length.dp(200)).height(Length.dp(100)).scroll(false, true)
                    .children(tall, overlay);
            gui.root().children(scroller);
            gui.frame(W, H, NO_TEXT);

            NodeLayout list = scroller.layout();
            assertTrue(list.content().w() < list.rect().w(),
                    "the premise: the content overflows, so a scrollbar strip is reserved out of the viewport");

            NodeLayout box = overlay.layout();
            assertEquals(box.rect(), box.visibleRect(),
                    "the float is visible over its whole box, strip included");
            NodeLayout scrolled = tall.layout();
            assertEquals(list.content().w(), scrolled.visibleRect().w(), 0.01f,
                    "while ordinary content in the same box stops at the viewport");
        }
    }

    /**
     * The agreement that makes the rectangle worth publishing: a point outside {@code visibleRect} does not
     * reach the node, and a point inside it does. Asked of the input path rather than of a second copy of the
     * clip rule, because the two agreeing on a diagram is not the claim — the claim is about clicks.
     */
    @Test
    void aPointOutsideTheVisibleRectDoesNotReachTheNodeAndOneInsideDoes() {
        try (Gui gui = deterministic()) {
            Rows list = list(gui);
            AtomicInteger clipped = new AtomicInteger();
            AtomicInteger straddling = new AtomicInteger();
            gui.onClick(list.rows()[3], clipped::incrementAndGet);
            gui.onClick(list.rows()[2], straddling::incrementAndGet);
            gui.frame(W, H, NO_TEXT);

            NodeLayout away = list.rows()[3].layout();
            click(gui, away.rect().centreX(), away.rect().centreY());
            gui.frame(W, H, NO_TEXT);
            assertEquals(0, clipped.get(),
                    "the centre of a clipped-away row is a place the pointer cannot address it");

            NodeLayout half = list.rows()[2].layout();
            click(gui, half.visibleRect().centreX(), half.visibleRect().centreY());
            gui.frame(W, H, NO_TEXT);
            assertEquals(1, straddling.get(),
                    "and the centre of what is left of a half-clipped row is a place it can");
        }
    }

    /** One press and release where the pointer is, through the ordinary input path. */
    private static void click(Gui gui, float x, float y) {
        int px = Math.round(x);
        int py = Math.round(y);
        gui.bus().publish(InputTopics.INPUT, new InputEvent.PointerMoved(px, py, 0, 0, 0));
        gui.bus().publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, px, py, 0));
        gui.bus().publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, px, py, 0));
    }
}
