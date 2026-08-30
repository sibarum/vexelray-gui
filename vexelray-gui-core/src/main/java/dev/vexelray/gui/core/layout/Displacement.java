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
     * <p><b>A node moves when it moves within its parent, and not otherwise.</b> Positions here are absolute, so
     * a great many things change one without being a move of it: the parent moved and took it along, an
     * ancestor scrolled, the window was resized. Reporting those is wrong twice over.
     *
     * <ul>
     *   <li><b>It double-counts.</b> {@link #displace} inherits a displacement down the subtree, so a child whose
     *       parent is lagging is <em>already</em> being carried. Give the child its own lag for the same
     *       distance and it travels twice as far as it should, arriving from somewhere it never was. That is
     *       visible the moment a whole page is enrolled rather than one list: the deeper a control sits, the
     *       further it overshoots, so a button inside a row inside a card swings while the paragraph beside it
     *       looks right.</li>
     *   <li><b>It tears.</b> A scroll of ten pixels arrives as every descendant having moved ten pixels, so an
     *       enrolled subtree lags behind the scroll it is inside of — and since only enrolled nodes lag, the
     *       view splits into a moving half and a still one.</li>
     * </ul>
     *
     * <p>So the walk carries how far each node's <em>content origin</em> has shifted since it was last recorded
     * — its own absolute shift, less any scrolling it did — and a node is reported as having moved only by the
     * part that is not accounted for by that. A row dragged to a new slot in a list the user is also scrolling
     * reports the drag and not the scroll; a row sitting still in a card that slid reports nothing at all, and
     * is carried by the card exactly once.
     *
     * <p>A node seen for the first time is recorded silently: it did not move, it arrived, and telling a motion
     * source it travelled from wherever the fields happened to be zeroed would have every new row fly in from
     * the top-left corner.
     */
    public static void settle(RetainedNode node, LayoutMotion motion) {
        settle(node, motion, 0f, 0f);
    }

    /**
     * @param carriedX how far this node has been carried by its ancestors since it was last recorded — their
     *                 movement and their scrolling together, which is exactly the part of any change in its
     *                 absolute position that is not a move of its own
     */
    private static void settle(RetainedNode node, LayoutMotion motion, float carriedX, float carriedY) {
        // Where the node would be now if it had merely been carried. Comparing against this rather than against
        // the recorded position is the whole of it, and reporting `from` in the same frame is what keeps a
        // genuine move that happens *while* the node is being carried reporting its true distance.
        float fromX = node.layoutX + carriedX;
        float fromY = node.layoutY + carriedY;
        if (node.layoutRectKnown && (node.x != fromX || node.y != fromY)) {
            motion.moved(node, fromX, fromY, node.x, node.y);
        }

        // What this node's children have been carried by: everything that moved this node's box, less any
        // scrolling it did itself — a child is placed at baseX - scrollX, so an offset that grew by ten carried
        // every descendant ten pixels the other way. Read before the record is overwritten, and accumulated
        // down the tree because both effects nest.
        float childCarriedX = node.layoutRectKnown
                ? (node.x - node.layoutX) - (node.scrollX - node.layoutScrollX) : 0f;
        float childCarriedY = node.layoutRectKnown
                ? (node.y - node.layoutY) - (node.scrollY - node.layoutScrollY) : 0f;

        node.layoutX = node.x;
        node.layoutY = node.y;
        node.layoutViewX = node.viewX;
        node.layoutViewY = node.viewY;
        node.layoutScrollX = node.scrollX;
        node.layoutScrollY = node.scrollY;
        node.layoutRectKnown = true;

        for (RetainedNode child : node.children) {
            settle(child, motion, childCarriedX, childCarriedY);
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
