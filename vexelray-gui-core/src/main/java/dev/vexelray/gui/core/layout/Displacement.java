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
     */
    public static void settle(RetainedNode node, LayoutMotion motion) {
        if (node.layoutRectKnown && (node.x != node.layoutX || node.y != node.layoutY)) {
            motion.moved(node, node.layoutX, node.layoutY, node.x, node.y);
        }
        node.layoutX = node.x;
        node.layoutY = node.y;
        node.layoutViewX = node.viewX;
        node.layoutViewY = node.viewY;
        node.layoutRectKnown = true;
        for (RetainedNode child : node.children) {
            settle(child, motion);
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
