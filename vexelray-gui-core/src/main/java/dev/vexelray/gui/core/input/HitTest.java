package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;

/**
 * Point → node hit-testing over the laid-out retained tree. Submission order is paint order and children paint
 * after their parent, so the topmost node under a point is the deepest last-painted descendant whose rect
 * contains it. Coordinates are client-space pixels (Y-down), the same space the layout writes into node rects and
 * that tactroller reports in {@code CoordinateSpace.CLIENT}.
 */
public final class HitTest {

    /** @return the topmost node containing {@code (x, y)}, or {@code null} if the point is outside {@code root}. */
    public static RetainedNode at(RetainedNode root, float x, float y) {
        if (root != null && !root.visible()) {
            return null;   // hidden nodes are not pointer targets, whatever geometry they still carry
        }
        if (root != null && root.hitInert()) {
            return null;   // pointer-transparent: drawn, but never under the pointer (subtree included)
        }
        if (root == null || !contains(root, x, y)) {
            return null;
        }
        // Descend only where the children are actually drawn. A scrolling container clips them to its viewport,
        // which excludes the strips its scrollbars reserved — so without this the region under a scrollbar hit
        // whatever content the clip had just hidden, and a press there addressed something invisible.
        if (!clips(root) || withinViewport(root, x, y)) {
            // Later children paint on top, so search them front-to-back (reverse) for the topmost hit.
            for (int i = root.children.size() - 1; i >= 0; i--) {
                RetainedNode hit = at(root.children.get(i), x, y);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return root;
    }

    /** Whether {@code n} clips its children — the same condition {@code TreeRenderer} pushes a clip for. */
    private static boolean clips(RetainedNode n) {
        return n.overflowX || n.overflowY;
    }

    private static boolean contains(RetainedNode n, float x, float y) {
        return x >= n.x && x < n.x + n.w && y >= n.y && y < n.y + n.h;
    }

    /** Whether the point is inside {@code n}'s content viewport — the rect its children are clipped to. */
    private static boolean withinViewport(RetainedNode n, float x, float y) {
        return x >= n.viewX && x < n.viewX + n.viewW && y >= n.viewY && y < n.viewY + n.viewH;
    }

    private HitTest() {
    }
}
