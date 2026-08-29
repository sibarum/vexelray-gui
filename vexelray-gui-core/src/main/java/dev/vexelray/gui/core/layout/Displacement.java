package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.RetainedNode;

/**
 * The two tree walks that turn a {@link LayoutMotion}'s per-node displacements into drawn positions.
 *
 * <p>Kept apart from {@link dev.vexelray.gui.core.Gui} because the ordering they encode is the whole of the
 * feature and is worth testing without a frame around it: {@link #settle} must run while the rects are the ones
 * layout just wrote, and {@link #displace} must be able to run on a frame where layout did not run at all and
 * still produce the same answer. The second property is the one that keeps a transition from drifting, and it
 * is not observable from a single frame.
 */
public final class Displacement {

    private Displacement() {
    }

    /**
     * Record where layout has put every node, reporting each one that moved to {@code motion}.
     *
     * <p>Call once per layout pass, after the compute phase, while {@link RetainedNode#x} still holds the
     * settled position. A node seen for the first time is recorded silently: it did not move, it arrived, and
     * telling a motion source it travelled from wherever the fields happened to be zeroed would have every new
     * row fly in from the top-left corner.
     *
     * <p><b>Scrolling is not moving.</b> A scroller's offset is baked into the absolute position of everything
     * under it, so a scroll of ten pixels arrives here as every descendant having moved ten pixels — and a
     * motion source told that animates the subtree lagging behind the scroll it is inside of, which is both
     * wrong and, because only <em>enrolled</em> nodes lag, visibly torn. So the walk carries the scroll each
     * ancestor has taken since it was last recorded, and a node is reported as having moved only by the part
     * that is not accounted for by that. A row dragged to a new slot in a list the user is also scrolling
     * reports the drag and not the scroll.
     */
    public static void settle(RetainedNode node, LayoutMotion motion) {
        settle(node, motion, 0f, 0f);
    }

    /**
     * @param scrolledX how far this node has been carried by ancestors' scrolling since it was last recorded,
     *                  which is exactly the part of any change in its absolute position that is not a move
     */
    private static void settle(RetainedNode node, LayoutMotion motion, float scrolledX, float scrolledY) {
        // Where the node would be now if nothing but the scrolling had happened. Comparing against this rather
        // than against the recorded position is the whole of the fix, and reporting `from` in the same frame is
        // what keeps a genuine move that happens *during* a scroll reporting its true distance.
        float fromX = node.layoutX + scrolledX;
        float fromY = node.layoutY + scrolledY;
        if (node.layoutRectKnown && (node.x != fromX || node.y != fromY)) {
            motion.moved(node, fromX, fromY, node.x, node.y);
        }
        node.layoutX = node.x;
        node.layoutY = node.y;
        node.layoutViewX = node.viewX;
        node.layoutViewY = node.viewY;
        node.layoutRectKnown = true;

        // A child is placed at baseX - scrollX, so an offset that grew by ten carried every descendant ten
        // pixels the other way. Accumulated down the tree, because scrollers nest.
        float childScrolledX = scrolledX - (node.scrollX - node.layoutScrollX);
        float childScrolledY = scrolledY - (node.scrollY - node.layoutScrollY);
        node.layoutScrollX = node.scrollX;
        node.layoutScrollY = node.scrollY;
        for (RetainedNode child : node.children) {
            settle(child, motion, childScrolledX, childScrolledY);
        }
    }

    /**
     * Draw every node at its settled position plus the displacement it and its ancestors are carrying, and
     * report whether anything is still short of where it belongs.
     *
     * <p>Absolute rather than incremental: each node's position is rebuilt from the recorded one every frame, so
     * running this twice on one frame, or on a frame with no layout pass, yields the same tree. That is what
     * makes it safe to call unconditionally.
     *
     * @return whether any node was displaced, i.e. whether the tree is still in motion and a further frame is
     *         owed
     */
    public static boolean displace(RetainedNode node, LayoutMotion motion) {
        return displace(node, motion, 0f, 0f);
    }

    private static boolean displace(RetainedNode node, LayoutMotion motion, float ax, float ay) {
        if (!node.layoutRectKnown) {
            // Laid out but never settled: there is no recorded position to displace from, and inventing one
            // would move the node to wherever the uninitialised fields point. Leave it where layout put it.
            return displaceChildren(node, motion, ax, ay);
        }
        float dx = ax + motion.displacementX(node);
        float dy = ay + motion.displacementY(node);
        node.x = node.layoutX + dx;
        node.y = node.layoutY + dy;
        node.viewX = node.layoutViewX + dx;
        node.viewY = node.layoutViewY + dy;
        boolean moving = dx != 0f || dy != 0f;
        return displaceChildren(node, motion, dx, dy) || moving;
    }

    private static boolean displaceChildren(RetainedNode node, LayoutMotion motion, float ax, float ay) {
        boolean moving = false;
        for (RetainedNode child : node.children) {
            // Not short-circuited: every child must be placed, and `||` would stop walking at the first one that
            // reported motion.
            moving |= displace(child, motion, ax, ay);
        }
        return moving;
    }
}
