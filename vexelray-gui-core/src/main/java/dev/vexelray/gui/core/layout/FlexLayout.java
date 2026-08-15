package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.text.TextMetrics;

import java.util.List;

/**
 * The flex layout: rows and columns with border-box sizing, padding, margin, border and gap. Not a full flex
 * implementation — no wrap, no shrink-below-basis — but bulletproof: every size is clamped non-negative, every
 * property has a sensible default, and nothing is ever left null or NaN, so a node can never land at an unexpected
 * position or size (architecture.md §6).
 *
 * <p><b>Border-box.</b> A node's rect ({@code x,y,w,h}) is its border-box: {@code w}/{@code h} include the border
 * and padding. The content box children live in is inset by {@code border + padding} on every side. Margin is
 * space <i>outside</i> the border-box that separates a node from its siblings and its parent's content edge.
 *
 * <p><b>Units.</b> All lengths are relative ({@link Length}); percentages resolve against the parent content
 * extent along the axis (width/height) or the node's own border-box width (padding/border/gap/corner). The single
 * {@link #measure} pass derives intrinsic sizes; {@link #layout} then places everything.
 */
public final class FlexLayout {

    /** Overflow tolerance in px, so sub-pixel rounding doesn't spuriously trigger a scrollbar. */
    private static final float EPS = 0.5f;

    private FlexLayout() {
    }

    /** Lay the whole tree into a {@code w}×{@code h} viewport at origin (0,0). */
    public static void layout(RetainedNode root, float w, float h, LayoutContext ctx, TextMeasurer tm) {
        root.x = 0f;
        root.y = 0f;
        root.w = Math.max(0f, w);
        root.h = Math.max(0f, h);
        layoutBox(root, ctx, tm);
    }

    /** Resolve {@code n}'s render scalars and place its children within its content box. Recurses. */
    private static void layoutBox(RetainedNode n, LayoutContext ctx, TextMeasurer tm) {
        // Resolve the render inputs the tree renderer needs, now that this node's border-box is known. Percent
        // padding/border/corner use the node's own border-box width as the basis (CSS convention).
        n.borderPx = n.borderWidth().scalarPx(ctx, n.w);
        n.cornerPx = n.corner().scalarPx(ctx, n.w);
        n.textSizePx = Math.max(1f, n.textSize().scalarPx(ctx, emBasis(ctx)));

        List<RetainedNode> kids = n.children;
        if (kids.isEmpty()) {
            if (n.kind == NodeKind.TEXT) {
                layoutTextLeaf(n, ctx, tm);
                return;
            }
            n.overflowX = false;
            n.overflowY = false;
            n.viewX = n.x;
            n.viewY = n.y;
            n.viewW = n.w;
            n.viewH = n.h;
            n.contentW = 0f;
            n.contentH = 0f;
            return;
        }

        float insetX = n.borderPx + n.paddingX().scalarPx(ctx, n.w);
        float insetY = n.borderPx + n.paddingY().scalarPx(ctx, n.w);
        float gap = n.gap().scalarPx(ctx, n.w);
        boolean row = n.direction() == Direction.ROW;

        float baseX = n.x + insetX;
        float baseY = n.y + insetY;
        float baseW = Math.max(0f, n.w - 2f * insetX);
        float baseH = Math.max(0f, n.h - 2f * insetY);

        // Pass 1: place in the full content box (no scroll) to measure how much space the children need.
        float[] need = place(n, baseX, baseY, row ? baseW : baseH, row ? baseH : baseW, row, gap, ctx, tm);
        float neededW = row ? need[0] : need[1];
        float neededH = row ? need[1] : need[0];

        // Overflow + reserve: a scrollbar on one axis reserves a strip on the other. Recompute once so reserving
        // vertical space can reveal horizontal overflow and vice versa. Scrollbars appear purely from overflow.
        float sb = scrollbarThickness(ctx);
        boolean overY = n.scrollYAllowed() && neededH > baseH + EPS;
        boolean overX = n.scrollXAllowed() && neededW > baseW + EPS;
        float viewW = baseW - (overY ? sb : 0f);
        float viewH = baseH - (overX ? sb : 0f);
        overY = n.scrollYAllowed() && neededH > viewH + EPS;
        overX = n.scrollXAllowed() && neededW > viewW + EPS;
        viewW = Math.max(0f, baseW - (overY ? sb : 0f));
        viewH = Math.max(0f, baseH - (overX ? sb : 0f));

        // Re-measure at the reserved width. Wrapped text is height-for-width, so narrowing the box to make room
        // for a scrollbar makes the content *taller* — measuring it at the pre-scrollbar width leaves the scroll
        // range short by exactly the lines the narrowing added. This cannot oscillate: narrowing never shortens
        // wrapped content, so a box that overflowed at the full width still overflows at the reserved one.
        if (viewW < baseW - EPS || viewH < baseH - EPS) {
            float[] reNeed = place(n, baseX, baseY, row ? viewW : viewH, row ? viewH : viewW, row, gap, ctx, tm);
            neededW = row ? reNeed[0] : reNeed[1];
            neededH = row ? reNeed[1] : reNeed[0];
        }

        // Clamp the persisted scroll to the new content, then record the viewport/content/overflow for the renderer.
        n.scrollX = clamp(n.scrollX, 0f, Math.max(0f, neededW - viewW));
        float maxScrollY = Math.max(0f, neededH - viewH);
        n.scrollY = clamp(n.scrollY, 0f, maxScrollY);
        // Scroll-lock (§8.5): while attached, pin the vertical offset to the locked edge so growth keeps the
        // edge in view (log tailing). The dispatcher toggles scrollAttached as the user scrolls off/onto the edge.
        if (n.scrollAttached) {
            switch (n.scrollLock()) {
                case TOP -> n.scrollY = 0f;
                case BOTTOM -> n.scrollY = maxScrollY;
                case NONE -> { }
            }
        }
        n.overflowX = overX;
        n.overflowY = overY;
        n.scrollbarPx = sb;
        n.viewX = baseX;
        n.viewY = baseY;
        n.viewW = viewW;
        n.viewH = viewH;
        n.contentW = neededW;
        n.contentH = neededH;

        // Re-place in the (possibly reduced) viewport, offset by the scroll, when scrolling is in effect.
        if (overX || overY || n.scrollX != 0f || n.scrollY != 0f) {
            place(n, baseX - n.scrollX, baseY - n.scrollY, row ? viewW : viewH, row ? viewH : viewW, row, gap, ctx, tm);
        }
    }

    /**
     * Place {@code n}'s children starting at {@code (originX, originY)} within {@code availMain}×{@code availCross},
     * recursing into each. Returns {@code {neededMain, neededCross}} — the space the children actually occupy, used
     * by the caller to detect overflow.
     */
    private static float[] place(RetainedNode n, float originX, float originY, float availMain, float availCross,
                                 boolean row, float gap, LayoutContext ctx, TextMeasurer tm) {
        List<RetainedNode> kids = n.children;
        Axis main = row ? Axis.HORIZONTAL : Axis.VERTICAL;
        Axis cross = row ? Axis.VERTICAL : Axis.HORIZONTAL;
        int count = kids.size();
        float[] basis = new float[count];
        float[] growF = new float[count];
        float[] marginMain = new float[count];
        float sumBasis = 0f;
        float sumGrow = 0f;
        // Wrapped text is height-for-width, not an intrinsic: a label that wraps onto three lines is three lines
        // tall. The two axes therefore resolve in opposite orders. In a COLUMN the child's width is settled by the
        // container before its height matters, so resolve the cross axis first and feed each width into the height
        // measure below. In a ROW the dependency runs the other way — width is the main size — and the cross pass
        // further down already has it.
        float[] crossPre = new float[count];
        if (!row) {
            for (int i = 0; i < count; i++) {
                crossPre[i] = crossSize(n, kids.get(i), availCross, ctx, tm, true);
            }
        }
        for (int i = 0; i < count; i++) {
            RetainedNode c = kids.get(i);
            Length mainLen = row ? c.width() : c.height();
            float fixed = mainLen.resolve(ctx, availMain);
            basis[i] = fixed >= 0f ? fixed
                    : (mainLen.growFactor() > 0f ? 0f : measure(c, main, ctx, tm, row ? -1f : crossPre[i]));
            growF[i] = mainLen.growFactor();
            marginMain[i] = c.margin().scalarPx(ctx, availMain);
            sumBasis += basis[i] + 2f * marginMain[i];
            sumGrow += growF[i];
        }
        float gaps = gap * Math.max(0, count - 1);
        float free = availMain - sumBasis - gaps;

        float[] mainSize = new float[count];
        for (int i = 0; i < count; i++) {
            mainSize[i] = basis[i];
            if (sumGrow > 0f && free > 0f) {
                mainSize[i] += free * (growF[i] / sumGrow);
            }
        }

        float used = gaps;
        for (int i = 0; i < count; i++) {
            used += mainSize[i] + 2f * marginMain[i];
        }
        float leftover = Math.max(0f, availMain - used);
        float startPad = switch (n.justify()) {
            case START, SPACE_BETWEEN -> 0f;
            case CENTER -> leftover / 2f;
            case END -> leftover;
        };
        float extraGap = (n.justify() == Justify.SPACE_BETWEEN && count > 1) ? leftover / (count - 1) : 0f;

        float neededCross = 0f;
        float cursor = startPad;
        for (int i = 0; i < count; i++) {
            RetainedNode c = kids.get(i);
            float ms = Math.max(0f, mainSize[i]);
            float mMain = marginMain[i];

            // Cross size + position, inset by the child's margin.
            Length crossLen = row ? c.height() : c.width();
            float crossFixed = crossLen.resolve(ctx, availCross);
            float mCross = c.margin().scalarPx(ctx, availCross);
            float availC = Math.max(0f, availCross - 2f * mCross);
            float cs;
            float crossPos;
            // On the cross axis grow/fill has no main-axis meaning; STRETCH fills it whenever the cross size is not
            // a fixed length (AUTO or FILL/GROW all stretch to the content extent).
            boolean stretched = n.alignItems() == AlignItems.STRETCH && crossFixed < 0f;
            if (stretched) {
                cs = availC;
                crossPos = mCross;
            } else if (!row) {
                cs = crossPre[i];   // already resolved above, and the height basis was measured against it
                crossPos = switch (n.alignItems()) {
                    case START, STRETCH -> mCross;
                    case CENTER -> mCross + Math.max(0f, (availC - cs) / 2f);
                    case END -> mCross + Math.max(0f, availC - cs);
                };
            } else {
                // Row: the cross axis is height, so this is the height-for-width measure — ms is the width.
                cs = Math.max(0f, crossFixed >= 0f ? crossFixed : measure(c, cross, ctx, tm, ms));
                crossPos = switch (n.alignItems()) {
                    case START, STRETCH -> mCross;
                    case CENTER -> mCross + Math.max(0f, (availC - cs) / 2f);
                    case END -> mCross + Math.max(0f, availC - cs);
                };
            }
            // Stretched children fill the cross extent by definition, so they never cause cross overflow; only
            // fixed/intrinsic cross sizes count toward the content size (otherwise reserving a scrollbar on one
            // axis would spuriously report overflow on the other).
            if (!stretched) {
                neededCross = Math.max(neededCross, cs + 2f * mCross);
            }

            if (row) {
                c.x = originX + cursor + mMain;
                c.w = ms;
                c.y = originY + crossPos;
                c.h = cs;
            } else {
                c.y = originY + cursor + mMain;
                c.h = ms;
                c.x = originX + crossPos;
                c.w = cs;
            }
            layoutBox(c, ctx, tm);
            cursor += 2f * mMain + ms + gap + extraGap;
        }
        return new float[]{used, neededCross};
    }

    /**
     * A text leaf's own viewport, content extent and overflow — the same job {@link #layoutBox} does for a
     * container, for the one leaf kind that has content of its own.
     *
     * <p>Only <b>unwrapped</b> text can overflow horizontally: wrapping means there is nothing to the right of a
     * line to scroll to, so {@code scrollXAllowed} is already false there. Vertical text scrolling remains
     * caret-follow only — {@code contentH} stays 0 until text leaves become vertical scroll citizens too.
     */
    private static void layoutTextLeaf(RetainedNode n, LayoutContext ctx, TextMeasurer tm) {
        float pad = Math.min(TextMetrics.PAD_X, n.w * 0.25f);
        float textW = TextMetrics.contentWidth(n.w);
        float widest = widestLine(n, textW, n.textSizePx, tm);
        boolean overX = n.scrollXAllowed() && widest > textW + EPS;
        float sb = scrollbarThickness(ctx);

        n.overflowX = overX;
        n.overflowY = false;
        n.scrollbarPx = sb;
        n.viewX = n.x + pad;
        n.viewY = n.y;
        n.viewW = textW;
        // The h-scrollbar takes its strip from this node's own height for now; moving that cost out to the parent
        // is the separate change (rule 2) that stops one scrollbar from ever inducing the other.
        n.viewH = Math.max(0f, n.h - (overX ? sb : 0f));
        n.contentW = widest;
        n.contentH = 0f;
    }

    /**
     * The widest visual line in px, measured through the same {@code lineSpans} seam the compute phase and the
     * renderer use — so the overflow decision and the lines actually drawn cannot disagree.
     */
    private static float widestLine(RetainedNode n, float textW, float px, TextMeasurer tm) {
        String s = n.textString();
        if (s == null || s.isEmpty()) {
            return 0f;
        }
        float[] adv = tm.caretAdvances(s, px);
        if (adv == null) {
            return Math.max(0f, tm.intrinsic(n, Axis.HORIZONTAL, px));   // no glyph metrics: whole-run estimate
        }
        float widest = 0f;
        for (var span : tm.lineSpans(s, n.wrapsText() ? textW : 0f, px)) {
            widest = Math.max(widest, adv[span.end()] - adv[span.start()]);
        }
        return widest;
    }

    /** Scrollbar thickness in px — scales with the root em so it tracks zoom/DPI like everything else. */
    private static float scrollbarThickness(LayoutContext ctx) {
        return 0.85f * emBasis(ctx);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Intrinsic border-box size of {@code n} along {@code axis}: a fixed length if one is set in a basis-free unit
     * (em/rem/vw/vh), otherwise content — text metrics for a text node, or the children's sizes for a box — plus
     * the node's own border + padding. Percent/Auto/Fill/Grow have no intrinsic basis here, so they measure to
     * content. Always ≥ 0.
     */
    public static float measure(RetainedNode n, Axis axis, LayoutContext ctx, TextMeasurer tm) {
        return measure(n, axis, ctx, tm, -1f);
    }

    /**
     * As {@link #measure(RetainedNode, Axis, LayoutContext, TextMeasurer)}, but with the node's already-resolved
     * <b>border-box width</b> when the caller knows it ({@code -1} when it does not). Measuring a text node's
     * height without it can only assume one line; with it, wrapped text reports its true height.
     */
    public static float measure(RetainedNode n, Axis axis, LayoutContext ctx, TextMeasurer tm, float knownWidth) {
        Length along = axis == Axis.HORIZONTAL ? n.width() : n.height();
        if (isBasisFree(along)) {
            return Math.max(0f, along.resolve(ctx, 0f));
        }
        Length padAxis = axis == Axis.HORIZONTAL ? n.paddingX() : n.paddingY();
        float inset = n.borderWidth().scalarPx(ctx, 0f) + padAxis.scalarPx(ctx, 0f);
        if (n.kind == NodeKind.TEXT) {
            float textPx = Math.max(1f, n.textSize().scalarPx(ctx, emBasis(ctx)));
            float size = axis == Axis.VERTICAL && knownWidth >= 0f
                    ? textBlockHeight(n, knownWidth, textPx, tm)
                    : tm.intrinsic(n, axis, textPx);
            return Math.max(0f, size) + 2f * inset;
        }
        List<RetainedNode> kids = n.children;
        if (kids.isEmpty()) {
            return 2f * inset;
        }
        Axis mainAxis = n.direction() == Direction.ROW ? Axis.HORIZONTAL : Axis.VERTICAL;
        // A column stacked inside a known width hands that width (less its own inset) to its children, so text
        // nested a level down still measures height-for-width. A row cannot: its children's widths come out of
        // flex distribution, which has not happened at measure time.
        float childWidth = knownWidth >= 0f && mainAxis == Axis.VERTICAL
                ? Math.max(0f, knownWidth - 2f * n.borderWidth().scalarPx(ctx, 0f)
                        - 2f * n.paddingX().scalarPx(ctx, 0f))
                : -1f;
        if (axis == mainAxis) {
            float gap = n.gap().scalarPx(ctx, 0f);
            float sum = gap * Math.max(0, kids.size() - 1);
            for (RetainedNode c : kids) {
                sum += measure(c, axis, ctx, tm, childWidth) + 2f * c.margin().scalarPx(ctx, 0f);
            }
            return sum + 2f * inset;
        }
        float max = 0f;
        for (RetainedNode c : kids) {
            max = Math.max(max, measure(c, axis, ctx, tm, childWidth) + 2f * c.margin().scalarPx(ctx, 0f));
        }
        return max + 2f * inset;
    }

    /**
     * How tall a text node's content is at a border-box width of {@code nodeW}: its wrapped line count times the
     * line height. Line breaking goes through {@code TextMeasurer.lineSpans} — the same seam the compute phase
     * and the renderer use — so the height a node is given always matches the lines that end up drawn in it.
     */
    private static float textBlockHeight(RetainedNode n, float nodeW, float textPx, TextMeasurer tm) {
        float lineH = tm.intrinsic(n, Axis.VERTICAL, textPx);
        String s = n.textString();
        if (s == null || s.isEmpty()) {
            return lineH;   // an empty field is still one line tall
        }
        float wrapWidth = n.wrapsText() ? TextMetrics.contentWidth(nodeW) : 0f;
        int lineCount = Math.max(1, tm.lineSpans(s, wrapWidth, textPx).size());
        return lineCount * lineH;
    }

    /**
     * The cross-axis size {@code c} will take inside {@code parent}. Extracted so a column can resolve it before
     * the main-axis basis loop (text height depends on it) and reuse the identical value when placing.
     */
    private static float crossSize(RetainedNode parent, RetainedNode c, float availCross, LayoutContext ctx,
                                   TextMeasurer tm, boolean column) {
        float mCross = c.margin().scalarPx(ctx, availCross);
        float availC = Math.max(0f, availCross - 2f * mCross);
        Length crossLen = column ? c.width() : c.height();
        float crossFixed = crossLen.resolve(ctx, availCross);
        if (parent.alignItems() == AlignItems.STRETCH && crossFixed < 0f) {
            return availC;
        }
        return Math.max(0f, crossFixed >= 0f ? crossFixed
                : measure(c, column ? Axis.HORIZONTAL : Axis.VERTICAL, ctx, tm));
    }

    /** Basis-free fixed units resolve to a concrete size with no containing extent; percents and flex keywords don't. */
    private static boolean isBasisFree(Length l) {
        return l instanceof Length.Em || l instanceof Length.Rem || l instanceof Length.Vw || l instanceof Length.Vh;
    }

    /** The pixel value of one em: {@code rootEmPx · zoom · dpi} — the basis for a percent text size. */
    private static float emBasis(LayoutContext ctx) {
        return ctx.rootEmPx() * ctx.zoom() * ctx.dpi();
    }
}
