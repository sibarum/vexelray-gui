package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.RetainedNode;

/**
 * How much of a node is drawn while it arrives: the companion of {@link LayoutMotion} for the change that has no
 * previous position to travel from.
 *
 * <p>{@link LayoutMotion} deliberately says nothing about a node's first appearance — the honest answer to "where did
 * it come from" is nowhere. But a row that pops into existence in one frame is the same defect as one that teleports,
 * so arrival gets its own seam: a node joins at {@linkplain #presence presence} 0 and is brought up to 1, rising a
 * little as it comes if the source says so. Everything around it makes room at once and slides
 * ({@link LayoutMotion}), so there is still only one clock moving any position.
 *
 * <h2>What counts as arriving</h2>
 *
 * <p>The framework reports {@link #entered} when a node is put under a parent that has already been laid out, and it
 * was not in the tree a moment ago: a new node appended, a subtree built off-screen and then attached (only its top
 * arrives; its descendants come with it), or a node that was {@linkplain ExitMotion leaving} put back before it had
 * gone. A move between parents is not an arrival — it was on screen, and {@link LayoutMotion} carries it. Nothing in
 * the first frame's tree arrives either: a window being drawn for the first time has nothing to arrive into.
 *
 * <h2>Presence, not opacity</h2>
 *
 * <p>The node's own {@code opacity} is the application's and is never written. Presence multiplies it at draw time,
 * the way a displacement is added to a laid-out position, so the resting state is the single value 1 and an
 * application fading a node for its own reasons is undisturbed by the framework fading it in.
 *
 * <p>An arriving node is hit where layout put it, like any other: it is already where it belongs, and a user who
 * reaches it before it has finished appearing has found it.
 *
 * <p>Both reads are made on the GUI thread, inside the frame, once per node per frame while anything is arriving or
 * leaving; a source driven from another thread publishes its values safely, as for {@link LayoutMotion}.
 */
public interface EnterMotion {

    /** Nothing fades in: every node is fully present the frame it is added. The default. */
    EnterMotion NONE = new EnterMotion() {

        @Override
        public void entered(RetainedNode node) {
            // nothing arrives gradually, so nothing needs telling
        }

        @Override
        public float presence(RetainedNode node) {
            return 1f;
        }

        @Override
        public float rise(RetainedNode node) {
            return 0f;
        }

        @Override
        public String toString() {
            return "EnterMotion.NONE";
        }
    };

    /**
     * {@code node} has just joined the tree — see the class note for what counts. Called on the GUI thread as the
     * edit is applied, before the frame draws, so a source that starts an arrival here has the node at its starting
     * presence in the very frame it appears. A node coming back from leaving is drawn at {@link RetainedNode#presence}
     * now, and an arrival that starts from there is continuous.
     */
    void entered(RetainedNode node);

    /** How much of {@code node} is drawn, in [0, 1]: 1 once it has arrived, and for every node never reported. */
    float presence(RetainedNode node);

    /**
     * How far below where layout put it {@code node} is drawn while it arrives, px; 0 once it has arrived. Drawn
     * only, like {@code Node.translate}: the node is laid out and hit where it belongs.
     */
    float rise(RetainedNode node);

    /**
     * {@code node}, already {@link #entered}, arrives in place of what its parent held, which is fading out where it
     * stood ({@link ExitMotion#replaced}). Two pages seen through each other are a smear, so a source may hold the
     * arrival until the departure has gone. GUI thread, in the frame it arrived. Nothing by default.
     */
    default void replacing(RetainedNode node) {
    }
}
