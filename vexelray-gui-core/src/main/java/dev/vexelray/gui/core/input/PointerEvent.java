package dev.vexelray.gui.core.input;

/**
 * Where the pointer is over a node: told on every move while the node (or a descendant) is under it, and once more,
 * with {@link #over()} false, when it leaves. What {@link InteractionState#HOVER} says without saying where.
 *
 * <p><b>Why a node needs it.</b> A picture's marks are not nodes and are never hit-tested (docs/reference/drawing.md),
 * so a chart, a plot or a timeline drawn as one {@code Picture} can know it is hovered but not which mark is under the
 * pointer. A node per mark is not the answer when there are thousands. With this the node's owner finds the mark
 * itself, from {@link #localX()} and {@link #localY()}, as it already does for a click.
 *
 * <p><b>It is still hover.</b> What a node does with it is bound by the stack's rule that nothing appears, moves or
 * grows on hover: a colour change under the pointer, not a bubble or a reveal.
 *
 * <p>Coordinates follow {@link DragEvent}'s: the pointer in client space, and the node's border box as the hit-test
 * judged it, so local coordinates need nothing but this record. A leave carries the pointer where it left and the box
 * last reported.
 *
 * @param nodeId the node told
 * @param x      pointer client-space x, px
 * @param y      pointer client-space y, px
 * @param nodeX  the node's border-box x, px
 * @param nodeY  the node's border-box y, px
 * @param nodeW  the node's border-box width, px
 * @param nodeH  the node's border-box height, px
 * @param over   true while the pointer is over the node or a descendant; false once, when it leaves
 */
public record PointerEvent(long nodeId, float x, float y, float nodeX, float nodeY, float nodeW, float nodeH,
                           boolean over) {

    /** The pointer's x from the node's left edge, px. */
    public float localX() {
        return x - nodeX;
    }

    /** The pointer's y from the node's top edge, px. */
    public float localY() {
        return y - nodeY;
    }

    /** This one as a leave, with the pointer at {@code (x, y)}. */
    PointerEvent left(float x, float y) {
        return new PointerEvent(nodeId, x, y, nodeX, nodeY, nodeW, nodeH, false);
    }
}
