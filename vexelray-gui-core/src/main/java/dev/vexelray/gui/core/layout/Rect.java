package dev.vexelray.gui.core.layout;

/** An immutable axis-aligned rectangle in root (viewport) space, in pixels. Part of the layout read-model. */
public record Rect(float x, float y, float w, float h) {

    public static final Rect ZERO = new Rect(0f, 0f, 0f, 0f);

    /** Whether {@code (px, py)} lies within this rectangle (left/top inclusive, right/bottom exclusive). */
    public boolean contains(float px, float py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    /** Whether this rectangle encloses no area: nothing is drawn in it, and nothing can be clicked in it. */
    public boolean empty() {
        return w <= 0f || h <= 0f;
    }

    /** The centre — the one point that is inside a rectangle for every rectangle that is not {@link #empty}. */
    public float centreX() {
        return x + w * 0.5f;
    }

    public float centreY() {
        return y + h * 0.5f;
    }
}
