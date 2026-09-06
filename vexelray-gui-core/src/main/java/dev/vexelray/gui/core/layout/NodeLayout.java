package dev.vexelray.gui.core.layout;

/**
 * One node's computed layout, as published in a {@link LayoutSnapshot} (docs/layout-read-model.md). Pure,
 * immutable, transport-serializable data — no reference to the live retained node or to any measurer — so a
 * widget (or a test, a devtools overlay, a remote client) reads a node's geometry without reaching into core's
 * single-writer model. Coordinates are pixels in root space.
 *
 * <p>{@link #present} is {@code false} for {@link #ABSENT}, returned when a node has not been laid out yet (or is
 * not in the current snapshot).
 *
 * <p>Text-specific metrics (caret geometry, line boxes) are added in a later migration step as a nullable field;
 * this step carries boxes, scroll and overflow only.
 *
 * <p><b>The corner radii are here because a drawing needs them.</b> They are the one piece of a node's shape that
 * is not a rectangle, and everything that authors marks in a node's own box — a {@code Picture}, and above all an
 * overlay that has to hug the box it decorates — would otherwise have to draw square corners over a rounded card
 * and hope. Resolved to px by the layout pass like every other length, so a reader needs no units and no context.
 *
 * <p><b>{@link #visibleRect} is where a node actually is.</b> {@link #rect} says where a node was <em>put</em>,
 * which is not the same thing the moment an ancestor scrolls: a row scrolled out of its list still has a rect,
 * and it is a rect nobody can see or click. The two were the same number until virtualisation made "laid out"
 * and "on screen" routinely disagree, and a reader with only {@code rect} has no way to tell — it reads a
 * position for a node that is behind a sticky header and aims there. Clipping is geometry, so it is answered
 * here rather than in the semantic snapshot, and it is computed by the same rule {@code HitTest} descends by,
 * which is what makes "the centre of {@code visibleRect}" a point that really does address this node.
 */
public record NodeLayout(
        boolean present,
        Rect rect,
        Rect content,
        Rect visibleRect,
        float cornerTopPx,
        float cornerBottomPx,
        float scrollX,
        float scrollY,
        float contentW,
        float contentH,
        boolean overflowX,
        boolean overflowY,
        float textSizePx,
        dev.vexelray.gui.core.text.TextMetrics text) {

    /** The value returned for a node that has no computed layout yet. */
    public static final NodeLayout ABSENT =
            new NodeLayout(false, Rect.ZERO, Rect.ZERO, Rect.ZERO, 0f, 0f, 0f, 0f, 0f, 0f, false, false, 0f, null);

    /** Caret geometry for a text node (line boxes + per-boundary x), or {@code null} for a non-text node. */
    public dev.vexelray.gui.core.text.TextMetrics text() {
        return text;
    }

    /** The content viewport size (visible area inside border/padding/scrollbars). */
    public float viewW() {
        return content.w();
    }

    public float viewH() {
        return content.h();
    }

    /**
     * How much of an edge has to be missing before this node counts as clipped: half a pixel, because less
     * than that is not something a clip did.
     *
     * <p>A child's edge and the edge of the box holding it are computed by different sums that are equal in
     * exact arithmetic and differ in the last bit of a {@code float} — so an intersection that takes nothing
     * off still comes back a few millionths of a pixel short. Comparing exactly reports almost every node in a
     * laid-out tree as clipped, which is a flag that means nothing and would be read as one that means
     * something.
     */
    private static final float CLIP_EPS = 0.5f;

    /** Whether an ancestor's clip takes anything off this node — a partly scrolled row says {@code true}. */
    public boolean clipped() {
        return Math.abs(visibleRect.x() - rect.x()) > CLIP_EPS
                || Math.abs(visibleRect.y() - rect.y()) > CLIP_EPS
                || Math.abs(visibleRect.w() - rect.w()) > CLIP_EPS
                || Math.abs(visibleRect.h() - rect.h()) > CLIP_EPS;
    }

    /**
     * Whether none of this node survives its ancestors' clips: laid out, and nowhere on screen.
     *
     * <p>The state a virtualised list is full of — a realized row scrolled past the top of its viewport — and
     * the one a reader must not resolve to a point, because the point it would compute is over whatever is
     * drawn there instead.
     */
    public boolean clippedAway() {
        return visibleRect.empty();
    }
}
