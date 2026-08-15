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
 */
public record NodeLayout(
        boolean present,
        Rect rect,
        Rect content,
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
            new NodeLayout(false, Rect.ZERO, Rect.ZERO, 0f, 0f, 0f, 0f, false, false, 0f, null);

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
}
