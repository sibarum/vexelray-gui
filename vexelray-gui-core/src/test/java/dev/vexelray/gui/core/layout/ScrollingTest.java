package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bringing a node into view, against hand-built rectangles and no {@code Gui} — see
 * {@code TextGeometryTest} for why that absence is the assertion rather than a convenience
 * (docs/plans/gui-decomposition.md §4).
 */
class ScrollingTest {

    private static long ids = 1L;

    /**
     * A scroller whose viewport is {@code [y, y + viewH]} in laid-out coordinates, holding {@code contentH} of
     * content. Positions are absolute, as they are after layout, because the arithmetic compares a target's
     * laid-out {@code y} against an ancestor's {@code viewY} directly.
     */
    private static RetainedNode scroller(float y, float viewH, float contentH) {
        RetainedNode n = new RetainedNode(ids++);
        n.x = 0f;
        n.y = y;
        n.w = 100f;
        n.h = viewH;
        n.viewX = 0f;
        n.viewY = y;
        n.viewW = 100f;
        n.viewH = viewH;
        n.contentW = 100f;
        n.contentH = contentH;
        n.overflowY = contentH > viewH;
        return n;
    }

    /** A row of height {@code h} whose laid-out top is {@code y}, parented to {@code parent}. */
    private static RetainedNode row(RetainedNode parent, float y, float h) {
        RetainedNode n = new RetainedNode(ids++);
        n.x = 0f;
        n.y = y;
        n.w = 100f;
        n.h = h;
        n.parent = parent;
        parent.children.add(n);
        return n;
    }

    @Test
    void aRowAlreadyInViewIsNotScrolledTo() {
        RetainedNode box = scroller(0f, 100f, 500f);
        RetainedNode visible = row(box, 20f, 20f);

        assertFalse(Scrolling.reveal(visible), "nothing moved");
        assertEquals(0f, box.scrollY);
    }

    @Test
    void aRowBelowTheFoldIsBroughtUpByTheLeastThatDoesIt() {
        RetainedNode box = scroller(0f, 100f, 500f);
        RetainedNode below = row(box, 180f, 20f);

        assertTrue(Scrolling.reveal(below));
        assertEquals(100f, box.scrollY, 0.001f, "its bottom reaches the bottom edge, and not one pixel more");
    }

    /**
     * The leading edge wins the tie. A row taller than the viewport is shown from its top rather than scrolled
     * to its bottom — the top of something too big to see is the part that says what it is.
     */
    @Test
    void aRowTallerThanTheViewportIsShownFromItsTop() {
        RetainedNode box = scroller(0f, 100f, 900f);
        RetainedNode tall = row(box, 300f, 400f);

        assertTrue(Scrolling.reveal(tall));
        assertEquals(300f, box.scrollY, 0.001f, "its top at the top edge, not its bottom at the bottom");
    }

    /** A container that fits its content has no offset to give, and asking is not an error. */
    @Test
    void revealingInsideSomethingThatDoesNotScrollIsAlreadySatisfied() {
        RetainedNode box = scroller(0f, 500f, 100f);
        RetainedNode any = row(box, 20f, 20f);

        assertFalse(Scrolling.reveal(any));
        assertEquals(0f, box.scrollY);
    }

    /**
     * The nested case, which is the only part that is not obvious: scrolling the inner container moves the
     * target within it, so the outer one has to be asked about where the target has just ended up. Ask it about
     * where the target was laid out and the two scrollers each solve the problem the other just changed.
     */
    @Test
    void nestedScrollersAgreeBecauseTheInnerOneReportsWhatItMoved() {
        RetainedNode outer = scroller(0f, 100f, 600f);
        RetainedNode inner = scroller(200f, 100f, 400f);
        inner.parent = outer;
        outer.children.add(inner);
        RetainedNode target = row(inner, 450f, 20f);

        assertTrue(Scrolling.reveal(target));

        // Inner first: the row sits 250 below the inner viewport top, and 170 below its bottom edge, so 170 is
        // the least that shows it.
        assertEquals(170f, inner.scrollY, 0.001f, "the inner scroller brings the row to its own bottom edge");

        // The row is now at 450 - 170 = 280, and the outer scroller is asked about *that*. It needs 200 to bring
        // 280..300 to its bottom edge. Asked about the laid-out 450 instead it would have moved 370 -- scrolling
        // the outer container past the row the inner one had already brought up.
        assertEquals(200f, outer.scrollY, 0.001f,
                "asked about where the row now is, not the 370 it would have moved for where it was laid out");
    }

    /** An ancestor that is hidden has stale geometry, so nothing under it is anywhere worth scrolling to. */
    @Test
    void aHiddenAncestorStopsTheWalk() {
        RetainedNode box = scroller(0f, 100f, 500f);
        box.set(dev.vexelray.gui.core.model.PropKey.VISIBLE, false);
        RetainedNode below = row(box, 180f, 20f);

        assertFalse(Scrolling.reveal(below));
        assertEquals(0f, box.scrollY);
    }

    @Test
    void aNodeThatIsNotInTheTreeIsNotRevealed() {
        RetainedNode orphan = new RetainedNode(ids++);
        assertFalse(Scrolling.reveal(orphan), "no parent: never in the tree, or gone from it since the ask");
        assertFalse(Scrolling.reveal(null), "and the ask can outlive the node entirely");
    }
}
