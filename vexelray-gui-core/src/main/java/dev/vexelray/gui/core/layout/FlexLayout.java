package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.text.Styling;
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
        layout(root, w, h, w, h, ctx, tm);
    }

    /**
     * Lay the whole tree into a {@code w}×{@code h} window whose content needs at least a
     * {@code canvasW}×{@code canvasH} canvas.
     *
     * <p><b>The root is the window; the canvas is its content.</b> The root's own box is always the window, so
     * what is anchored to the root's frame — a docked title bar, an overlay — is anchored to the window. Its flow
     * is laid out on the canvas, and where the canvas is the larger the root overflows and scrolls like any other
     * container, rather than the window showing a crop of a tree laid out somewhere bigger than it.
     */
    public static void layout(RetainedNode root, float w, float h, float canvasW, float canvasH, LayoutContext ctx,
                              TextMeasurer tm) {
        root.x = 0f;
        root.y = 0f;
        root.w = Math.max(0f, w);
        root.h = Math.max(0f, h);
        // Only a canvas larger than the window is a floor. One the window's size would count the strip a scrollbar
        // reserves on the other axis as overflow — content that fitted the window not fitting beside the bar.
        layoutBox(root, ctx, tm, canvasW > root.w ? canvasW : 0f, canvasH > root.h ? canvasH : 0f);
        // Two scrollers along one axis, one filling the other, are two bars side by side that nobody asked for:
        // said here, where the window and the canvas that may have caused it are both known.
        NestedScroll.report(root, root.w, root.h, canvasW, canvasH);
        // And a box scrolling sideways that never said it should: one thing that could not be narrower.
        UnaskedScroll.report(root, root.w, canvasW);
    }

    /** Resolve {@code n}'s render scalars and place its children within its content box. Recurses. */
    private static void layoutBox(RetainedNode n, LayoutContext ctx, TextMeasurer tm) {
        layoutBox(n, ctx, tm, 0f, 0f);
    }

    /**
     * As {@link #layoutBox(RetainedNode, LayoutContext, TextMeasurer)}, with the flow laid out as if {@code n}'s
     * border box were at least {@code floorW}×{@code floorH}. The box itself does not grow: the content does, and
     * overflows it.
     */
    private static void layoutBox(RetainedNode n, LayoutContext ctx, TextMeasurer tm, float floorW, float floorH) {
        // Resolve the render inputs the tree renderer needs, now that this node's border-box is known. Percent
        // padding/border/corner use the node's own border-box width as the basis (CSS convention).
        LayoutContext own = own(n, ctx);
        n.borderPx = n.borderWidth().scalarPx(own, n.w);
        n.cornerPx = n.corner().scalarPx(own, n.w);
        n.cornerBottomPx = n.cornerBottom().scalarPx(own, n.w);
        n.elevationPx = n.elevation().scalarPx(own, n.w);
        n.textSizePx = own.emPx();
        // The text insets and the em basis, resolved here so no later stage needs a LayoutContext to know them.
        // The inset is the full box-model one — border + declared padding + (editable-only) caret gutter — so a
        // text leaf insets its glyphs by the same accounting a container insets its children.
        n.emPx = emBasis(ctx);
        n.textPadXPx = TextMetrics.resolveInsetX(own, n, n.w);
        n.textPadYPx = TextMetrics.resolveInsetY(own, n, n.w);
        n.gutterPadPx = TextMetrics.GUTTER_PAD.scalarPx(own, n.w);

        List<RetainedNode> kids = flowChildren(n);
        if (kids.isEmpty()) {
            if (n.hasText()) {
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
            float band = dockBand(n, ctx, tm);
            n.viewY = n.y + band;
            n.viewH = Math.max(0f, n.h - band);
            placeFloating(n, ctx, tm);   // a box whose only children float is empty *flow*, not empty
            return;
        }

        float insetX = n.borderPx + n.paddingX().scalarPx(own, n.w);
        float insetY = n.borderPx + n.paddingY().scalarPx(own, n.w);
        float gap = n.gap().scalarPx(own, n.w);
        boolean row = n.direction() == Direction.ROW;

        float baseX = n.x + insetX;
        float baseY = n.y + Math.max(insetY, dockBand(n, ctx, tm));
        float baseW = Math.max(0f, n.w - 2f * insetX);
        float baseH = Math.max(0f, n.y + n.h - insetY - baseY);
        // The floor's content box, inset the same way. A docked band counts against it, so a floor is the whole
        // box's minimum and not the minimum of what is left below the band.
        float floorContentW = Math.max(0f, floorW - 2f * insetX);
        float floorContentH = Math.max(0f, n.y + floorH - insetY - baseY);
        float roomW = Math.max(baseW, floorContentW);
        float roomH = Math.max(baseH, floorContentH);

        // Pass 1: place in the full content box (no scroll) to measure how much space the children need.
        float[] need = place(n, baseX, baseY, row ? roomW : roomH, row ? roomH : roomW, row, gap, ctx, tm);
        // Laid out on the floor, the content is at least the floor — even where a stretched child, which never
        // counts toward the extent, is all that fills it.
        float neededW = Math.max(floorContentW, row ? need[0] : need[1]);
        float neededH = Math.max(floorContentH, row ? need[1] : need[0]);

        // Overflow + reserve: a scrollbar on one axis reserves a strip on the other. Recompute once so reserving
        // vertical space can reveal horizontal overflow and vice versa. Scrollbars appear purely from overflow
        // (docs/reference/scrolling.md §1).
        float sb = scrollbarThickness(ctx);
        // A container that never said whether it scrolls scrolls only along an axis long enough to hold a scrollbar
        // worth having: a thumb is at least two thicknesses long, so below SENSIBLE_SCROLL of them it has nowhere to
        // travel, and a pill squeezed into a chip, a button or a fixed-height row is chrome that scrolls by a few
        // pixels and narrows the content it was meant to reveal. Such a box overflows instead, as it would with
        // scroll(false, false) — what every application had been writing on each one by hand. Declared either way,
        // the declaration wins, so a short box that genuinely scrolls says scroll(..., true).
        boolean scrollsY = n.scrollYAllowed() && (n.scrollYDeclared() || baseH >= SENSIBLE_SCROLL * sb);
        boolean scrollsX = n.scrollXAllowed() && (n.scrollXDeclared() || baseW >= SENSIBLE_SCROLL * sb);
        boolean overY = scrollsY && neededH > baseH + EPS;
        boolean overX = scrollsX && neededW > baseW + EPS;
        float viewW = baseW - (overY ? sb : 0f);
        float viewH = baseH - (overX ? sb : 0f);
        overY = scrollsY && neededH > viewH + EPS;
        overX = scrollsX && neededW > viewW + EPS;
        viewW = Math.max(0f, baseW - (overY ? sb : 0f));
        viewH = Math.max(0f, baseH - (overX ? sb : 0f));

        // Re-measure at the reserved width. Wrapped text is height-for-width, so narrowing the box to make room
        // for a scrollbar makes the content *taller* — measuring it at the pre-scrollbar width leaves the scroll
        // range short by exactly the lines the narrowing added. This cannot oscillate: narrowing never shortens
        // wrapped content, so a box that overflowed at the full width still overflows at the reserved one.
        roomW = Math.max(viewW, floorContentW);
        roomH = Math.max(viewH, floorContentH);
        if (viewW < baseW - EPS || viewH < baseH - EPS) {
            float[] reNeed = place(n, baseX, baseY, row ? roomW : roomH, row ? roomH : roomW, row, gap, ctx, tm);
            neededW = Math.max(floorContentW, row ? reNeed[0] : reNeed[1]);
            neededH = Math.max(floorContentH, row ? reNeed[1] : reNeed[0]);
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
            place(n, baseX - n.scrollX, baseY - n.scrollY, row ? roomW : roomH, row ? roomH : roomW, row, gap, ctx, tm);
        }

        // Floating children last, against the settled frame — out of the flow above, on top of it on screen.
        placeFloating(n, ctx, tm);
    }

    /**
     * Place {@code n}'s children starting at {@code (originX, originY)} within {@code availMain}×{@code availCross},
     * recursing into each. Returns {@code {neededMain, neededCross}} — the space the children actually occupy, used
     * by the caller to detect overflow.
     */
    private static float[] place(RetainedNode n, float originX, float originY, float availMain, float availCross,
                                 boolean row, float gap, LayoutContext ctx, TextMeasurer tm) {
        List<RetainedNode> kids = flowChildren(n);
        Axis cross = row ? Axis.VERTICAL : Axis.HORIZONTAL;
        int count = kids.size();
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
        float[] marginMain = new float[count];
        float[] mainSize = mainSizes(kids, availMain, row, gap, crossPre, marginMain, ctx, tm);
        float gaps = gap * Math.max(0, count - 1);

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
            LayoutContext own = own(c, ctx);
            Length crossLen = row ? c.height() : c.width();
            float crossFixed = crossLen.resolve(own, availCross);
            float mCross = c.margin().scalarPx(own, availCross);
            float availC = Math.max(0f, availCross - 2f * mCross);
            float cs;
            float crossPos;
            // STRETCH fills the cross axis whenever the cross size is not a fixed length (AUTO or FILL/GROW all
            // stretch to the content extent), and FILL fills it under any alignment: see fillsCross.
            boolean stretched = fillsCross(n, crossLen, crossFixed);
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
     * The main-axis size of each of {@code kids} in {@code availMain}: its fixed length, else its measured content
     * (0 for a grow child), then the free space shared out by grow factor. {@code crossPre} holds a column's
     * already-resolved child widths, which its children's heights are measured against; a row ignores it. Fills
     * {@code marginMain} with each child's main-axis margin.
     *
     * <p>Shared by {@link #place} and by {@link #measure} for a row, so a row's height is measured against the
     * very widths it will place its children at.
     */
    private static float[] mainSizes(List<RetainedNode> kids, float availMain, boolean row, float gap,
                                     float[] crossPre, float[] marginMain, LayoutContext ctx, TextMeasurer tm) {
        Axis main = row ? Axis.HORIZONTAL : Axis.VERTICAL;
        int count = kids.size();
        float[] basis = new float[count];
        float[] growF = new float[count];
        float sumBasis = 0f;
        float sumGrow = 0f;
        for (int i = 0; i < count; i++) {
            RetainedNode c = kids.get(i);
            LayoutContext own = own(c, ctx);
            Length mainLen = row ? c.width() : c.height();
            float fixed = mainLen.resolve(own, availMain);
            basis[i] = fixed >= 0f ? fixed
                    : (mainLen.growFactor() > 0f ? 0f : measure(c, main, ctx, tm, row ? -1f : crossPre[i]));
            growF[i] = mainLen.growFactor();
            marginMain[i] = c.margin().scalarPx(own, availMain);
            sumBasis += basis[i] + 2f * marginMain[i];
            sumGrow += growF[i];
        }
        float free = availMain - sumBasis - gap * Math.max(0, count - 1);

        float[] mainSize = new float[count];
        for (int i = 0; i < count; i++) {
            mainSize[i] = basis[i];
            if (sumGrow > 0f && free > 0f) {
                mainSize[i] += free * (growF[i] / sumGrow);
            }
        }
        return mainSize;
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
        float px = n.textSizePx;
        float sb = scrollbarThickness(ctx);
        float lineH = tm.intrinsic(n, Axis.VERTICAL, px);
        float pad = n.textPadXPx;      // resolved in layoutBox, just above
        float padY = TextMetrics.padY(n);
        // The gutter is resolved before anything measures: it narrows the text area, so it decides the wrap, which
        // decides the line count, which is what the gutter is wide enough to number. Sized from the *hard* line
        // count, which wrapping cannot change — so that is a dependency, not a cycle.
        float gutter = gutterWidth(n, n.textSizePx, tm);
        n.gutterPx = gutter;
        float boxW = Math.max(1f, TextMetrics.contentWidth(n) - gutter);
        float top = Math.max(padY, dockBand(n, ctx, tm));   // a docked find bar moves the first line down
        float boxH = Math.max(0f, n.h - padY - top);

        // Pass 1 at the full text width.
        float[] lines = measureLines(n, boxW, px, tm);
        boolean overY = n.scrollYAllowed() && n.multiline() && lines[1] * lineH > boxH + EPS;
        float viewW = boxW - (overY ? sb : 0f);
        // A vertical bar narrows the text, and wrapped text is taller when narrower, so re-measure once at the
        // reserved width. Monotone — narrowing never shortens the block — so the bar it just reserved cannot
        // un-appear, and one extra pass is enough.
        if (overY && viewW < boxW - EPS) {
            lines = measureLines(n, viewW, px, tm);
        }
        // Decided against boxH, *not* against a viewH already shortened by an h-scrollbar: an h-bar must never be
        // what makes a v-bar appear. The one remaining interaction is real rather than spurious — a v-bar narrows
        // the text, which can genuinely push unwrapped text into horizontal overflow — and it cannot recur,
        // because the h-bar takes nothing from the width.
        boolean overX = n.scrollXAllowed() && lines[0] > viewW + EPS;

        n.overflowX = overX;
        n.overflowY = overY;
        n.scrollbarPx = sb;
        // The viewport is the *text area*: inset by the padding the metrics use, less whatever the bars reserve.
        // Keeping it in that space is what lets scrollX/scrollY, the thumb geometry and the caret metrics all be
        // expressed against the same rectangle.
        n.viewX = n.x + pad + gutter;
        n.viewY = n.y + top;
        n.viewW = Math.max(0f, viewW);
        n.viewH = Math.max(0f, boxH - (overX ? sb : 0f));
        n.contentW = lines[0];
        n.contentH = lines[1] * lineH;

        // A text leaf with a floating child is a leaf in *flow*, not a leaf — the same distinction the empty-box
        // case above draws. Its glyphs are its content and a float takes nothing from them, so a field can carry
        // its own chrome anchored to its own box (a find bar) without a wrapper the application has to know about.
        placeFloating(n, ctx, tm);
    }

    /**
     * Width of {@code n}'s line-number gutter, or 0 when it has none: wide enough for the highest hard-line
     * number, plus a gap either side. Uses the digit advance rather than a guess, so it is right at any text size.
     */
    private static float gutterWidth(RetainedNode n, float px, TextMeasurer tm) {
        if (!n.lineNumbers()) {
            return 0f;
        }
        float[] zero = tm.caretAdvances(Styling.base(n), "0", px);
        float digitW = zero != null && zero.length > 1 ? zero[1] : px * 0.6f;
        int digits = Integer.toString(Math.max(1, n.hardLineCount())).length();
        return digits * digitW + 2f * n.gutterPadPx;
    }

    /**
     * {@code {widestLinePx, lineCount}} for a text node at {@code textW}, measured through the same
     * {@code lineSpans} seam the compute phase and the renderer use — so the overflow decision, the height the
     * node is given, and the lines actually drawn cannot disagree.
     */
    private static float[] measureLines(RetainedNode n, float textW, float px, TextMeasurer tm) {
        String s = n.textString();
        if (s == null || s.isEmpty()) {
            n.lineSpans = null;
            return new float[]{0f, 1f};
        }
        var spans = tm.lineSpans(Styling.of(n), s, n.wrapsText() ? textW : 0f, px);
        n.lineSpans = spans;   // the one break-up of this text; the compute phase reads it rather than redoing it
        float[] adv = tm.caretAdvances(Styling.of(n), s, px);
        float widest = 0f;
        if (adv == null) {
            widest = Math.max(0f, tm.intrinsic(n, Axis.HORIZONTAL, px));   // no glyph metrics: whole-run estimate
        } else {
            for (var span : spans) {
                widest = Math.max(widest, adv[span.end()] - adv[span.start()]);
            }
        }
        return new float[]{widest, Math.max(1, spans.size())};
    }

    /**
     * The children that take part in the flex flow. A hidden child is not placed, not measured and not counted
     * toward gaps or overflow -- it occupies no space at all, rather than occupying space invisibly. A
     * <b>floating</b> child is likewise out of the flow — it takes no space and adds no overflow — but unlike a
     * hidden one it is very much laid out: {@link #placeFloating} sizes and positions it after the flow settles.
     *
     * <p>A hidden child's rect is zeroed so nothing downstream reads last frame geometry for a node that is no
     * longer shown; everything else about it survives untouched, which is the whole point of hiding rather than
     * removing.
     */
    private static List<RetainedNode> flowChildren(RetainedNode n) {
        for (RetainedNode c : n.children) {
            if (!c.visible() || c.floating()) {
                List<RetainedNode> shown = new java.util.ArrayList<>(n.children.size());
                for (RetainedNode k : n.children) {
                    if (!k.visible()) {
                        k.x = 0f;
                        k.y = 0f;
                        k.w = 0f;
                        k.h = 0f;
                    } else if (!k.floating()) {
                        shown.add(k);
                    }
                }
                return shown;
            }
        }
        return n.children;   // the common case allocates nothing
    }

    /**
     * Size and place {@code n}'s floating children, after the flow has settled. A floating child is sized to its
     * own width/height props when fixed, to the parent's extent when it said {@link Length#FILL}, and otherwise to
     * its intrinsic content, capped at the parent; its offsets resolve against the parent's border box and are
     * clamped so the whole node stays inside it — a menu opened near an edge slides in rather than cropping.
     * Scroll deliberately does not move it: a floating node is anchored to the parent's frame, not its content.
     *
     * <p><b>{@code FILL} means the parent out of flow, not intrinsic content.</b> The other flex keywords really do
     * lose their meaning here — {@code AUTO} is intrinsic by definition, and {@code grow} is a share of leftover
     * space among siblings a float has none of — but {@code FILL} is "take all there is", and out of flow what
     * there is is the parent box, with no distribution to compute and nothing ambiguous about it. Collapsing it to
     * content instead made every overlay that wanted to cover its parent restate the size in some other basis:
     * {@code ModalScrim} says {@code vw(100)/vh(100)}, which is only right because its parent is the root, and a
     * tab page lifted out of flow for a crossfade would have had to say {@code percent(100)} and put {@code FILL}
     * back afterwards. Two workarounds for one missing meaning is the sign it was missing rather than undefined.
     */
    private static void placeFloating(RetainedNode n, LayoutContext ctx, TextMeasurer tm) {
        for (RetainedNode c : n.children) {
            if (c.visible() && c.floating() && !c.docked()) {
                placeFloat(n, c, ctx, tm);
            }
        }
    }

    /**
     * Place {@code n}'s visible docked floats, and answer how far below {@code n}'s top edge the lowest of them
     * reaches — the band its viewport starts below. Placed before the flow rather than after it, because the flow
     * is what the band moves; a float's size and place depend only on the parent's box, so nothing is lost.
     */
    private static float dockBand(RetainedNode n, LayoutContext ctx, TextMeasurer tm) {
        float band = 0f;
        for (RetainedNode c : n.children) {
            if (c.visible() && c.docked()) {
                placeFloat(n, c, ctx, tm);
                band = Math.max(band, c.y + c.h - n.y);
            }
        }
        return Math.min(band, n.h);
    }

    /** Size and place one floating child {@code c} against {@code n}'s border box, then lay out its subtree. */
    private static void placeFloat(RetainedNode n, RetainedNode c, LayoutContext ctx, TextMeasurer tm) {
        LayoutContext own = own(c, ctx);
        float w = c.width().resolve(own, n.w);
        if (w < 0f) {
            w = c.width() instanceof Length.FillT ? n.w : measure(c, Axis.HORIZONTAL, ctx, tm);
        }
        w = Math.min(Math.max(0f, w), n.w);
        float h = c.height().resolve(own, n.h);
        if (h < 0f) {
            h = c.height() instanceof Length.FillT ? n.h : measure(c, Axis.VERTICAL, ctx, tm, w);
        }
        h = Math.min(Math.max(0f, h), n.h);
        c.w = w;
        c.h = h;
        c.x = n.x + clamp(c.floatX().scalarPx(own, n.w), 0f, Math.max(0f, n.w - w));
        c.y = n.y + clamp(c.floatY().scalarPx(own, n.h), 0f, Math.max(0f, n.h - h));
        layoutBox(c, ctx, tm);
    }

    /**
     * How many scrollbar thicknesses a container's content box must be along an axis before it scrolls there without
     * having been asked to: a minimum-length thumb (two thicknesses) plus as much again to travel.
     */
    static final float SENSIBLE_SCROLL = 4f;

    /** Scrollbar thickness in px — scales with the root em so it tracks zoom/DPI like everything else. */
    private static float scrollbarThickness(LayoutContext ctx) {
        return 0.85f * emBasis(ctx);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Intrinsic border-box size of {@code n} along {@code axis}: a fixed length if one is set in a basis-free unit
     * (em/rem/dp/vw/vh), otherwise content — text metrics for a text node, or the children's sizes for a box — plus
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
        LayoutContext own = own(n, ctx);
        Length along = axis == Axis.HORIZONTAL ? n.width() : n.height();
        if (isBasisFree(along)) {
            return Math.max(0f, along.resolve(own, 0f));
        }
        Length padAxis = axis == Axis.HORIZONTAL ? n.paddingX() : n.paddingY();
        float inset = n.borderWidth().scalarPx(own, 0f) + padAxis.scalarPx(own, 0f);
        if (n.hasText()) {
            float textPx = own.emPx();
            // An editable node adds its caret gutter, so an auto-sized field measures to a width its text
            // actually fits in — the same invariant the label case gets from the inset being border+padding
            // alone. (Unclamped: the narrow-node clamp needs the final width, and over-reporting by the clamped
            // sliver is safe where under-reporting would re-open the wrap-inside-the-last-word bug.)
            float gutter = !n.editable() ? 0f
                    : (axis == Axis.HORIZONTAL ? TextMetrics.PAD_X : TextMetrics.PAD_Y).scalarPx(own, 0f);
            float size = axis == Axis.VERTICAL && knownWidth >= 0f
                    ? textBlockHeight(n, knownWidth, textPx, own, tm)
                    : tm.intrinsic(n, axis, textPx) + 2f * gutter;
            return Math.max(0f, size) + 2f * inset;
        }
        List<RetainedNode> kids = flowChildren(n);
        if (kids.isEmpty()) {
            return 2f * inset;
        }
        Axis mainAxis = n.direction() == Direction.ROW ? Axis.HORIZONTAL : Axis.VERTICAL;
        // A box inside a known width knows its content width (that width less its own inset), so text nested a
        // level down still measures height-for-width. A column hands the content width to every child; a row
        // shares it out first, exactly as place will, and measures each child's height at its share — so a
        // title wrapping beside an icon makes the row as tall as the title, not one line tall.
        float contentWidth = knownWidth >= 0f
                ? Math.max(0f, knownWidth - 2f * n.borderWidth().scalarPx(own, 0f)
                        - 2f * n.paddingX().scalarPx(own, 0f))
                : -1f;
        float childWidth = mainAxis == Axis.VERTICAL ? contentWidth : -1f;
        if (axis == Axis.VERTICAL && mainAxis == Axis.HORIZONTAL && contentWidth >= 0f) {
            float[] marginMain = new float[kids.size()];
            float[] widths = mainSizes(kids, contentWidth, true, n.gap().scalarPx(own, knownWidth), null,
                    marginMain, ctx, tm);
            float max = 0f;
            for (int i = 0; i < kids.size(); i++) {
                RetainedNode c = kids.get(i);
                max = Math.max(max, measure(c, axis, ctx, tm, Math.max(0f, widths[i]))
                        + 2f * c.margin().scalarPx(own(c, ctx), 0f));
            }
            return max + 2f * inset;
        }
        if (axis == mainAxis) {
            float gap = n.gap().scalarPx(own, 0f);
            float sum = gap * Math.max(0, kids.size() - 1);
            for (RetainedNode c : kids) {
                sum += measure(c, axis, ctx, tm, childWidth) + 2f * c.margin().scalarPx(own(c, ctx), 0f);
            }
            return sum + 2f * inset;
        }
        float max = 0f;
        for (RetainedNode c : kids) {
            max = Math.max(max, measure(c, axis, ctx, tm, childWidth) + 2f * c.margin().scalarPx(own(c, ctx), 0f));
        }
        return max + 2f * inset;
    }

    /**
     * How tall a text node's content is at a border-box width of {@code nodeW}: its wrapped line count times the
     * line height. Line breaking goes through {@code TextMeasurer.lineSpans} — the same seam the compute phase
     * and the renderer use — so the height a node is given always matches the lines that end up drawn in it.
     */
    private static float textBlockHeight(RetainedNode n, float nodeW, float textPx, LayoutContext own,
                                         TextMeasurer tm) {
        float lineH = tm.intrinsic(n, Axis.VERTICAL, textPx);
        String s = n.textString();
        if (s == null || s.isEmpty()) {
            return lineH;   // an empty field is still one line tall
        }
        // Measure runs before this node's own layoutBox, so the insets are resolved here from its context rather
        // than read off the node — same Lengths, same numbers, just not yet stored.
        float wrapWidth = n.wrapsText() ? TextMetrics.contentWidth(own, n, nodeW) : 0f;
        int lineCount = Math.max(1, tm.lineSpans(Styling.of(n), s, wrapWidth, textPx).size());
        // An editable field's text sits inside a vertical caret gutter, so an auto-sized one must be tall enough
        // for both; a label is the block itself. Border and declared padding are the caller's to add — this is
        // content height only.
        float gutterY = n.editable() ? TextMetrics.PAD_Y.scalarPx(own, nodeW) : 0f;
        return lineCount * lineH + 2f * gutterY;
    }

    /**
     * The cross-axis size {@code c} will take inside {@code parent}. Extracted so a column can resolve it before
     * the main-axis basis loop (text height depends on it) and reuse the identical value when placing.
     */
    private static float crossSize(RetainedNode parent, RetainedNode c, float availCross, LayoutContext ctx,
                                   TextMeasurer tm, boolean column) {
        LayoutContext own = own(c, ctx);
        float mCross = c.margin().scalarPx(own, availCross);
        float availC = Math.max(0f, availCross - 2f * mCross);
        Length crossLen = column ? c.width() : c.height();
        float crossFixed = crossLen.resolve(own, availCross);
        if (fillsCross(parent, crossLen, crossFixed)) {
            return availC;
        }
        return Math.max(0f, crossFixed >= 0f ? crossFixed
                : measure(c, column ? Axis.HORIZONTAL : Axis.VERTICAL, ctx, tm));
    }

    /**
     * Whether a child takes its parent's whole cross extent: always under {@link AlignItems#STRETCH} when its cross
     * size is not fixed, and under any alignment when it said {@link Length#FILL}.
     *
     * <p><b>{@code FILL} means "take all there is" on either axis</b>, the argument {@link #placeFloating} makes for
     * floats. On the cross axis there is no leftover to share, so {@code grow} still has no meaning there; but all
     * there is, is the parent's cross extent, and alignment has nothing to place in a child that fills it. Reading
     * it as intrinsic under {@code CENTER} gave a title bar whose window buttons, declared {@code height(FILL)},
     * hovered as a letterbox inside the bar, and a caption — the drag region — shorter than the bar it is.
     */
    private static boolean fillsCross(RetainedNode parent, Length crossLen, float crossFixed) {
        return crossFixed < 0f && (parent.alignItems() == AlignItems.STRETCH || crossLen instanceof Length.FillT);
    }

    /** Basis-free fixed units resolve to a concrete size with no containing extent; percents and flex keywords don't. */
    private static boolean isBasisFree(Length l) {
        return l instanceof Length.Em || l instanceof Length.Rem || l instanceof Length.Dp
                || l instanceof Length.Vw || l instanceof Length.Vh;
    }

    /** The pixel value of one root em: {@code rootEmPx · zoom · dpi} — the basis for a percent text size. */
    private static float emBasis(LayoutContext ctx) {
        return ctx.remPx();
    }

    /**
     * The context {@code n}'s own lengths resolve in: {@code root}, with one em being {@code n}'s text size. Every
     * length that belongs to a node (its size, margin, padding, border, gap, offsets) resolves in this one, which is
     * what makes {@link Length#em} the node's own em; whatever only the layout itself sizes, the scrollbar, stays on
     * the root. {@code root} is always the root context, never another node's: the text size itself resolves
     * against it, so an em text size is the root's em and not a parent's.
     *
     * <p>Cached on the node and rebuilt only when the root or the text size changed, so a layout that changes
     * neither allocates nothing here.
     */
    private static LayoutContext own(RetainedNode n, LayoutContext root) {
        float em = Math.max(1f, n.textSize().scalarPx(root, emBasis(root)));
        LayoutContext c = n.layoutCtx;
        if (c == null || c.emPx() != em || !c.sameRoot(root)) {
            c = root.withEm(em);
            n.layoutCtx = c;
        }
        return c;
    }
}
