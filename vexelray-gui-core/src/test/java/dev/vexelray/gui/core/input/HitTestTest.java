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

    @Test
    void rightAndBottomEdgesAreExclusive() {
        RetainedNode root = node(0, 0, 0, 100, 100);
        assertSame(root, HitTest.at(root, 0, 0), "top-left corner is inside");
        assertNull(HitTest.at(root, 100, 50), "right edge is exclusive");
        assertNull(HitTest.at(root, 50, 100), "bottom edge is exclusive");
    }
}
