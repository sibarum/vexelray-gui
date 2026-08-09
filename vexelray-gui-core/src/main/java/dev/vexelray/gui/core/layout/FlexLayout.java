package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;

import java.util.List;

/**
 * The flex layout: one {@link #measure} pass computes intrinsic sizes, then {@link #layout} places children of
 * each box along its main axis with basis + grow, distributes leftover space by {@code justify}, and sizes/places
 * on the cross axis by {@code alignItems}. Row/column, padding, and gap are supported; wrap and flex-shrink are
 * deferred (architecture.md §8). Fixed lengths win; {@code Auto} sizes to intrinsic content; {@code Fill}/{@code
 * Grow} share leftover main-axis space.
 *
 * <p>The "one {@code measure(axis)} per node" is {@link #measure} — the single place intrinsic size is derived,
 * replacing the duplicated per-variant switches earlier toolkits carried.
 */
public final class FlexLayout {

    private FlexLayout() {
    }

    /** Lay the whole tree into a {@code w}×{@code h} viewport at origin (0,0). */
    public static void layout(RetainedNode root, float w, float h, LayoutContext ctx, TextMeasurer tm) {
        root.x = 0;
        root.y = 0;
        root.w = w;
        root.h = h;
        layoutBox(root, ctx, tm);
    }

    /** Place {@code n}'s children within its already-set rect. Recurses. */
    private static void layoutBox(RetainedNode n, LayoutContext ctx, TextMeasurer tm) {
        List<RetainedNode> kids = n.children;
        if (kids.isEmpty()) {
            return;
        }
        float pad = n.padding();
        float gap = n.gap();
        boolean row = n.direction() == Direction.ROW;
        Axis main = row ? Axis.HORIZONTAL : Axis.VERTICAL;
        Axis cross = row ? Axis.VERTICAL : Axis.HORIZONTAL;

        float contentX = n.x + pad;
        float contentY = n.y + pad;
        float contentW = Math.max(0f, n.w - 2 * pad);
        float contentH = Math.max(0f, n.h - 2 * pad);
        float contentMain = row ? contentW : contentH;
        float contentCross = row ? contentH : contentW;

        int count = kids.size();
        float[] basis = new float[count];
        float[] growF = new float[count];
        float sumBasis = 0f;
        float sumGrow = 0f;
        for (int i = 0; i < count; i++) {
            RetainedNode c = kids.get(i);
            Length mainLen = row ? c.width() : c.height();
            float fixed = mainLen.fixedPx(ctx);
            basis[i] = fixed >= 0 ? fixed : (mainLen.growFactor() > 0 ? 0f : measure(c, main, ctx, tm));
            growF[i] = mainLen.growFactor();
            sumBasis += basis[i];
            sumGrow += growF[i];
        }
        float gaps = gap * (count - 1);
        float free = contentMain - sumBasis - gaps;

        float[] mainSize = new float[count];
        for (int i = 0; i < count; i++) {
            mainSize[i] = basis[i];
            if (sumGrow > 0f && free > 0f) {
                mainSize[i] += free * (growF[i] / sumGrow);
            }
        }

        // Leftover only matters when nothing grew; justify distributes it.
        float used = gaps;
        for (float ms : mainSize) {
            used += ms;
        }
        float leftover = Math.max(0f, contentMain - used);
        float start = switch (n.justify()) {
            case START, SPACE_BETWEEN -> 0f;
            case CENTER -> leftover / 2f;
            case END -> leftover;
        };
        float extraGap = (n.justify() == LayoutEnums.Justify.SPACE_BETWEEN && count > 1)
                ? leftover / (count - 1) : 0f;

        float cursor = start;
        for (int i = 0; i < count; i++) {
            RetainedNode c = kids.get(i);
            float ms = mainSize[i];

            // Cross size + position.
            Length crossLen = row ? c.height() : c.width();
            float crossFixed = crossLen.fixedPx(ctx);
            float cs;
            float crossPos;
            if (n.alignItems() == LayoutEnums.AlignItems.STRETCH && crossFixed < 0) {
                cs = contentCross;
                crossPos = 0f;
            } else {
                cs = crossFixed >= 0 ? crossFixed : measure(c, cross, ctx, tm);
                crossPos = switch (n.alignItems()) {
                    case START, STRETCH -> 0f;
                    case CENTER -> (contentCross - cs) / 2f;
                    case END -> contentCross - cs;
                };
            }

            if (row) {
                c.x = contentX + cursor;
                c.w = ms;
                c.y = contentY + crossPos;
                c.h = cs;
            } else {
                c.y = contentY + cursor;
                c.h = ms;
                c.x = contentX + crossPos;
                c.w = cs;
            }
            layoutBox(c, ctx, tm);
            cursor += ms + gap + extraGap;
        }
    }

    /** Intrinsic size of {@code n} along {@code axis}: fixed length if set, else content (text metrics / children). */
    public static float measure(RetainedNode n, Axis axis, LayoutContext ctx, TextMeasurer tm) {
        Length along = axis == Axis.HORIZONTAL ? n.width() : n.height();
        float fixed = along.fixedPx(ctx);
        if (fixed >= 0) {
            return fixed;
        }
        float pad2 = 2 * n.padding();
        if (n.kind == NodeKind.TEXT) {
            return tm.intrinsic(n, axis) + pad2;
        }
        // BOX: along its main axis sum children (+gaps); along the cross axis take the max.
        Axis mainAxis = n.direction() == Direction.ROW ? Axis.HORIZONTAL : Axis.VERTICAL;
        List<RetainedNode> kids = n.children;
        if (kids.isEmpty()) {
            return pad2;
        }
        if (axis == mainAxis) {
            float sum = n.gap() * (kids.size() - 1);
            for (RetainedNode c : kids) {
                sum += measure(c, axis, ctx, tm);
            }
            return sum + pad2;
        }
        float max = 0f;
        for (RetainedNode c : kids) {
            max = Math.max(max, measure(c, axis, ctx, tm));
        }
        return max + pad2;
    }
}
