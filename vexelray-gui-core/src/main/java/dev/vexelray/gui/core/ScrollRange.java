package dev.vexelray.gui.core;

/**
 * One axis of something looked at a part at a time: how long it is, how much of it can be seen, and where the view
 * starts. In px, with {@code 0 <= offset <= content - view} and {@code content >= view}.
 *
 * <p>This is all a scrollbar shows, which is the point of it being a type: a bar reads a range and asks its
 * {@link ScrollSource} to move, and neither knows whether what moves is a {@link Scroller}'s child, a 3D view's
 * camera, or a terminal's scrollback (docs/plans/scrolling.md §3).
 *
 * <p>Virtualisation reads it too: {@link #view} is how much a list can show, the question every list asks to know
 * how many rows to realize.
 */
public record ScrollRange(float content, float view, float offset) {

    /** Nothing to look at, and nothing to scroll: what a source publishes before it has been laid out. */
    public static final ScrollRange NONE = new ScrollRange(0f, 0f, 0f);

    /** How far the view can travel: the largest {@link #offset}. */
    public float max() {
        return Math.max(0f, content - view);
    }

    /** Whether there is anything to scroll: more content than view, by more than half a pixel. */
    public boolean scrolls() {
        return content - view > 0.5f;
    }
}
