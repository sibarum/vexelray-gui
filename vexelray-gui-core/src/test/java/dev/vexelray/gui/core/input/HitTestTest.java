package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HitTestTest {

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id, NodeKind.BOX);
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
