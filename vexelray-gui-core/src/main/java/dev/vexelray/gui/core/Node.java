package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.SampledImage;

/**
 * A write-only, identity-stable handle to a node — safe to hold and call from any thread. Every setter enqueues a
 * mutation on the shared {@link MutationSink}; the GUI thread applies them to the retained model. Editing a node
 * never mints a new identity, so state stays attached (architecture.md §3). Fluent: setters return {@code this}.
 */
public final class Node {

    private final long id;
    private final MutationSink sink;
    private final LayoutReader reads;

    Node(long id, MutationSink sink) {
        this(id, sink, null);
    }

    Node(long id, MutationSink sink, LayoutReader reads) {
        this.id = id;
        this.sink = sink;
        this.reads = reads;
    }

    public long id() {
        return id;
    }

    /**
     * This node's computed layout from the latest published snapshot (docs/layout-read-model.md) — position,
     * size, scroll and overflow. Synchronous and lock-free, and <b>one frame stale</b> (the latency the framework
     * already accepts for input); returns {@link NodeLayout#ABSENT} before the node's first layout. This is a read
     * of a published snapshot, not a live poke into the model — the handle stays write-only for mutations.
     */
    public NodeLayout layout() {
        return reads == null ? NodeLayout.ABSENT : reads.snapshot().node(id);
    }

    // --- visual ---

    public Node background(Color c) {
        return prop(PropKey.BACKGROUND, c);
    }

    /**
     * Draw {@code image} across this node's box — a picture, an icon, or a <b>viewport</b> showing a scene another
     * pipeline rendered into a target this frame. Pass null to clear it.
     *
     * <p>The node stays an ordinary box: it sizes by flex like any other, and its corner radius, border, clip and
     * subtree transforms apply to the image unchanged. That is the whole of what makes a viewport cheap here —
     * there is no viewport node, because a box that samples already is one. The image is drawn between the
     * background and the border, so a background shows through anything transparent and a border frames it.
     *
     * <p>Thread-safe like every setter: the handle is posted as a mutation and applied on the GUI thread. The
     * image itself must stay valid until a frame that no longer names it has been presented.
     */
    public Node image(SampledImage image) {
        return prop(PropKey.IMAGE, image);
    }

    /**
     * Draw {@code picture} inside this node's box — a plot, a diagram, a sparkline. Marks are in pixels with
     * {@code (0, 0)} at the box's top-left corner, so a drawing is authored against the box's own size and knows
     * nothing about where on screen the box ended up. Pass null to clear it.
     *
     * <p>The node stays an ordinary box, exactly as with {@link #image}: it sizes by flex, and its border, clip
     * and subtree transforms apply to the drawing unchanged. Nothing here is laid out or hit-tested mark by mark
     * — a picture is one value, and replacing it is one prop write rather than a subtree of reconciled nodes.
     * That is what makes a picture the right size of thing for a plot: hundreds of marks, none of which wants
     * layout, all of which change together whenever the window does.
     *
     * <p>The drawing is <b>clipped to the box</b>, and it is in pixels rather than {@code Length}s — see
     * {@link PropKey#PICTURE} for why both of those are so.
     */
    public Node picture(Picture picture) {
        return prop(PropKey.PICTURE, picture);
    }

    public Node corner(Length radius) {
        return prop(PropKey.CORNER, radius);
    }

    /** Independent top and bottom corner radii — a tab is {@code corner(r, Length.ZERO)}. */
    public Node corner(Length top, Length bottom) {
        prop(PropKey.CORNER, top);
        return prop(PropKey.CORNER_BOTTOM, bottom);
    }

    /**
     * Select the atlas face this node's text measures and renders with: 0 (default) is the primary UI font,
     * 1+ are the extra faces the atlas was built with (e.g. a monospace face for code). An index the atlas
     * doesn't carry degrades to the primary font.
     */
    public Node font(int face) {
        return prop(PropKey.FONT, face);
    }

    public Node border(Length width, Color color) {
        prop(PropKey.BORDER_WIDTH, width);
        return prop(PropKey.BORDER_COLOR, color);
    }

    /**
     * Float this node above its surroundings: a soft drop shadow of this blur radius under its background.
     * Purely visual — the shadow reserves no space and moves no layout. {@code Length.ZERO} removes it.
     */
    public Node elevation(Length blur) {
        return prop(PropKey.ELEVATION, blur);
    }

    /**
     * Light this node's background: an embossed edge highlight from a fixed top-left light plus a subtle vertical
     * luminance gradient, both analytic functions of the background's own SDF. The colour props are untouched —
     * the light modulates whatever {@link #background} is set to, so hover/press restyles keep working.
     */
    public Node lit(boolean lit) {
        return prop(PropKey.LIT, lit);
    }

    /**
     * Fade this node and its whole subtree: every colour the renderer emits for it is multiplied by
     * {@code opacity}, and a nested opacity multiplies again. 1 is opaque (the default), 0 draws nothing.
     *
     * <p><b>A visual transform, not a layout input</b> (architecture.md §7): fading a node moves nothing and
     * measures nothing, so it never reflows — that is what makes it cheap enough to drive every frame. It is also
     * why it is <em>only</em> visual: a fully faded node still occupies its box and, unless it is also
     * {@link #hitInert}, is still the thing under the pointer. Anything that fades something out from under the
     * user should say both.
     *
     * <p>The alpha applies per primitive rather than to the subtree as a composited group; see
     * {@link PropKey#OPACITY} for what that costs and why it is the right trade here.
     */
    public Node opacity(float opacity) {
        return prop(PropKey.OPACITY, Math.max(0f, Math.min(1f, opacity)));
    }

    /**
     * Draw this node and its subtree offset by {@code (emX, emY)} multiples of its own em, without moving it.
     *
     * <p>The other half of the visual-transform layer (architecture.md §7), and the same bargain as
     * {@link #opacity}: nothing reflows, nothing is measured, so it is cheap enough to drive every frame. In em
     * so that it scales with zoom and density like every other relative measure here — and because a
     * {@link Length} would have to be resolved by the layout pass, which does not re-run for a visual prop and so
     * would leave the animation working against a stale px value. Multiples of the em need only what the renderer
     * already has.
     *
     * <p><b>The node still lays out, reports, and is hit where it was.</b> Displacement is a fact about drawing,
     * so a translated node's {@link #layout()} rect and its pointer target stay where the layout put them. Pair
     * this with {@link #hitInert}(true) on anything the pointer could be over while it moves, and give the parent
     * {@link #clip}(true) unless it is meant to escape.
     */
    public Node translate(float emX, float emY) {
        prop(PropKey.TRANSLATE_X, emX == 0f ? null : emX);
        return prop(PropKey.TRANSLATE_Y, emY == 0f ? null : emY);
    }

    /**
     * Mask this node's children to its border box. A scrolling container already clips its viewport; say this
     * when a container that does not scroll still has an edge — most obviously one that something
     * {@link #translate}s through, since a translated child adds no overflow and so never triggers the automatic
     * kind.
     */
    public Node clip(boolean clip) {
        return prop(PropKey.CLIP, clip ? Boolean.TRUE : null);
    }

    // --- text ---

    public Node text(String s) {
        sink.post(new Mutation.SetText(id, s));
        return this;
    }

    public Node textSize(Length size) {
        return prop(PropKey.TEXT_SIZE, size);
    }

    public Node textColor(Color c) {
        return prop(PropKey.TEXT_COLOR, c);
    }

    /**
     * Draw this node's text sunken into the surface (letterpress): a soft shadow dropped below the glyphs and a
     * sharp black outline around them, for contrast on strong backgrounds (white on accent). Renderer-only —
     * glyph geometry is unchanged, so nothing reflows.
     */
    public Node textSunken(boolean sunken) {
        return prop(PropKey.TEXT_SUNKEN, sunken);
    }

    public Node align(TextLayout.HAlign h, TextLayout.VAlign v) {
        prop(PropKey.H_ALIGN, h);
        return prop(PropKey.V_ALIGN, v);
    }

    /** Mark this text node editable (a text field). Editing state (caret) is carried by {@link #caret}. */
    public Node editable(boolean editable) {
        return prop(PropKey.EDITABLE, editable);
    }

    /**
     * Let this text node hold multiple lines: Enter inserts a newline instead of submitting, and the node scrolls
     * vertically to keep the caret in view rather than horizontally.
     */
    public Node multiline(boolean multiline) {
        return prop(PropKey.MULTILINE, multiline);
    }

    /**
     * Wrap long lines at the content width instead of scrolling horizontally. Only meaningful with
     * {@link #multiline}; a wrapped node never scrolls horizontally.
     */
    public Node wordWrap(boolean wrap) {
        return prop(PropKey.WORD_WRAP, wrap);
    }

    /**
     * Show a gutter of hard-line numbers down the left edge. Numbers count {@code '\n'}s, not wrapped rows, so a
     * line that wraps onto three rows is numbered once. The gutter narrows the text area, and therefore the wrap.
     */
    public Node lineNumbers(boolean show) {
        return prop(PropKey.LINE_NUMBERS, show);
    }

    /** Set the caret offset into the text (character index), or {@code -1} to hide the caret. */
    public Node caret(int offset) {
        return prop(PropKey.CARET, offset);
    }

    /** Set the caret blink phase — {@code true} shows the caret this instant, {@code false} hides it. */
    public Node caretOn(boolean on) {
        return prop(PropKey.CARET_ON, on);
    }

    /** Set the selection range (character offsets); {@code start == end} clears the selection. */
    public Node selection(int start, int end) {
        prop(PropKey.SELECT_START, start);
        return prop(PropKey.SELECT_END, end);
    }

    /**
     * Set the formatting spans (fg/bg/underline over character ranges) drawn on this text node. Replaces the whole
     * set; pass an empty list to clear. The list is copied defensively. See {@link dev.vexelray.gui.core.text.Span}.
     */
    public Node spans(java.util.List<dev.vexelray.gui.core.text.Span> spans) {
        return prop(PropKey.SPANS, spans == null ? java.util.List.of() : java.util.List.copyOf(spans));
    }

    // --- layout ---

    public Node direction(Direction d) {
        return prop(PropKey.DIRECTION, d);
    }

    public Node justify(Justify j) {
        return prop(PropKey.JUSTIFY, j);
    }

    public Node alignItems(AlignItems a) {
        return prop(PropKey.ALIGN_ITEMS, a);
    }

    public Node width(Length w) {
        return prop(PropKey.WIDTH, w);
    }

    public Node height(Length h) {
        return prop(PropKey.HEIGHT, h);
    }

    public Node size(Length w, Length h) {
        width(w);
        return height(h);
    }

    public Node padding(Length p) {
        return prop(PropKey.PADDING, p);
    }

    /** CSS-style shorthand: {@code vertical} padding top+bottom, {@code horizontal} padding left+right. */
    public Node padding(Length vertical, Length horizontal) {
        prop(PropKey.PADDING_Y, vertical);
        return prop(PropKey.PADDING_X, horizontal);
    }

    public Node margin(Length m) {
        return prop(PropKey.MARGIN, m);
    }

    /**
     * Float this node out of its parent's flow, at {@code (x, y)} from the parent's border-box origin. A floating
     * node takes no space from its siblings and adds nothing to its parent's overflow; it is sized to its own
     * width/height (or its content), clamped to stay inside the parent, and — being an ordinary child for painting
     * and hit-testing — draws on top of and is hit before every sibling that precedes it. This is the overlay
     * primitive: a context menu, a tooltip, a toast is a floating last child of the root.
     */
    public Node floatAt(Length x, Length y) {
        prop(PropKey.FLOAT_X, x);
        return prop(PropKey.FLOAT_Y, y);
    }

    /** Return this node to normal flow. */
    public Node unfloat() {
        prop(PropKey.FLOAT_X, null);
        return prop(PropKey.FLOAT_Y, null);
    }

    /**
     * Make this node (and its subtree) pointer-transparent: it still lays out and draws, but hit-testing passes
     * straight through it, so it is never the pointer target and never steals a hover, click or wheel from what
     * lies beneath. An informational overlay — a tooltip, a drag ghost, a toast — is hit-inert; a menu is not.
     */
    public Node hitInert(boolean inert) {
        return prop(PropKey.HIT_INERT, inert ? Boolean.TRUE : null);
    }


    /**
     * Declare what this node is to the window manager — the one thing a GUI drawing its own window chrome has to
     * say. {@link WindowRegion#DRAG} makes the node a title bar (drag to move, double-click to maximize,
     * right-click for the system menu); a control drawn on top of one must declare
     * {@link WindowRegion#INTERACTIVE} or the window manager takes its clicks. Pass {@code null} to clear.
     *
     * <p>Affects nothing else: the node lays out, draws and hit-tests exactly as it did. Only a window created
     * with client decorations consults these at all.
     */
    public Node windowRegion(WindowRegion region) {
        return prop(PropKey.WINDOW_REGION, region);
    }

    public Node gap(Length g) {
        return prop(PropKey.GAP, g);
    }

    /**
     * Show or hide this node and its subtree. A hidden node is skipped by layout, drawing and hit-testing while
     * keeping its identity and everything attached to it -- handlers, focus registrations, widget state -- so it
     * comes back exactly as it was. Removing it instead would release those, since they are keyed by node id.
     */
    public Node visible(boolean visible) {
        return prop(PropKey.VISIBLE, visible);
    }

    /** Enable/disable overflow scrolling per axis (both default on — auto scrollbars appear on overflow). */
    public Node scroll(boolean allowX, boolean allowY) {
        prop(PropKey.SCROLL_X, allowX);
        return prop(PropKey.SCROLL_Y, allowY);
    }

    /**
     * Lock the vertical scroll to an edge (§8.5): {@link LayoutEnums.ScrollLock#BOTTOM} tails the content (stays
     * pinned to the bottom as it grows, e.g. a log) and {@code TOP} pins to the top; {@code NONE} scrolls freely.
     * While locked the container detaches when the user scrolls away from the edge and re-attaches on return.
     */
    public Node scrollLock(LayoutEnums.ScrollLock lock) {
        return prop(PropKey.SCROLL_LOCK, lock);
    }

    /**
     * Send a {@link #scrollLock}ed container back to its locked edge and re-attach it, so it resumes following
     * the content as it grows.
     *
     * <p>{@link #scrollLock} already detaches when the user scrolls away from the edge and re-attaches when they
     * scroll back onto it — that much needs no help, and taking it away from them would be the wrong fix. This is
     * for the other way a reader signals they are done looking at history: by doing something. A shell where
     * pressing Enter does not bring you back to the prompt is a shell that hides its own output, so the command
     * that appends says so here, and the scroller catches up in the same frame the output lands.
     *
     * <p>A no-op on a container with no scroll lock: there is no edge to be at.
     */
    public Node scrollToEdge() {
        sink.post(new Mutation.ScrollToEdge(id));
        return this;
    }

    /**
     * Ask every scrolling ancestor to bring this node inside its viewport, by the least scrolling that does it.
     *
     * <p><b>Least, and leading-edge first.</b> A node just past the bottom edge comes up by exactly the shortfall
     * rather than jumping to the middle: the rows around it stay where the eye left them, which is what makes a
     * selection walked with the arrow keys readable. A node taller than the viewport aligns its top, because the
     * top of something too big to see is the part that says what it is. A node already in view moves nothing.
     *
     * <p><b>Answered this frame, not the next.</b> The request queues with the edits around it and is resolved
     * after the layout that places this node — so revealing a row that the same handler just expanded a subtree to
     * produce works, and there is no frame in which the selection is somewhere the scroller is not.
     *
     * <p>A no-op on a node no ancestor can scroll to (nothing overflows), on a hidden one, and on one that has
     * left the tree by the time the frame answers.
     */
    public Node scrollIntoView() {
        sink.post(new Mutation.Reveal(id));
        return this;
    }

    // --- structure ---

    public Node append(Node child) {
        sink.post(new Mutation.Insert(id, child.id, Mutation.Insert.END));
        return this;
    }

    public Node children(Node... kids) {
        for (Node k : kids) {
            append(k);
        }
        return this;
    }

    public void remove() {
        sink.post(new Mutation.Remove(id));
    }

    private Node prop(PropKey key, Object value) {
        sink.post(new Mutation.SetProp(id, key, value));
        return this;
    }
}
