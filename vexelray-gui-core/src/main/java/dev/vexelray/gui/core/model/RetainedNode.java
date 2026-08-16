package dev.vexelray.gui.core.model;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The live model node — GUI-thread-only, mutated exclusively by the {@link Reconciler}. Identity is the stable
 * {@code id} (the {@code Node} handle shares it). Props live in an untyped map keyed by {@link PropKey} and are
 * read through the typed accessors below; {@link #x}/{@link #y}/{@link #w}/{@link #h} are the layout-computed
 * rect (screen px), filled by the flex layout each dirty frame.
 */
public final class RetainedNode {

    /** Default text size when none is set: one root em (≈16px at the default context). */
    private static final Length DEFAULT_TEXT_SIZE = Length.rem(1f);

    public final long id;
    public final NodeKind kind;
    private final Map<PropKey, Object> props = new EnumMap<>(PropKey.class);
    public final List<RetainedNode> children = new ArrayList<>();
    public RetainedNode parent;

    // Layout-computed border-box rect, absolute screen px (Y-down). w/h include border + padding (border-box).
    public float x;
    public float y;
    public float w;
    public float h;

    // Layout-computed, resolved-to-px render inputs (filled each layout pass so the renderer needs no units/ctx).
    public float borderPx;
    public float cornerPx;
    public float textSizePx = 16f;

    // Scroll state: scrollX/Y persist across frames. A staged pipeline value (docs/layout-read-model.md §2.2) —
    // the dispatch stage proposes an offset (wheel, scrollbar drag), the compute stage narrows it (caret-follow,
    // clamp to content, scroll-lock), and everything downstream only reads. The rest is layout-computed: whether
    // each axis overflows, the clipped content viewport, and the full content size.
    public float scrollX;
    public float scrollY;
    public boolean overflowX;
    public boolean overflowY;
    public float viewX;
    public float viewY;
    public float viewW;
    public float viewH;
    public float contentW;
    public float contentH;
    public float scrollbarPx;
    // Scroll-lock (§8.5) runtime state: whether the offset is currently pinned to the locked edge. Starts
    // attached so a freshly-built locked scroller opens at its edge; the dispatcher detaches/re-attaches it
    // as the user scrolls away from and back onto the edge.
    public boolean scrollAttached = true;

    // Derived geometry for a text node: the caret/line metrics the compute phase bakes each changed frame, which
    // publish then copies into the read-model verbatim (docs/layout-read-model.md §2.1). Null for a non-text node,
    // for empty text, or when the measurer has no glyph metrics. Never serialized from here — it is model-side
    // scratch, and the snapshot's copy is the transport-visible one.
    public dev.vexelray.gui.core.text.TextMetrics textMetrics;

    // Where this node's text breaks into visual lines, at the width the layout settled on. Computed once, by the
    // layout pass, and read by everything downstream — the compute phase bakes caret geometry from it and never
    // re-breaks the text. Beyond halving the work, it removes the possibility of two stages disagreeing about the
    // wrap width, which is a bug this codebase has now shipped twice. Safe to hold across a geometry-only frame:
    // line breaks depend only on text, width and text size, none of which can change without a relayout.
    public java.util.List<dev.vexelray.text.TextLayout.LineSpan> lineSpans;

    // The caret offset the view last scrolled to follow. Caret-follow runs only when the caret has *moved*, so a
    // user who scrolls the field away from the caret (wheel, dragging the scrollbar) keeps their position instead
    // of being snapped back every frame. Clamping still runs unconditionally.
    public int caretFollowed = Integer.MIN_VALUE;

    public RetainedNode(long id, NodeKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public void set(PropKey key, Object value) {
        if (value == null) {
            props.remove(key);
        } else {
            props.put(key, value);
        }
    }

    public Object raw(PropKey key) {
        return props.get(key);
    }

    // --- typed accessors with defaults ---

    public Color background() {
        return (Color) props.get(PropKey.BACKGROUND);
    }

    public Length corner() {
        return len(PropKey.CORNER, Length.ZERO);
    }

    public Length borderWidth() {
        return len(PropKey.BORDER_WIDTH, Length.ZERO);
    }

    public Color borderColor() {
        return (Color) props.get(PropKey.BORDER_COLOR);
    }

    public String textString() {
        return (String) props.get(PropKey.TEXT);
    }

    public Length textSize() {
        return len(PropKey.TEXT_SIZE, DEFAULT_TEXT_SIZE);
    }

    public Color textColor() {
        Object c = props.get(PropKey.TEXT_COLOR);
        return c != null ? (Color) c : Color.WHITE;
    }

    public TextLayout.HAlign hAlign() {
        Object a = props.get(PropKey.H_ALIGN);
        return a != null ? (TextLayout.HAlign) a : TextLayout.HAlign.LEFT;
    }

    public TextLayout.VAlign vAlign() {
        Object a = props.get(PropKey.V_ALIGN);
        return a != null ? (TextLayout.VAlign) a : TextLayout.VAlign.MIDDLE;
    }

    /** Whether this text node is an editable text field. */
    public boolean editable() {
        return Boolean.TRUE.equals(props.get(PropKey.EDITABLE));
    }

    /** Whether this text node holds multiple lines (Enter inserts '\n'; the node scrolls vertically). */
    public boolean multiline() {
        return Boolean.TRUE.equals(props.get(PropKey.MULTILINE));
    }

    /** Whether long lines wrap at the content width instead of scrolling horizontally. */
    public boolean wordWrap() {
        return Boolean.TRUE.equals(props.get(PropKey.WORD_WRAP));
    }

    /**
     * Whether this text node's lines wrap to its content width. A label always does — that is how a caption
     * behaves — while an editable field wraps only when asked, because the alternative (scrolling horizontally)
     * is what a single-line field is for.
     *
     * <p>Both the layout (which needs the wrapped line count to size the node) and the compute phase (which
     * breaks the lines) ask this, so the rule has one home.
     */
    public boolean wrapsText() {
        return !editable() || (multiline() && wordWrap());
    }

    /** Caret offset into the text, or {@code -1} for no caret. */
    public int caret() {
        Object c = props.get(PropKey.CARET);
        return c instanceof Integer i ? i : -1;
    }

    /** Whether the caret is shown this blink phase (only meaningful when {@link #caret()} >= 0). */
    public boolean caretOn() {
        return Boolean.TRUE.equals(props.get(PropKey.CARET_ON));
    }

    /** Selection start offset (character index), or {@code -1} for no selection. */
    public int selectStart() {
        Object v = props.get(PropKey.SELECT_START);
        return v instanceof Integer i ? i : -1;
    }

    /** Selection end offset (character index), or {@code -1} for no selection. */
    public int selectEnd() {
        Object v = props.get(PropKey.SELECT_END);
        return v instanceof Integer i ? i : -1;
    }

    /** The formatting spans on this text node (empty if none). */
    @SuppressWarnings("unchecked")
    public java.util.List<dev.vexelray.gui.core.text.Span> spans() {
        Object v = props.get(PropKey.SPANS);
        return v instanceof java.util.List<?> list ? (java.util.List<dev.vexelray.gui.core.text.Span>) list
                : java.util.List.of();
    }

    public Direction direction() {
        Object d = props.get(PropKey.DIRECTION);
        return d != null ? (Direction) d : Direction.ROW;
    }

    public Justify justify() {
        Object j = props.get(PropKey.JUSTIFY);
        return j != null ? (Justify) j : Justify.START;
    }

    public AlignItems alignItems() {
        Object a = props.get(PropKey.ALIGN_ITEMS);
        return a != null ? (AlignItems) a : AlignItems.STRETCH;
    }

    public Length width() {
        return len(PropKey.WIDTH, Length.AUTO);
    }

    public Length height() {
        return len(PropKey.HEIGHT, Length.AUTO);
    }

    public Length padding() {
        return len(PropKey.PADDING, Length.ZERO);
    }

    /** Horizontal padding (left = right): {@code PADDING_X} if set, else the uniform {@code PADDING}, else zero. */
    public Length paddingX() {
        Object v = props.get(PropKey.PADDING_X);
        return v instanceof Length l ? l : padding();
    }

    /** Vertical padding (top = bottom): {@code PADDING_Y} if set, else the uniform {@code PADDING}, else zero. */
    public Length paddingY() {
        Object v = props.get(PropKey.PADDING_Y);
        return v instanceof Length l ? l : padding();
    }

    public Length margin() {
        return len(PropKey.MARGIN, Length.ZERO);
    }

    public Length gap() {
        return len(PropKey.GAP, Length.ZERO);
    }

    /** Whether horizontal overflow may scroll (default true = auto scrollbar). */
    public boolean scrollXAllowed() {
        // Wrapped text never scrolls horizontally: there is nothing to the right of a wrapped line to reach, so
        // an h-scrollbar there would be chrome for an axis that cannot move. Not overridable.
        if (kind == NodeKind.TEXT && wrapsText()) {
            return false;
        }
        Object v = props.get(PropKey.SCROLL_X);
        if (v instanceof Boolean b) {
            return b;   // an explicit choice always wins
        }
        // A single-line input masks its overflow at the edge and scrolls with the caret; a scrollbar under a
        // one-line box is chrome nobody asked for. Multi-line editors and containers still default to scrolling.
        return !(kind == NodeKind.TEXT && editable() && !multiline());
    }

    /** Whether vertical overflow may scroll (default true = auto scrollbar). */
    public boolean scrollYAllowed() {
        Object v = props.get(PropKey.SCROLL_Y);
        return !(v instanceof Boolean b) || b;
    }

    /** The scroll-edge lock for this container (default {@link ScrollLock#NONE}). */
    public ScrollLock scrollLock() {
        Object v = props.get(PropKey.SCROLL_LOCK);
        return v instanceof ScrollLock s ? s : ScrollLock.NONE;
    }

    private Length len(PropKey key, Length dflt) {
        Object v = props.get(key);
        return v instanceof Length l ? l : dflt;
    }

    // --- scrollbar thumb geometry (shared by the renderer and input dispatch so grab-testing matches drawing) ---

    /** Vertical thumb length in px (proportional to the visible fraction, floored so it stays grabbable). */
    public float vThumbLen() {
        return contentH <= 0f ? 0f : Math.max(scrollbarPx * 2f, viewH * (viewH / contentH));
    }

    /** Horizontal thumb length in px. */
    public float hThumbLen() {
        return contentW <= 0f ? 0f : Math.max(scrollbarPx * 2f, viewW * (viewW / contentW));
    }

    /** Vertical thumb rect {x, y, w, h} in the right-hand reserved strip. */
    public float[] vThumbRect() {
        float len = vThumbLen();
        float max = Math.max(0f, contentH - viewH);
        float frac = max > 0f ? scrollY / max : 0f;
        float y = viewY + frac * (viewH - len);
        return new float[]{viewX + viewW + scrollbarPx * 0.2f, y, scrollbarPx * 0.6f, len};
    }

    /** Horizontal thumb rect {x, y, w, h} in the bottom reserved strip. */
    public float[] hThumbRect() {
        float len = hThumbLen();
        float max = Math.max(0f, contentW - viewW);
        float frac = max > 0f ? scrollX / max : 0f;
        float x = viewX + frac * (viewW - len);
        return new float[]{x, viewY + viewH + scrollbarPx * 0.2f, len, scrollbarPx * 0.6f};
    }
}
