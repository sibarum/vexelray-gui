package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;

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
            return;
        }

        float pad = n.padding().scalarPx(ctx, n.w);
        float inset = n.borderPx + pad;
        float gap = n.gap().scalarPx(ctx, n.w);
        boolean row = n.direction() == Direction.ROW;
        Axis main = row ? Axis.HORIZONTAL : Axis.VERTICAL;
        Axis cross = row ? Axis.VERTICAL : Axis.HORIZONTAL;

        float contentX = n.x + inset;
        float contentY = n.y + inset;
        float contentW = Math.max(0f, n.w - 2f * inset);
        float contentH = Math.max(0f, n.h - 2f * inset);
        float contentMain = row ? contentW : contentH;
        float contentCross = row ? contentH : contentW;

        int count = kids.size();
        float[] basis = new float[count];
        float[] growF = new float[count];
        float[] marginMain = new float[count];
        float sumBasis = 0f;
        float sumGrow = 0f;
        for (int i = 0; i < count; i++) {
            RetainedNode c = kids.get(i);
            Length mainLen = row ? c.width() : c.height();
            float fixed = mainLen.resolve(ctx, contentMain);
            basis[i] = fixed >= 0f ? fixed
                    : (mainLen.growFactor() > 0f ? 0f : measure(c, main, ctx, tm));
            growF[i] = mainLen.growFactor();
            marginMain[i] = c.margin().scalarPx(ctx, contentMain);
            sumBasis += basis[i] + 2f * marginMain[i];
            sumGrow += growF[i];
        }
        float gaps = gap * Math.max(0, count - 1);
        float free = contentMain - sumBasis - gaps;

        float[] mainSize = new float[count];
        for (int i = 0; i < count; i++) {
            mainSize[i] = basis[i];
            if (sumGrow > 0f && free > 0f) {
                mainSize[i] += free * (growF[i] / sumGrow);
            }
        }

        // Leftover main-axis space only exists when nothing grew; justify distributes it.
        float used = gaps;
        for (int i = 0; i < count; i++) {
            used += mainSize[i] + 2f * marginMain[i];
        }
        float leftover = Math.max(0f, contentMain - used);
        float startPad = switch (n.justify()) {
            case START, SPACE_BETWEEN -> 0f;
            case CENTER -> leftover / 2f;
            case END -> leftover;
        };
        float extraGap = (n.justify() == Justify.SPACE_BETWEEN && count > 1) ? leftover / (count - 1) : 0f;

        float cursor = startPad;
        for (int i = 0; i < count; i++) {
            RetainedNode c = kids.get(i);
            float ms = Math.max(0f, mainSize[i]);
            float mMain = marginMain[i];

            // Cross size + position, inset by the child's margin.
            Length crossLen = row ? c.height() : c.width();
            float crossFixed = crossLen.resolve(ctx, contentCross);
            float mCross = c.margin().scalarPx(ctx, contentCross);
            float availCross = Math.max(0f, contentCross - 2f * mCross);
            float cs;
            float crossPos;
            // On the cross axis grow/fill has no main-axis meaning; STRETCH fills it whenever the cross size is not
            // a fixed length (AUTO or FILL/GROW all stretch to the content extent).
            if (n.alignItems() == AlignItems.STRETCH && crossFixed < 0f) {
                cs = availCross;
                crossPos = mCross;
            } else {
                cs = Math.max(0f, crossFixed >= 0f ? crossFixed : measure(c, cross, ctx, tm));
                crossPos = switch (n.alignItems()) {
                    case START, STRETCH -> mCross;
                    case CENTER -> mCross + Math.max(0f, (availCross - cs) / 2f);
                    case END -> mCross + Math.max(0f, availCross - cs);
                };
            }

            if (row) {
                c.x = contentX + cursor + mMain;
                c.w = ms;
                c.y = contentY + crossPos;
                c.h = cs;
            } else {
                c.y = contentY + cursor + mMain;
                c.h = ms;
                c.x = contentX + crossPos;
                c.w = cs;
            }
            layoutBox(c, ctx, tm);
            cursor += 2f * mMain + ms + gap + extraGap;
        }
    }

    /**
     * Intrinsic border-box size of {@code n} along {@code axis}: a fixed length if one is set in a basis-free unit
     * (em/rem/vw/vh), otherwise content — text metrics for a text node, or the children's sizes for a box — plus
     * the node's own border + padding. Percent/Auto/Fill/Grow have no intrinsic basis here, so they measure to
     * content. Always ≥ 0.
     */
    public static float measure(RetainedNode n, Axis axis, LayoutContext ctx, TextMeasurer tm) {
        Length along = axis == Axis.HORIZONTAL ? n.width() : n.height();
        if (isBasisFree(along)) {
            return Math.max(0f, along.resolve(ctx, 0f));
        }
        float inset = n.borderWidth().scalarPx(ctx, 0f) + n.padding().scalarPx(ctx, 0f);
        if (n.kind == NodeKind.TEXT) {
            float textPx = Math.max(1f, n.textSize().scalarPx(ctx, emBasis(ctx)));
            return Math.max(0f, tm.intrinsic(n, axis, textPx)) + 2f * inset;
        }
        List<RetainedNode> kids = n.children;
        if (kids.isEmpty()) {
            return 2f * inset;
        }
        Axis mainAxis = n.direction() == Direction.ROW ? Axis.HORIZONTAL : Axis.VERTICAL;
        if (axis == mainAxis) {
            float gap = n.gap().scalarPx(ctx, 0f);
            float sum = gap * Math.max(0, kids.size() - 1);
            for (RetainedNode c : kids) {
                sum += measure(c, axis, ctx, tm) + 2f * c.margin().scalarPx(ctx, 0f);
            }
            return sum + 2f * inset;
        }
        float max = 0f;
        for (RetainedNode c : kids) {
            max = Math.max(max, measure(c, axis, ctx, tm) + 2f * c.margin().scalarPx(ctx, 0f));
        }
        return max + 2f * inset;
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
