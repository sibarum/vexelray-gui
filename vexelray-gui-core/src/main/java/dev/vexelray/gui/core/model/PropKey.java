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
    /**
     * A sampled image drawn across this node's border-box: a decoded picture, an icon, or a <b>viewport</b> — a
     * scene another pipeline marched into a render target this frame. The value is the opaque handle the renderer
     * hands to {@code Canvas.image}; in practice a {@code SampledImage}, and anything else draws as a blank box
     * rather than failing the frame.
     *
     * <p><b>Not a node kind, and not layout-affecting.</b> A viewport is a box that samples, so it takes its size
     * from the ordinary box it already is — width, height, flex, corner radius, border, clip and the subtree
     * transforms all apply unchanged, and putting a scene on a node moves nothing. It draws between the
     * background and the border, so a background shows through anything transparent in it and a border frames it.
     */
    IMAGE(false),
    /**
     * A {@code Picture} drawn inside this node's box: an ordered list of marks in the box's own pixel frame, with
     * {@code (0, 0)} at its top-left corner. A plot, a diagram, a sparkline — anything an application wants to put
     * marks on the screen for, without one laid-out node per mark.
     *
     * <p><b>Not a node kind, and not layout-affecting</b>, for the same reason {@link #IMAGE} is neither: a
     * drawing is a box that paints, so it takes its size from the box it already is, and putting one on a node
     * moves nothing. It draws over the image and under the border, <b>clipped to the box</b> — an application
     * authors geometry in whatever numbers it computed, and there is no measure pass to catch a mark that
     * overshoots, so here the clip is the guarantee rather than an assertion about it.
     *
     * <p><b>In pixels, and so rebuilt on a zoom.</b> The renderer resolves no units of its own — every length a
     * node carries was resolved by the layout pass — and a picture cannot be scaled here without this class
     * becoming the one place that does. A picture is authored for the box that was measured, exactly as a typeset
     * block re-projects when its basis changes.
     */
    PICTURE(false),
    /**
     * A {@code Picture} drawn <b>over</b> this node and its whole subtree — the same value {@link #PICTURE} is,
     * emitted at the other end of the node's paint. The decoration slot: a sweep across a field that just took a
     * command, a wash over a panel that was written to from somewhere else, a ring around one that rejected what
     * it was given.
     *
     * <p><b>Why a second slot rather than reusing {@link #PICTURE}.</b> The two are different in kind, and the
     * difference is exactly where they paint. A picture is <em>content</em>: it goes under the border, because
     * an application's own marks belong inside the frame the node draws around them, and it is the application's
     * — a plot lives there. An overlay is <em>about</em> the node: it goes over the border, the text and the
     * children, because a thing that says "this just happened to this box" that a label can cover has failed at
     * the one job it has. Writing a decoration into {@link #PICTURE} would both mispaint it and evict the plot.
     *
     * <p><b>The third visual transform</b> (architecture.md §7), alongside {@link #OPACITY} and
     * {@link #TRANSLATE_X}. Same bargain as those two: not layout-affecting, so nothing reflows and nothing is
     * measured; never hit-tested, because a picture is not a node and there is nothing there to hit; and — the
     * property that makes it a member of that layer rather than merely a prop — <b>its identity value is
     * {@code null}</b>, so whatever put one on can be taken off again without anyone being told what was there
     * before. That is what lets a one-shot cue be played on a node the framework does not own.
     *
     * <p>Clipped to the border box and travelling with the node's own displacement, exactly as {@link #PICTURE}
     * is, and in the same pixel frame: {@code (0, 0)} is the box's top-left corner. The box's corner radii are
     * published on {@link dev.vexelray.gui.core.layout.NodeLayout}, so an overlay can hug the shape it decorates.
     */
    OVERLAY(false),
    /**
     * Subtree opacity: this node and everything under it draw at {@code own x inherited}, multiplied into every
     * colour the renderer emits. The first of the visual-transform properties of architecture.md §7, and
     * deliberately not layout-affecting — fading a page moves nothing, so nothing reflows and no measurement
     * runs. Absent (or 1) is fully opaque; 0 draws nothing and the subtree is skipped, but it still lays out, so
     * the box it fades back into is already the right shape.
     *
     * <p><b>Per-primitive alpha, not group opacity.</b> Siblings that overlap inside a half-faded subtree
     * composite against each other rather than against what lies behind the group. The exact version needs an
     * offscreen layer and a second composite — a render target this GUI does not have and does not want for a
     * 160ms crossfade, where the two states that must be right are the endpoints and both are exact.
     */
    OPACITY(false),
    /**
     * Subtree displacement, in multiples of the node's own em — the second visual transform of architecture.md
     * §7, and like {@link #OPACITY} not a layout input: the node is <em>drawn</em> somewhere else and reflows
     * nothing, so a page can travel without a single measurement running.
     *
     * <p>In em rather than a {@link dev.vexelray.gui.core.layout.Length} because of <em>when</em> it has to
     * resolve. Lengths are resolved to px by the layout pass, and layout deliberately does not re-run for a
     * purely visual prop — so a {@code Length} here would animate against a px value baked whenever layout last
     * happened to run, which is to say never, in a UI that is otherwise still. An em multiple needs only the
     * node's own baked {@code emPx}, which the renderer already has and already scales by (the lit bevel and the
     * caret width are both fractions of it), so it honours zoom and density with no context to thread and no
     * layout to trigger.
     *
     * <p>Hit-testing does <b>not</b> follow: the tree still reports, and is still hit at, the box layout gave it.
     * That is the honest reading of a transform layer, and it means anything moving something the pointer could
     * be over should pair this with {@link #HIT_INERT} rather than let the two disagree.
     */
    TRANSLATE_X(false),
    TRANSLATE_Y(false),
    /**
     * Clip this node's children to its own border box. Overflow scrolling already clips its viewport; this is the
     * same masking asked for outright, by a container that does not scroll and has no overflow to detect —
     * because what escapes it is a {@link #TRANSLATE_X translated} child, which by design adds no overflow and so
     * can never trigger the scrolling kind. A container that pages slide through needs to say where it ends.
     */
    CLIP(false),
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
    VISIBLE(true),
    /**
     * What this node is to the <b>window manager</b> ({@link dev.vexelray.gui.core.WindowRegion}) — a title bar
     * to drag the window by, a hole in one for a control drawn on it, or the maximize button. Only meaningful in
     * a window whose chrome the application draws; the host reads these off the laid-out tree each frame and
     * publishes the rectangles to the OS. Changes nothing about layout, drawing or hit-testing: the node is
     * ordinary UI, and this is a fact stated about it.
     */
    WINDOW_REGION(false);

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
