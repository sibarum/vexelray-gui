package dev.vexelray.gui.core.model;

/**
 * The typed-by-convention property keys a node carries. Values are stored untyped in a {@code RetainedNode}'s
 * prop map and read back through {@link RetainedNode}'s typed accessors. {@code layoutAffecting} marks the props
 * whose change must retrigger layout (as opposed to purely visual props like colour).
 */
public enum PropKey {
    // Visual
    BACKGROUND(false),
    CORNER(false),
    // Bottom corner radius, when it differs from CORNER (which then serves as the top): a tab is (r, 0).
    // Absent means "same as CORNER" — the uniform rounded rect stays the one-prop case.
    CORNER_BOTTOM(false),
    BORDER_COLOR(false),
    TEXT_COLOR(false),
    // Depth & light (renderer-only; neither moves a rect). ELEVATION is a Length — a drop shadow of that blur
    // radius under the node's background. LIT modulates the background fill with an SDF edge light + vertical
    // luminance gradient in the engine's uber-shader; the node's colour props are unchanged.
    ELEVATION(false),
    LIT(false),
    // Sunken ("letterpress") text: glyphs drop a soft shadow below themselves and carry a sharp black outline,
    // reading as set into the surface. Renderer-only — the glyph rects are unchanged, so nothing reflows.
    TEXT_SUNKEN(false),
    // Text (size affects layout via intrinsic measure)
    TEXT(true),
    TEXT_SIZE(true),
    H_ALIGN(false),
    V_ALIGN(false),
    // Text editing (visual/state only — caret position and blink don't reflow the line):
    EDITABLE(false),
    CARET(false),        // caret offset into the text (Integer), or absent/-1 for no caret
    CARET_ON(false),     // caret blink phase: true = currently shown
    SELECT_START(false), // selection range start (Integer char offset); == SELECT_END means no selection
    SELECT_END(false),   // selection range end (Integer char offset)
    SPANS(false),        // formatting spans (List<Span>): fg/bg/underline over character ranges
    // Multiline (§11): both change how the text breaks into visual lines, so both reflow.
    MULTILINE(true),     // Enter inserts '\n' instead of submitting; the field scrolls vertically
    FONT(true),          // atlas face index (Integer): 0 = primary/UI, 1+ = extra faces (e.g. monospace)
    WORD_WRAP(true),     // wrap long lines at the content width instead of scrolling horizontally
    LINE_NUMBERS(true),  // a gutter of hard-line numbers, which narrows the text area and so reflows the wrap
    // Layout (border-box: border + padding inset the content, so border width is layout-affecting)
    DIRECTION(true),
    JUSTIFY(true),
    ALIGN_ITEMS(true),
    WIDTH(true),
    HEIGHT(true),
    PADDING(true),
    PADDING_X(true),
    PADDING_Y(true),
    MARGIN(true),
    BORDER_WIDTH(true),
    GAP(true),
    // Per-axis overflow scrolling: enabled by default (auto scrollbars on overflow); set false to disable an axis.
    SCROLL_X(true),
    SCROLL_Y(true),
    // Scroll-edge lock (LayoutEnums.ScrollLock): pins the offset to top/bottom while attached (log tailing).
    SCROLL_LOCK(true),
    /**
     * Out-of-flow placement (both Lengths, or absent for a normal in-flow child). A floating node does not take
     * part in its parent's flex distribution, measurement or overflow — it is sized to its own props/content and
     * placed at these offsets from the parent's border-box origin, clamped so it stays inside the parent. It is
     * still an ordinary child for painting and hit-testing, and children paint in order, so a floating node
     * appended last draws on top of and is hit before its siblings: that is the framework's overlay primitive
     * (context menus, tooltips, toasts), with no second tree and no z-order bookkeeping.
     */
    FLOAT_X(true),
    FLOAT_Y(true),
    /**
     * Pointer transparency: a hit-inert node (and its whole subtree) is never a pointer target — hit-testing
     * passes straight through it to whatever lies beneath. This is what lets an overlay be informational rather
     * than interactive: a tooltip floats over a button without ever becoming the thing under the pointer, so
     * hovering it changes nothing (the rule that chrome must never alter the pointer target on hover). Purely a
     * dispatch fact — the node still lays out and draws exactly as before.
     */
    HIT_INERT(false),
    /**
     * Whether the node and its subtree take part at all. A hidden node is skipped by layout, by the renderer and
     * by hit-testing, but keeps its identity and everything attached to it -- handlers, claims, focusability,
     * widget state. That is the difference from removing it: removal releases those (they are keyed by node id
     * and the node is gone), so a page rebuilt by remove/insert would come back inert. Anything that shows one
     * of several children at a time needs this rather than structural churn.
     */
    VISIBLE(true);

    private final boolean layoutAffecting;

    PropKey(boolean layoutAffecting) {
        this.layoutAffecting = layoutAffecting;
    }

    public boolean layoutAffecting() {
        return layoutAffecting;
    }

    /**
     * Whether a change to this prop invalidates <b>derived geometry</b> (docs/layout-read-model.md §2.1–2.3) — so
     * the compute phase must re-run and the read-model republish, even when the flex layout itself is unchanged.
     * Every layout-affecting prop qualifies. {@link #CARET} additionally does, because caret-follow scroll (and
     * therefore the baked caret x positions) is a function of it: moving the caret with an arrow key reflows
     * nothing, but it does move the view.
     */
    public boolean geometryAffecting() {
        return layoutAffecting || this == CARET;
    }
}
