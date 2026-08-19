package dev.vexelray.gui.core.text;

import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.layout.Length;

import java.util.List;

/**
 * Computed caret geometry for a text node, published as data in the layout read-model
 * (docs/layout-read-model.md). Pure and transport-serializable: the visual lines and the per-boundary x
 * positions are baked in <b>absolute root-space pixels</b> at publish time (core measures once, with the
 * atlas), so a widget — or a remote client with no atlas — answers point↔offset and vertical navigation as pure
 * lookups. Coordinates match {@code ClickEvent} and the renderer, so no pad/scroll bookkeeping leaks to callers.
 */
public record TextMetrics(List<VisualLine> lines) {

    /**
     * The editable field's caret gutter, as {@link Length}s rather than pixels — §6 admits no pixel unit, and
     * these were the last scalars pinned to device pixels while everything around them (scrollbar thickness,
     * border, corner, text size) already scaled. Flat root, no cascade (§6), so these track
     * {@code rootEmPx · zoom · dpi} and not the node's own text size. At the default context they resolve to the
     * 10/6/4 px they replace.
     *
     * <p><b>These apply to editable nodes only.</b> An editable field is a <em>box</em> with a document inside
     * it — the gutter keeps the caret off the border on both axes. A label is the text block itself and gets
     * neither: its text area is its border box less its own declared border and padding, exactly like a
     * container's content box. Anything else makes a label's intrinsic width a lie — the layout would size it to
     * its glyphs while the wrap ran at glyphs-minus-gutter, guaranteeing a wrap inside the last word of every
     * auto-sized label. A label that wants breathing room says {@code padding(...)}, the prop that already means
     * that, rather than inheriting an inset it cannot remove.
     */
    public static final Length PAD_X = Length.em(0.625f);

    /** Vertical caret gutter for an editable node; see {@link #PAD_X} for why labels get none. */
    public static final Length PAD_Y = Length.em(0.375f);

    /** Gap either side of the digits in a line-number gutter. */
    public static final Length GUTTER_PAD = Length.em(0.25f);

    /**
     * Resolve the full horizontal text inset for {@code n} at border-box width {@code nodeW}: its border, its
     * declared padding, and — for an editable node only — the {@link #PAD_X} caret gutter, which shrinks on a
     * very narrow field so text never vanishes entirely.
     *
     * <p>Called by the layout, which then stores the result on the node ({@code RetainedNode.textPadXPx}) for
     * every later stage to read. Three stages need the identical number — the layout to know how many lines the
     * text wraps onto, the compute phase to break lines and bake caret x, and the renderer to clip — and resolving
     * it once onto the node is what stops them drifting, the same way border and corner are already handled.
     * The measure pass calls it too (with the width it is probing), so the width a text node reports is a width
     * its text actually fits in.
     */
    public static float resolveInsetX(LayoutContext ctx, dev.vexelray.gui.core.model.RetainedNode n, float nodeW) {
        float declared = n.borderWidth().scalarPx(ctx, nodeW) + n.paddingX().scalarPx(ctx, nodeW);
        float gutter = n.editable() ? Math.min(PAD_X.scalarPx(ctx, nodeW), nodeW * 0.25f) : 0f;
        return declared + gutter;
    }

    /** The vertical counterpart of {@link #resolveInsetX}: border + declared padding + the editable gutter. */
    public static float resolveInsetY(LayoutContext ctx, dev.vexelray.gui.core.model.RetainedNode n, float nodeW) {
        float declared = n.borderWidth().scalarPx(ctx, nodeW) + n.paddingY().scalarPx(ctx, nodeW);
        return declared + (n.editable() ? PAD_Y.scalarPx(ctx, nodeW) : 0f);
    }

    /** The width available to glyphs, from the inset the layout already resolved onto {@code n}. */
    public static float contentWidth(dev.vexelray.gui.core.model.RetainedNode n) {
        return Math.max(1f, n.w - 2f * n.textPadXPx);
    }

    /** The width available to glyphs at {@code nodeW}, resolving the inset — for callers running before layout. */
    public static float contentWidth(LayoutContext ctx, dev.vexelray.gui.core.model.RetainedNode n, float nodeW) {
        return Math.max(1f, nodeW - 2f * resolveInsetX(ctx, n, nodeW));
    }

    /**
     * The vertical inset of a text node's text area, as the layout resolved it: border + declared padding, plus
     * the caret gutter on an editable field. A bare label's is zero — the text block is the box — which is what
     * lets the layout size a wrapped label at exactly {@code lineCount · lineHeight}.
     */
    public static float padY(dev.vexelray.gui.core.model.RetainedNode n) {
        return n.textPadYPx;
    }

    /**
     * One visual line: its character range {@code [start, end]} (end inclusive of the caret-after-last position),
     * its top and height in root space, and {@code xs} — the absolute x of the caret before each character in the
     * line, length {@code (end - start) + 1} (so {@code xs[0]} is the line's left caret x and the last entry is
     * the caret x after the final character).
     *
     * <p>{@code number} is the 1-based <em>hard</em> line this visual line begins, or {@code 0} when it is the
     * continuation of a wrapped line and so carries no number. Baked in here rather than derived at draw time so
     * a consumer with no line-break data — a remote client rendering a snapshot — can still show a gutter.
     */
    public record VisualLine(int start, int end, float top, float height, float[] xs, int number) {

        public float bottom() {
            return top + height;
        }

        /** Absolute caret x for a character {@code offset} within (or clamped to) this line. */
        public float caretX(int offset) {
            int i = clampIndex(offset - start);
            return xs[i];
        }

        /** The character offset in this line whose caret x is nearest {@code absX}. */
        public int nearestOffset(float absX) {
            int best = 0;
            float bestD = Math.abs(xs[0] - absX);
            for (int i = 1; i < xs.length; i++) {
                float d = Math.abs(xs[i] - absX);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return start + best;
        }

        private int clampIndex(int i) {
            return i < 0 ? 0 : Math.min(i, xs.length - 1);
        }
    }

    /** The offset nearest the absolute point {@code (absX, absY)} — the query a click uses. */
    public int offsetAt(float absX, float absY) {
        return lineByY(absY).nearestOffset(absX);
    }

    /** Absolute caret x for {@code offset} (in its visual line). */
    public float caretX(int offset) {
        return lineOf(offset).caretX(offset);
    }

    /** Absolute top y of the visual line containing {@code offset}. */
    public float caretTop(int offset) {
        return lineOf(offset).top();
    }

    /** Height of the visual line containing {@code offset}. */
    public float caretHeight(int offset) {
        return lineOf(offset).height();
    }

    /** The offset one visual line up from {@code offset}, nearest column {@code desiredX} (unchanged on line 0). */
    public int offsetAbove(int offset, float desiredX) {
        int li = lineIndexOf(offset);
        return li <= 0 ? offset : lines.get(li - 1).nearestOffset(desiredX);
    }

    /** The offset one visual line down from {@code offset}, nearest column {@code desiredX} (unchanged on last). */
    public int offsetBelow(int offset, float desiredX) {
        int li = lineIndexOf(offset);
        return li >= lines.size() - 1 ? offset : lines.get(li + 1).nearestOffset(desiredX);
    }

    /** Start offset of the visual line containing {@code offset} (visual Home). */
    public int lineStart(int offset) {
        return lineOf(offset).start();
    }

    /** End offset of the visual line containing {@code offset} (visual End). */
    public int lineEnd(int offset) {
        return lineOf(offset).end();
    }

    /** The visual line containing {@code offset} — the last line whose start &le; offset. */
    public VisualLine lineOf(int offset) {
        return lines.get(lineIndexOf(offset));
    }

    private int lineIndexOf(int offset) {
        int idx = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).start() <= offset) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }

    private VisualLine lineByY(float absY) {
        for (VisualLine l : lines) {
            if (absY < l.bottom()) {
                return l;
            }
        }
        return lines.get(lines.size() - 1);
    }
}
