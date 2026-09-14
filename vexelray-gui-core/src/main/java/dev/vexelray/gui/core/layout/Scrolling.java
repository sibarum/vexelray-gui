package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.RetainedNode;

/**
 * Bringing a node into view — the whole of what {@code Node.scrollIntoView} does, and nothing else.
 *
 * <p>Pure apart from the offsets it writes: every answer is a function of the laid-out rectangles it is handed.
 * It solves the nested case, which is the only part that is not obvious: scrolling an inner container moves the
 * target within it, so an outer scroller must be asked about where the target has just ended up rather than
 * where it was laid out. Innermost first, carrying the offset applied, or nested scrollers each solve the
 * problem the other just changed.
 *
 * <p>A declared model writer, for the two fields it exists to move: {@code scrollX} and {@code scrollY}.
 */
public final class Scrolling {

    private Scrolling() {
    }

    /**
     * Bring {@code target} inside the viewport of every ancestor that scrolls, by the least scrolling that does
     * it; @return whether any of them actually moved.
     *
     * <p>Innermost first, carrying the offset it applied: scrolling the inner container moves the target within
     * it, so an outer scroller has to be asked about where the target has just ended up rather than where it was
     * laid out. Without that, nested scrollers each solve the problem the other just changed.
     *
     * <p>Only containers that actually overflow are asked. One that fits its content has no offset to give, and
     * "reveal" on a page that does not scroll is not an error — it is a request that is already satisfied.
     */
    public static boolean reveal(RetainedNode target) {
        if (target == null || !target.visible() || target.parent == null) {
            return false;   // never in the tree, or gone from it since the ask
        }
        boolean moved = false;
        float x = target.x;
        float y = target.y;
        for (RetainedNode a = target.parent; a != null; a = a.parent) {
            if (!a.visible()) {
                return moved;   // an ancestor is hidden: its geometry is stale, and nothing under it is on screen
            }
            if (a.overflowY) {
                float applied = scrollBy(a, scrollDelta(y, target.h, a.viewY, a.viewH), true);
                y -= applied;
                moved |= applied != 0f;
            }
            if (a.overflowX) {
                float applied = scrollBy(a, scrollDelta(x, target.w, a.viewX, a.viewW), false);
                x -= applied;
                moved |= applied != 0f;
            }
        }
        return moved;
    }

    /**
     * The least scroll that puts {@code [pos, pos + size]} inside {@code [view, view + extent]}: negative to come
     * back, positive to go on, zero when it is already there.
     *
     * <p>The leading edge wins the tie. Going forward never scrolls past the point where the target's own top (or
     * left) reaches the edge of the viewport, which is what stops a row taller than the viewport from being
     * scrolled to its bottom — the top of something too big to see is the part that says what it is.
     */
    private static float scrollDelta(float pos, float size, float view, float extent) {
        float lead = pos - view;
        float trail = (pos + size) - (view + extent);
        if (lead < 0f) {
            return lead;
        }
        return trail > 0f ? Math.min(lead, trail) : 0f;
    }

    /** Move {@code n}'s scroll offset by {@code delta}, clamped to its content; @return what it actually moved. */
    private static float scrollBy(RetainedNode n, float delta, boolean vertical) {
        if (delta == 0f) {
            return 0f;
        }
        float was = vertical ? n.scrollY : n.scrollX;
        float max = vertical ? Math.max(0f, n.contentH - n.viewH) : Math.max(0f, n.contentW - n.viewW);
        float now = Math.max(0f, Math.min(was + delta, max));
        if (vertical) {
            n.scrollY = now;
        } else {
            n.scrollX = now;
        }
        return now - was;
    }
}
