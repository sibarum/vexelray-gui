package dev.vexelray.gui.core.input;

/**
 * A drag on a node that registered via {@code Gui.onDrag}. A left press on the node (or a descendant) captures the
 * pointer, so {@link Phase#MOVE} events keep arriving while the button is held even if the pointer leaves the node,
 * until {@link Phase#END} on release. Carries the pointer position and the captured node's border-box rect at the
 * event, so a handler can compute a fraction without touching the retained tree (it runs off the GUI thread).
 *
 * @param phase START on capture, MOVE while dragging, END on release
 * @param x     pointer client-space x, px
 * @param y     pointer client-space y, px
 * @param nodeX captured node's border-box x, px
 * @param nodeY captured node's border-box y, px
 * @param nodeW captured node's border-box width, px
 * @param nodeH captured node's border-box height, px
 */
public record DragEvent(Phase phase, float x, float y, float nodeX, float nodeY, float nodeW, float nodeH) {

    public enum Phase { START, MOVE, END }

    /** Pointer position along the node's width as a 0..1 fraction (clamped). */
    public float fractionX() {
        return nodeW <= 0f ? 0f : clamp01((x - nodeX) / nodeW);
    }

    /** Pointer position along the node's height as a 0..1 fraction (clamped). */
    public float fractionY() {
        return nodeH <= 0f ? 0f : clamp01((y - nodeY) / nodeH);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
