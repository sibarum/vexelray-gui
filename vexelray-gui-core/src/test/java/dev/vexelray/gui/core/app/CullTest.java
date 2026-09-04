package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the renderer declines to build at all.
 *
 * <p>A clip hides what it covers; it does not stop the geometry underneath being built. So a container holding
 * thousands of rows and showing twenty of them used to turn every one of the rest into quads, every frame, for
 * the mask to throw away — which is not merely wasteful. It is what makes a tailing log overrun a fixed vertex
 * buffer and take the window down with it, and the failure arrives as a crash in the presenter with nothing in
 * the stack to say a scrollback was involved.
 *
 * <p>These are all about a node being <b>absent from the output</b> rather than drawn and masked, which is the
 * distinction a screenshot cannot make and the only one that matters to the buffer. No layout runs: the renderer
 * reads computed geometry off {@link RetainedNode}, so a hand-placed tree is the whole input.
 */
class CullTest {

    private static final Color PAGE = Color.rgba(1f, 0f, 0f, 1f);
    private static final Color SEEN = Color.rgba(0f, 1f, 0f, 1f);
    private static final Color GONE = Color.rgba(0f, 0f, 1f, 1f);

    private static long nextId = 1;

    /** A 100x20 row at {@code y}, in {@code colour}. */
    private static RetainedNode row(float y, Color colour) {
        RetainedNode n = new RetainedNode(nextId++);
        n.set(PropKey.BACKGROUND, colour);
        n.x = 0f;
        n.y = y;
        n.w = 100f;
        n.h = 20f;
        return n;
    }

    /**
     * A container that scrolls: 100 wide, 40 tall at the origin, with its viewport the same. Overflow is what
     * makes the renderer clip to that viewport, which is what there is to be culled against.
     */
    private static RetainedNode viewport() {
        RetainedNode n = new RetainedNode(nextId++);
        n.set(PropKey.BACKGROUND, PAGE);
        n.w = 100f;
        n.h = 40f;
        n.viewX = 0f;
        n.viewY = 0f;
        n.viewW = 100f;
        n.viewH = 40f;
        n.overflowY = true;
        return n;
    }

    private static RetainedNode child(RetainedNode parent, RetainedNode c) {
        c.parent = parent;
        parent.children.add(c);
        return c;
    }

    private static float[] emit(RetainedNode root) {
        Canvas canvas = new Canvas(200, 200).begin();
        TreeRenderer.emit(root, canvas, new TextLayout[]{null});
        return java.util.Arrays.copyOf(canvas.toVertexArray(),
                canvas.vertexCount() * CanvasVertex.FLOATS_PER_VERTEX);
    }

    /** Whether any vertex was emitted in {@code colour} — that is, whether the node was built at all. */
    private static boolean drawn(float[] data, Color colour) {
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + rgba] == colour.r() && data[v + rgba + 1] == colour.g()
                    && data[v + rgba + 2] == colour.b()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aRowInsideTheViewportIsDrawn() {
        RetainedNode scroller = viewport();
        child(scroller, row(10f, SEEN));

        assertTrue(drawn(emit(scroller), SEEN), "it is in view; nothing about culling should touch it");
    }

    @Test
    void aRowFarBelowTheViewportIsNotBuiltAtAll() {
        RetainedNode scroller = viewport();
        child(scroller, row(10f, SEEN));
        child(scroller, row(4000f, GONE));   // the thousandth line of a scrollback

        float[] data = emit(scroller);
        assertTrue(drawn(data, SEEN), "the visible row is still there");
        assertFalse(drawn(data, GONE), "a row that far out must not reach the vertex buffer at all");
    }

    @Test
    void aRowFarAboveTheViewportIsNotBuiltEither() {
        RetainedNode scroller = viewport();
        child(scroller, row(-4000f, GONE));

        assertFalse(drawn(emit(scroller), GONE), "scrolled off the top is as absent as scrolled off the bottom");
    }

    @Test
    void aRowStraddlingTheEdgeIsKept() {
        RetainedNode scroller = viewport();
        child(scroller, row(30f, SEEN));   // 30..50, and the viewport ends at 40

        assertTrue(drawn(emit(scroller), SEEN), "half of it is visible, so all of it is built and masked");
    }

    @Test
    void aRowJustOutsideIsKeptRatherThanCutFine() {
        RetainedNode scroller = viewport();
        child(scroller, row(45f, SEEN));   // wholly below the viewport, but only by a quarter of its height

        assertTrue(drawn(emit(scroller), SEEN),
                "the margin is deliberate: a node is not guaranteed to draw inside its own box, and text given "
                        + "less height than it needs spills past the bottom of it");
    }

    @Test
    void cullingFollowsATranslatedSubtreeRatherThanItsBoxes() {
        // The case that makes this worth a test. The row's own box is far below the viewport, so judged on the
        // box alone it would be dropped -- but its parent is translated back up, which is where it actually
        // draws. A cull that ignored the transform would delete the visible content of every animating panel.
        RetainedNode scroller = viewport();
        RetainedNode moved = child(scroller, row(0f, PAGE));
        moved.h = 40f;
        moved.set(PropKey.TRANSLATE_Y, -100f);
        moved.emPx = 1f;   // translate is in multiples of the node's own em
        child(moved, row(4000f, GONE));
        child(moved, row(4100f, SEEN));

        // Neither is in view here: -100 does not bring 4000 anywhere near 0..40. The point is the arithmetic
        // happens at all -- so shift the mask far enough that one of them lands in it.
        moved.set(PropKey.TRANSLATE_Y, -4090f);
        float[] data = emit(scroller);
        assertTrue(drawn(data, SEEN), "translated into view, so it has to be built");
        assertFalse(drawn(data, GONE), "translated by the same amount and still out of view");
    }

    @Test
    void withNoClipAnywhereNothingIsCulled() {
        RetainedNode root = row(0f, PAGE);
        root.w = 100f;
        root.h = 40f;
        child(root, row(9000f, SEEN));

        assertTrue(drawn(emit(root), SEEN),
                "an unclipped container shows what overflows it, so there is nothing to judge a child against");
    }

    @Test
    void aWrapperWithNoAreaIsNotJudgedByItsOwnBox() {
        // A zero-sized node says nothing about where its children are, and the layout is allowed to produce one.
        RetainedNode scroller = viewport();
        RetainedNode wrapper = child(scroller, row(0f, PAGE));
        wrapper.w = 0f;
        wrapper.h = 0f;
        child(wrapper, row(10f, SEEN));

        assertTrue(drawn(emit(scroller), SEEN), "the child is in view even though its parent has no box");
    }
}
