package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.RetainedNode;

/**
 * How a node goes: {@code Node.remove()} asks this first, and a node it takes is drawn leaving, in place, until it
 * has gone — and only then taken out of the tree.
 *
 * <p>The other half of {@link EnterMotion}. Removal used to be one frame: the node was there, and then the thing
 * under it was, revealed rather than uncovered. Every application that minded wrote the same workaround — fade the
 * node by hand, make it pointer-transparent, remove it from a callback when the fade ends — and each one had to
 * remember that a node it was fading out might be wanted back before it had gone.
 *
 * <h2>Leaving keeps its place</h2>
 *
 * <p>A leaving node stays in the tree and in the flow, where it was, until its {@link #presence} reaches 0. Then it is
 * detached, and whatever followed it slides into the space ({@link LayoutMotion}). Taking it out of the flow at once
 * would move its neighbours under it while it was still visible, and two things on the same pixels fading in opposite
 * directions read as a smear.
 *
 * <p>While it leaves it is <b>pointer-transparent</b> ({@link RetainedNode#hitInert}): what the user is looking at is
 * going away, and a click that lands on it in its last frames would act on something that has been deleted.
 *
 * <h2>Interrupted, it turns round where it is</h2>
 *
 * <p>Put back before it has gone — {@code append}, {@code insert} or an {@code arrange} that names it — a leaving node
 * is not rebuilt: the same node, with every handler and widget state still attached, is reported to
 * {@link EnterMotion#entered} at the presence it is drawn at, and comes back up from there. A node detached a frame
 * ago can still be put back the same way; one that has been gone longer than that is released, and putting it back
 * does nothing.
 *
 * <p>A second {@code remove()} of a node already leaving takes it out at once, which is the way to say "now".
 */
public interface ExitMotion {

    /** Nothing fades out: a removed node leaves the tree the frame it is removed. The default. */
    ExitMotion NONE = new ExitMotion() {

        @Override
        public boolean leave(RetainedNode node) {
            return false;
        }

        /** Nothing: a node still leaving when this replaced the source that took it goes at once. */
        @Override
        public float presence(RetainedNode node) {
            return 0f;
        }

        @Override
        public void left(RetainedNode node) {
            // never took one
        }

        @Override
        public String toString() {
            return "ExitMotion.NONE";
        }
    };

    /**
     * {@code node} has been asked to leave. Return true to take it — the framework keeps it where it is, drawn at
     * {@link #presence}, and detaches it in the frame that presence first reads 0 — or false to have it removed now.
     * It is drawn at {@link RetainedNode#presence} at this moment, so a departure that starts from there is continuous
     * with an arrival it interrupts. GUI thread.
     */
    boolean leave(RetainedNode node);

    /** How much of a leaving {@code node} is drawn, in [0, 1]; it is detached once this is 0. */
    float presence(RetainedNode node);

    /** {@code node} has left the tree for good — released, not merely detached. Forget anything kept for it. */
    void left(RetainedNode node);

    /**
     * {@code node}, already taken by {@link #leave}, is being replaced: its parent kept nothing and gained new children
     * in the same edit, so it has been lifted out of the flow and fades where it stood, over what replaces it
     * ({@link EnterMotion#replacing}). A source may go quicker here, since what arrives waits on it. GUI thread.
     * Nothing by default.
     */
    default void replaced(RetainedNode node) {
    }
}
