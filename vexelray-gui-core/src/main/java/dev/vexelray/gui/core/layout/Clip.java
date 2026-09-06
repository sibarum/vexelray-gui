package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.RetainedNode;

/**
 * Where each node actually is: its box, less everything its ancestors clip away.
 *
 * <p>A compute-phase pass (docs/layout-read-model.md §2.1), run after layout and displacement have finished
 * moving things and before anything is published. It writes {@code clipX/Y/W/H} onto each node and computes
 * nothing at publish time, which is the rule: a value that needs working out belongs to the phase that can see
 * the tree, not to the projection that copies it out.
 *
 * <h2>Why the read-model needs it at all</h2>
 * {@link NodeLayout#rect} says where a node was <em>put</em>. Those were the same question until virtualisation:
 * a list of a hundred thousand rows realizes a screenful of them, lays each one out where its index says, and
 * relies on the clip to show only the ones in the window. A row scrolled past the top is in the tree, has a
 * rect, and every pixel of that rect belongs to whatever is drawn there instead — so a reader outside the
 * renderer that treats "has a rect" as "can be pointed at" aims at a sticky header and reports success.
 *
 * <h2>The rule is {@code HitTest}'s rule</h2>
 * Descend into a node only where the point is inside it; inside a scroller, only within the viewport its
 * scrollbars were taken out of; and never apply that viewport to a floating child, which was not scrolled and
 * draws wherever it was placed. Stated here in terms of rectangles rather than of one point, which is the only
 * difference between the two — and pinned by {@code ClipTest}, which asks the input path whether it agrees
 * rather than asking a second copy of this walk.
 *
 * <p>The renderer states the same rule a third time, in the clips {@code TreeRenderer} pushes. Folding those
 * three into this one value is the obvious next step and is not this change: drawing is the one of the three
 * where being wrong is silent.
 */
public final class Clip {

    /** Resolve the whole tree. The root's own box is the first clip — nothing outside the window is reachable. */
    public static void resolve(RetainedNode root) {
        if (root == null) {
            return;
        }
        resolve(root, root.x, root.y, root.x + root.w, root.y + root.h);
    }

    /**
     * @param x0 the clip's left edge, {@code x1} its right, and likewise {@code y0}/{@code y1} — carried as
     *           edges rather than as a rectangle so that an empty clip cannot be mistaken for a small one
     *           half-way through the walk
     */
    private static void resolve(RetainedNode n, float x0, float y0, float x1, float y1) {
        float vx0 = Math.max(n.x, x0);
        float vy0 = Math.max(n.y, y0);
        float vx1 = Math.min(n.x + n.w, x1);
        float vy1 = Math.min(n.y + n.h, y1);
        boolean any = vx1 > vx0 && vy1 > vy0;
        n.clipX = any ? vx0 : 0f;
        n.clipY = any ? vy0 : 0f;
        n.clipW = any ? vx1 - vx0 : 0f;
        n.clipH = any ? vy1 - vy0 : 0f;
        if (n.children.isEmpty()) {
            return;
        }
        // Children are inside this node's box whatever else happens to them, and inside its viewport as well
        // when it scrolls — the same two gates, in the same order, that a hit test passes through.
        float cx0 = Math.max(n.x, x0);
        float cy0 = Math.max(n.y, y0);
        float cx1 = Math.min(n.x + n.w, x1);
        float cy1 = Math.min(n.y + n.h, y1);
        boolean clips = n.overflowX || n.overflowY;
        float sx0 = clips ? Math.max(cx0, n.viewX) : cx0;
        float sy0 = clips ? Math.max(cy0, n.viewY) : cy0;
        float sx1 = clips ? Math.min(cx1, n.viewX + n.viewW) : cx1;
        float sy1 = clips ? Math.min(cy1, n.viewY + n.viewH) : cy1;
        for (RetainedNode c : n.children) {
            if (c.floating()) {
                resolve(c, cx0, cy0, cx1, cy1);
            } else {
                resolve(c, sx0, sy0, sx1, sy1);
            }
        }
    }

    private Clip() {
    }
}
