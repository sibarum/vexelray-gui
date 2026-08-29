package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.RetainedNode;

/**
 * Where a node is drawn, as distinct from where layout put it.
 *
 * <p>Layout is instantaneous and always right: a row reordered, an item removed, a container resized, and the
 * next pass states the new truth in full. What it cannot state is the <em>journey</em> — and a tree that
 * teleports every time its model changes is the same defect whether the change came from a drop, an undo, or an
 * expand. So the journey is modelled here, as one number per axis per node: the distance from where the node is
 * drawn to where layout says it belongs, which is non-zero only while it is catching up.
 *
 * <h2>Displacement, not position</h2>
 *
 * <p>An implementation never says where a node <em>is</em>; it says how far behind it is running, and the
 * framework does the arithmetic. That inversion is what keeps the two from disagreeing. A motion source that
 * reported absolute positions would be a second layout engine — free to place a node somewhere layout never
 * would, and obliged to re-derive scroll offsets, float origins and clip rects that the real one already
 * resolved. A displacement cannot express any of that: it is relative to a position the framework computed, so
 * every invariant layout established survives it, and the resting state is the single value zero rather than a
 * remembered rect that could rot.
 *
 * <p>Displacement is inherited. A node's own displacement is added to every one it is nested inside, because a
 * row that slides carries its label with it — the alternative, displacing a subtree by walking it, would have
 * every implementation re-derive the tree structure it was just handed.
 *
 * <h2>The one rect</h2>
 *
 * <p>Displacement is applied after the compute phase and before publish, which places it deliberately on one
 * side of a line. The compute phase — caret-follow scroll, text metrics — reads <b>settled</b> geometry, so a
 * scroller does not chase an animation that is already on its way to the answer. Everything downstream of it —
 * the read-model, the renderer, and the next frame's hit-testing — reads <b>displaced</b> geometry, so what the
 * user clicks is what the user sees.
 *
 * <p>That second half is the load-bearing one. A tree whose rows are visibly mid-flight but which hit-tests
 * against where they will land is precisely the "you have to get it in just the right spot" failure, and it is
 * invisible in a screenshot: it only appears under a pointer moving faster than the animation. One rect for
 * paint and pointer is what makes it impossible.
 *
 * <h2>Being told what moved</h2>
 *
 * <p>The framework detects movement, because it is the one holding both the old position and the new one, and
 * reports it through {@link #moved}. An implementation that wants a transition starts one there; one that does
 * not is free to ignore it. Nothing is reported for a node's first appearance — there is no previous position
 * for it to have come from, and the honest answer to "where did it come from" is nowhere, not the origin.
 *
 * <p>Both methods are called on the GUI thread, inside the frame. An implementation whose displacements are
 * driven from a clock on another thread must publish them safely; the framework reads them once per node per
 * frame and does not synchronize.
 */
public interface LayoutMotion {

    /** Nothing ever lags: every node is drawn exactly where layout put it. The default, and the honest collapse
     * for reduced motion, since a transition has no end state to snap to other than the one already there. */
    LayoutMotion NONE = new LayoutMotion() {

        @Override
        public void moved(RetainedNode node, float fromX, float fromY, float toX, float toY) {
            // nothing catches up, so nothing needs telling
        }

        @Override
        public float displacementX(RetainedNode node) {
            return 0f;
        }

        @Override
        public float displacementY(RetainedNode node) {
            return 0f;
        }

        @Override
        public String toString() {
            return "LayoutMotion.NONE";
        }
    };

    /**
     * Layout has just put {@code node} somewhere other than where it was. Called once per moved node per layout
     * pass, before any displacement is read, so a transition started here takes effect in the same frame the
     * move happens — the node never appears at its destination, not even for one frame.
     *
     * <p>Positions are absolute border-box origins in layout px, the same space {@link RetainedNode#x} is in. A
     * node that is going to catch up smoothly starts displaced by {@code from - to} and decays that to zero.
     */
    void moved(RetainedNode node, float fromX, float fromY, float toX, float toY);

    /** How far left of where layout put it this node is drawn, px; 0 when it has caught up. Inherited by the
     * subtree. */
    float displacementX(RetainedNode node);

    /** How far above where layout put it this node is drawn, px; 0 when it has caught up. Inherited by the
     * subtree. */
    float displacementY(RetainedNode node);
}
