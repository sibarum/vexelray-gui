package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HitTestTest {

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private static void attach(RetainedNode parent, RetainedNode child) {
        child.parent = parent;
        parent.children.add(child);
    }

    @Test
    void topmostChildWinsWhenOverlapping() {
        RetainedNode root = node(0, 0, 0, 100, 100);
        RetainedNode a = node(1, 0, 0, 50, 50);
        RetainedNode b = node(2, 40, 40, 40, 40); // added after a -> painted on top
        attach(root, a);
        attach(root, b);

        assertSame(b, HitTest.at(root, 45, 45), "overlap region resolves to the later-painted child");
        assertSame(a, HitTest.at(root, 10, 10), "a-only region resolves to a");
        assertSame(root, HitTest.at(root, 90, 90), "gap between children resolves to the root");
    }

    @Test
    void outsidePointIsMiss() {
        RetainedNode root = node(0, 0, 0, 100, 100);
        assertNull(HitTest.at(root, 200, 200));
        assertNull(HitTest.at(null, 5, 5));
    }

    /**
     * A scrolling container clips its children to the viewport, which excludes the strips its scrollbars
     * reserved. Hit-testing has to agree, or a press in the region under a scrollbar addresses content the clip
     * had just hidden — invisible, and in a text field enough to fling the caret to the end of the document.
     */
    @Test
    void aClippingNodeIsNotDescendedOutsideItsViewport() {
        RetainedNode root = node(0, 0, 0, 100, 100);
        root.overflowY = true;
        root.viewX = 0;
        root.viewY = 0;
        root.viewW = 90;    // the right 10px are the reserved v-scrollbar strip
        root.viewH = 100;
        RetainedNode child = node(1, 0, 0, 100, 100);   // spans the full width, under the bar too
        attach(root, child);

        assertSame(child, HitTest.at(root, 50, 50), "inside the viewport the child is hit as usual");
        assertSame(root, HitTest.at(root, 95, 50),
                "in the scrollbar strip the container is hit, not the child the clip removed");
    }

    /**
     * A float is not in the flow, so the flow's clip is not about it: it was placed against the settled box and
     * stays there while the content scrolls underneath. Trimming it to the viewport would make an overlay
     * unhittable exactly where the padding, the gutter or the scrollbar strip happens to be — a find bar floating
     * across the top of a scrolling field, dead along its left edge.
     */
    @Test
    void aFloatingChildIsHitOutsideItsParentsViewport() {
        RetainedNode root = node(0, 0, 0, 100, 100);
        root.overflowY = true;
        root.viewX = 20;    // the left 20px are a gutter, the right 10 the scrollbar strip
        root.viewY = 0;
        root.viewW = 70;
        root.viewH = 100;
        RetainedNode flow = node(1, 0, 0, 100, 100);
        RetainedNode bar = node(2, 0, 0, 100, 30);
        bar.set(PropKey.FLOAT_X, Length.ZERO);
        bar.set(PropKey.FLOAT_Y, Length.ZERO);
        attach(root, flow);
        attach(root, bar);

        assertSame(bar, HitTest.at(root, 5, 10), "over the gutter, where the flow child would have been clipped");
        assertSame(bar, HitTest.at(root, 95, 10), "and over the scrollbar strip, for the same reason");
        assertSame(flow, HitTest.at(root, 50, 50), "below the bar the flow child is hit as usual");
    }

    /** Paint order is hit order, and a float paints over every sibling — including ones declared after it. */
    @Test
    void aFloatingChildIsHitBeforeSiblingsThatFollowIt() {
        RetainedNode root = node(0, 0, 0, 100, 100);
        RetainedNode bar = node(1, 0, 0, 100, 30);
        bar.set(PropKey.FLOAT_X, Length.ZERO);
        bar.set(PropKey.FLOAT_Y, Length.ZERO);
        RetainedNode later = node(2, 0, 0, 100, 100);
        attach(root, bar);
        attach(root, later);

        assertSame(bar, HitTest.at(root, 50, 10));
        assertSame(later, HitTest.at(root, 50, 50));
    }

    /** Without overflow there is no clip, so the viewport fields are irrelevant and children hit everywhere. */
    @Test
    void aNonClippingNodeIgnoresItsViewport() {
        RetainedNode root = node(0, 0, 0, 100, 100);
        RetainedNode child = node(1, 0, 0, 100, 100);
        attach(root, child);

        assertSame(child, HitTest.at(root, 95, 50));
    }

    @Test
    void rightAndBottomEdgesAreExclusive() {
        RetainedNode root = node(0, 0, 0, 100, 100);
        assertSame(root, HitTest.at(root, 0, 0), "top-left corner is inside");
        assertNull(HitTest.at(root, 100, 50), "right edge is exclusive");
        assertNull(HitTest.at(root, 50, 100), "bottom edge is exclusive");
    }
}
