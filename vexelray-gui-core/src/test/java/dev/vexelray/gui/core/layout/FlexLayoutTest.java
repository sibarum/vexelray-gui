package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout-engine behaviour and its robustness guarantees: border-box sizing, margins, percent, grow, gap — and the
 * invariants that no size is ever negative or NaN and every default is sensible (root em = 16px). Trees are built
 * directly from {@link RetainedNode} so the test is independent of the {@code Gui}/bus wiring.
 */
class FlexLayoutTest {

    private static final float EPS = 0.01f;

    // Deterministic text metrics: width = chars · size · 0.5, height = size.
    private static final TextMeasurer TM =
            (n, axis, sizePx) -> axis == Axis.HORIZONTAL
                    ? (n.textString() == null ? 0 : n.textString().length()) * sizePx * 0.5f
                    : sizePx;

    private static RetainedNode box(long id) {
        return new RetainedNode(id);
    }

    private static void add(RetainedNode parent, RetainedNode child) {
        child.parent = parent;
        parent.children.add(child);
    }

    private static LayoutContext ctx() {
        return LayoutContext.of(1000f, 1000f); // rootEmPx=16, zoom=dpi=1
    }

    @Test
    void borderBoxInsetsContentByBorderPlusPadding() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        root.set(PropKey.PADDING, Length.rem(1));   // 16px
        root.set(PropKey.BORDER_WIDTH, Length.rem(0.5f)); // 8px -> inset 24 per side
        RetainedNode child = box(1);
        child.set(PropKey.WIDTH, Length.rem(2));     // 32px fixed; height AUTO -> STRETCH
        add(root, child);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(8f, root.borderPx, EPS, "border resolved to px on the node");
        // content box = 200-48 x 100-48 = 152 x 52, origin (24,24)
        assertEquals(24f, child.x, EPS);
        assertEquals(24f, child.y, EPS);
        assertEquals(32f, child.w, EPS);
        assertEquals(52f, child.h, EPS, "AUTO cross stretches to the content height");
    }

    /**
     * FILL on the cross axis is the parent's cross extent under any alignment, as it is out of flow. Under CENTER
     * it read as intrinsic, so a child asking for all the height got its content's instead, centred.
     */
    @Test
    void fillOnTheCrossAxisFillsItUnderAnyAlignment() {
        for (AlignItems align : new AlignItems[] {AlignItems.CENTER, AlignItems.START, AlignItems.END}) {
            RetainedNode root = box(0);
            root.set(PropKey.DIRECTION, Direction.ROW);
            root.set(PropKey.ALIGN_ITEMS, align);
            RetainedNode filled = box(1);
            filled.set(PropKey.WIDTH, Length.rem(2));
            filled.set(PropKey.HEIGHT, Length.FILL);
            RetainedNode intrinsic = box(2);
            intrinsic.set(PropKey.WIDTH, Length.rem(2));
            add(root, filled);
            add(root, intrinsic);

            FlexLayout.layout(root, 200f, 100f, ctx(), TM);

            assertEquals(0f, filled.y, EPS, align + ": FILL starts at the cross edge");
            assertEquals(100f, filled.h, EPS, align + ": FILL takes the whole cross extent");
            assertEquals(0f, intrinsic.h, EPS, align + ": AUTO is still its content, which here is nothing");
        }
    }

    @Test
    void perAxisPaddingInsetsIndependently() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        root.set(PropKey.PADDING_X, Length.rem(1));    // 16 left/right
        root.set(PropKey.PADDING_Y, Length.rem(0.5f)); // 8 top/bottom
        RetainedNode child = box(1);
        child.set(PropKey.WIDTH, Length.rem(2));
        add(root, child);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(16f, child.x, EPS, "horizontal padding insets x");
        assertEquals(8f, child.y, EPS, "vertical padding insets y");
        assertEquals(84f, child.h, EPS, "cross stretch fills height minus vertical padding");
    }

    @Test
    void percentResolvesAgainstParentContentExtent() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        RetainedNode child = box(1);
        child.set(PropKey.WIDTH, Length.percent(50));
        add(root, child);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(100f, child.w, EPS, "50% of 200 content width");
    }

    @Test
    void marginOffsetsAndConsumesMainAxisSpace() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        RetainedNode a = box(1);
        a.set(PropKey.WIDTH, Length.rem(2));   // 32
        a.set(PropKey.MARGIN, Length.rem(1));  // 16 each side
        RetainedNode b = box(2);
        b.set(PropKey.WIDTH, Length.rem(2));   // 32
        add(root, a);
        add(root, b);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(16f, a.x, EPS, "left margin offsets a");
        assertEquals(64f, b.x, EPS, "a consumes 16+32+16, so b starts at 64");
    }

    /** An em is the node's own text size: a node at twice the root's text has an em of twice the root's. */
    @Test
    void emIsTheNodesOwnTextSize() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        RetainedNode big = box(1);
        big.set(PropKey.TEXT_SIZE, Length.rem(2));    // 32px, so one em here is 32
        big.set(PropKey.WIDTH, Length.em(2));         // 64
        big.set(PropKey.PADDING, Length.em(0.5f));    // 16 each side
        big.set(PropKey.MARGIN, Length.em(0.25f));    // 8 each side
        RetainedNode inner = box(2);
        inner.set(PropKey.WIDTH, Length.rem(1));
        add(root, big);
        add(big, inner);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(64f, big.w, EPS, "em(2) at a 32px text size");
        assertEquals(8f, big.x, EPS, "the margin is in the node's em too");
        assertEquals(big.x + 16f, inner.x, EPS, "and so is its padding");
        assertEquals(32f, big.textSizePx, EPS);
    }

    /** At the default text size an em and a rem are the same number, which is why moving em to rem moved nothing. */
    @Test
    void anEmAtTheDefaultTextSizeIsARem() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        RetainedNode a = box(1);
        a.set(PropKey.WIDTH, Length.em(3));
        RetainedNode b = box(2);
        b.set(PropKey.WIDTH, Length.rem(3));
        add(root, a);
        add(root, b);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(48f, a.w, EPS);
        assertEquals(b.w, a.w, EPS);
    }

    /** Text size is not inherited, so neither is an em: a child's em is its own text size, never its parent's. */
    @Test
    void anEmIsNotTheParents() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        RetainedNode parent = box(1);
        parent.set(PropKey.TEXT_SIZE, Length.rem(2));
        RetainedNode child = box(2);
        child.set(PropKey.WIDTH, Length.em(1));
        add(root, parent);
        add(parent, child);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(16f, child.w, EPS, "the child's own default text size, one rem");
    }

    /** A text size in em is what defines the node's em, so it resolves against the root and not against itself. */
    @Test
    void anEmTextSizeIsTheRootsEm() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        RetainedNode n = box(1);
        n.set(PropKey.TEXT_SIZE, Length.em(1.5f));   // 24px: 1.5 root ems
        n.set(PropKey.WIDTH, Length.em(2));          // 48: two of its own
        add(root, n);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(24f, n.textSizePx, EPS);
        assertEquals(48f, n.w, EPS);
    }

    /** An em scales with zoom and density as the text it follows does, and follows a text size changed later. */
    @Test
    void anEmFollowsZoomAndALaterTextSize() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        RetainedNode n = box(1);
        n.set(PropKey.WIDTH, Length.em(1));
        add(root, n);

        FlexLayout.layout(root, 200f, 100f, new LayoutContext(16f, 2f, 1.5f, 1000f, 1000f), TM);
        assertEquals(48f, n.w, EPS, "16 · zoom 2 · dpi 1.5");

        n.set(PropKey.TEXT_SIZE, Length.rem(0.5f));
        FlexLayout.layout(root, 200f, 100f, new LayoutContext(16f, 2f, 1.5f, 1000f, 1000f), TM);
        assertEquals(24f, n.w, EPS, "half the text, half the em; the cached context did not hold the old one");
    }

    /** Measured as it is placed: an auto-sized box's em padding is the box's own em, not the root's. */
    @Test
    void measureResolvesAnEmInTheNodesOwnText() {
        RetainedNode n = box(1);
        n.set(PropKey.TEXT_SIZE, Length.rem(2));
        n.set(PropKey.PADDING_X, Length.em(1));     // 32 each side

        assertEquals(64f, FlexLayout.measure(n, Axis.HORIZONTAL, ctx(), TM), EPS);
    }

    @Test
    void fillDistributesFreeMainSpace() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        RetainedNode grow = box(1);
        grow.set(PropKey.WIDTH, Length.FILL);
        RetainedNode fixed = box(2);
        fixed.set(PropKey.WIDTH, Length.rem(2)); // 32
        add(root, grow);
        add(root, fixed);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(0f, grow.x, EPS);
        assertEquals(168f, grow.w, EPS, "FILL takes 200-32");
        assertEquals(168f, fixed.x, EPS);
        assertEquals(32f, fixed.w, EPS);
    }

    @Test
    void columnStacksWithGap() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.COLUMN);
        root.set(PropKey.GAP, Length.rem(1)); // 16
        RetainedNode a = box(1);
        a.set(PropKey.HEIGHT, Length.rem(2)); // 32
        RetainedNode b = box(2);
        b.set(PropKey.HEIGHT, Length.rem(2)); // 32
        add(root, a);
        add(root, b);

        FlexLayout.layout(root, 100f, 200f, ctx(), TM);

        assertEquals(0f, a.y, EPS);
        assertEquals(48f, b.y, EPS, "32 + 16 gap");
    }

    @Test
    void everythingHasSensibleDefaultsAndNothingIsNaN() {
        RetainedNode root = box(0);
        RetainedNode bare = box(1);          // nothing set: width/height AUTO, no children
        RetainedNode label = new RetainedNode(2);
        label.set(PropKey.TEXT, "hi");
        add(root, bare);
        add(root, label);

        FlexLayout.layout(root, 300f, 120f, ctx(), TM);

        for (RetainedNode n : new RetainedNode[]{root, bare, label}) {
            assertTrue(Float.isFinite(n.x) && Float.isFinite(n.y), "position finite");
            assertTrue(n.w >= 0f && n.h >= 0f, "size non-negative");
            assertTrue(Float.isFinite(n.w) && Float.isFinite(n.h), "size finite");
            assertTrue(n.textSizePx >= 1f, "text size defaulted to >= 1px");
        }
        assertEquals(16f, label.textSizePx, EPS, "default text size is 1rem = 16px");
    }

    @Test
    void oversizedPaddingClampsContentToZeroNeverNegative() {
        RetainedNode root = box(0);
        root.set(PropKey.PADDING, Length.rem(100)); // 1600px padding on a 200px box
        RetainedNode child = box(1);
        child.set(PropKey.WIDTH, Length.FILL);
        add(root, child);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertTrue(child.w >= 0f && child.h >= 0f, "content clamps to zero, never negative");
        assertTrue(Float.isFinite(child.w) && Float.isFinite(child.h));
    }

    @Test
    void fillOnCrossAxisStretchesToContentExtent() {
        // A FILL width inside a COLUMN is a cross-axis length; with the default STRETCH it must fill the width,
        // not collapse to intrinsic content (regression: header/body must span the viewport).
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.COLUMN);
        RetainedNode header = new RetainedNode(1);
        header.set(PropKey.TEXT, "hi");            // narrow intrinsic width
        header.set(PropKey.WIDTH, Length.FILL);
        header.set(PropKey.HEIGHT, Length.rem(2));
        add(root, header);

        FlexLayout.layout(root, 400f, 200f, ctx(), TM);

        assertEquals(400f, header.w, EPS, "FILL width in a column stretches across the full content width");
    }

    @Test
    void verticalOverflowSetsScrollbarAndReservesWidth() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.COLUMN);
        for (int i = 0; i < 5; i++) {
            RetainedNode c = box(1 + i);
            c.set(PropKey.HEIGHT, Length.rem(2)); // 32 each -> 160 total
            add(root, c);
        }

        FlexLayout.layout(root, 100f, 100f, ctx(), TM);

        assertTrue(root.overflowY, "content taller than the box overflows vertically");
        assertTrue(!root.overflowX, "no spurious horizontal overflow from stretched children");
        assertTrue(root.viewW < 100f, "the vertical scrollbar reserves width");
        assertTrue(root.contentH > 150f, "content height reflects all children");
    }

    @Test
    void scrollOffsetClampsToContentAndShiftsChildren() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.COLUMN);
        RetainedNode first = box(1);
        first.set(PropKey.HEIGHT, Length.rem(2));
        add(root, first);
        for (int i = 0; i < 5; i++) {
            RetainedNode c = box(2 + i);
            c.set(PropKey.HEIGHT, Length.rem(2));
            add(root, c);
        }
        root.scrollY = 1000f; // absurd; layout must clamp to maxScroll

        FlexLayout.layout(root, 100f, 100f, ctx(), TM);

        float maxScroll = root.contentH - root.viewH;
        assertEquals(maxScroll, root.scrollY, EPS, "scroll clamps to content - viewport");
        assertEquals(-maxScroll, first.y, EPS, "children shift up by the scroll offset");
    }

    @Test
    void alignItemsCenterCentersOnCross() {
        RetainedNode root = box(0);
        root.set(PropKey.DIRECTION, Direction.ROW);
        root.set(PropKey.ALIGN_ITEMS, AlignItems.CENTER);
        RetainedNode child = box(1);
        child.set(PropKey.WIDTH, Length.rem(2));  // 32
        child.set(PropKey.HEIGHT, Length.rem(2)); // 32 fixed cross
        add(root, child);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(34f, child.y, EPS, "(100-32)/2 = 34");
    }

    /**
     * {@code FILL} keeps its meaning out of flow: an overlay that says "take all there is" gets the parent box.
     * The other flex keywords genuinely lose theirs there — {@code AUTO} is intrinsic by definition, {@code grow}
     * is a share of leftover space among siblings a float has none of — but for {@code FILL} the available space
     * is unambiguous and needs no distribution to compute. Without this, every overlay that wants to cover its
     * parent has to restate the size in another basis, and each restatement is right for one parent only.
     */
    @Test
    void aFloatingFillChildFillsItsParent() {
        RetainedNode root = box(0);
        root.set(PropKey.WIDTH, Length.percent(100));
        root.set(PropKey.HEIGHT, Length.percent(100));
        RetainedNode overlay = box(1);
        overlay.set(PropKey.WIDTH, Length.FILL);
        overlay.set(PropKey.HEIGHT, Length.FILL);
        overlay.set(PropKey.FLOAT_X, Length.ZERO);
        overlay.set(PropKey.FLOAT_Y, Length.ZERO);
        add(root, overlay);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertEquals(200f, overlay.w, EPS, "a floated FILL covers its parent rather than collapsing to content");
        assertEquals(100f, overlay.h, EPS);
        assertEquals(root.x, overlay.x, EPS);
        assertEquals(root.y, overlay.y, EPS);
    }

    /** And {@code AUTO} out of flow still means intrinsic — the distinction FILL is being separated from. */
    @Test
    void aFloatingAutoChildStillSizesToItsContent() {
        RetainedNode root = box(0);
        RetainedNode tip = new RetainedNode(1);
        tip.set(PropKey.TEXT, "abcd");            // 4 chars x 16px x 0.5 = 32 wide, 16 tall
        tip.set(PropKey.FLOAT_X, Length.ZERO);
        tip.set(PropKey.FLOAT_Y, Length.ZERO);
        add(root, tip);

        FlexLayout.layout(root, 200f, 100f, ctx(), TM);

        assertTrue(tip.w < 200f, "a tooltip is still the size of its text, not the size of the page under it");
        assertTrue(tip.h < 100f);
    }

    /**
     * A text node with a floating child is a leaf in <em>flow</em>, not a leaf: its glyphs are its content and a
     * float takes nothing from them, so the float is still placed. Without this an editable field could not carry
     * its own chrome — a find bar — anchored to its own box, and the bar would silently never be laid out.
     */
    @Test
    void aTextLeafStillPlacesItsFloatingChild() {
        RetainedNode field = new RetainedNode(0);
        field.set(PropKey.EDITABLE, Boolean.TRUE);
        field.set(PropKey.MULTILINE, Boolean.TRUE);
        field.set(PropKey.TEXT, "one\ntwo\nthree");
        RetainedNode bar = box(1);
        bar.set(PropKey.WIDTH, Length.FILL);
        bar.set(PropKey.HEIGHT, Length.rem(2));
        bar.set(PropKey.FLOAT_X, Length.ZERO);
        bar.set(PropKey.FLOAT_Y, Length.ZERO);
        add(field, bar);

        FlexLayout.layout(field, 200f, 100f, ctx(), TM);

        assertEquals(200f, bar.w, EPS, "the bar spans the field it is anchored to");
        assertEquals(32f, bar.h, EPS);
        assertEquals(field.x, bar.x, EPS, "seated at the box origin, not at the text origin");
        assertEquals(field.y, bar.y, EPS);
        assertTrue(field.contentH > 0f, "and the text was measured exactly as it was before it had a bar");
    }

    /**
     * A label wraps unless it says not to, and {@code wordWrap(false)} is how it says so: one line, cut off at its
     * edge, with no h-scrollbar for the overflow (a label has no caret to scroll after). Before, a non-editable node
     * wrapped whatever its {@code WORD_WRAP} said (text-editor-vexel-demo FN-18).
     */
    @Test
    void aLabelWrapsByDefaultAndWordWrapFalseKeepsItToOneLine() {
        String text = "Updates are ready for the whole suite, close first and open again";
        RetainedNode col = box(0);
        col.set(PropKey.DIRECTION, Direction.COLUMN);
        col.set(PropKey.ALIGN_ITEMS, AlignItems.START);
        RetainedNode unset = box(1);
        RetainedNode on = box(2);
        on.set(PropKey.WORD_WRAP, true);
        RetainedNode off = box(3);
        off.set(PropKey.WORD_WRAP, false);
        for (RetainedNode label : new RetainedNode[]{unset, on, off}) {
            label.set(PropKey.WIDTH, Length.rem(10));      // 160px, a third of the text
            label.set(PropKey.TEXT, text);
            add(col, label);
        }

        FlexLayout.layout(col, 1000f, 1000f, ctx(), WRAPPING_TM);

        assertTrue(unset.lineSpans.size() > 1, "a label that never set wordWrap still wraps");
        assertEquals(unset.lineSpans.size(), on.lineSpans.size(), "and wordWrap(true) is the same thing");
        assertEquals(1, off.lineSpans.size(), "wordWrap(false) keeps the label to one line");
        assertEquals(160f, off.w, EPS, "at the width it was given, not widened to its text");
        assertTrue(!off.overflowX && !off.overflowY, "cut off at its edge, with no scrollbar for the rest");
        assertTrue(on.h > off.h + EPS, "the wrapped label is the taller");
    }

    // As TM, and breaking lines at those widths, so wrapped text has a real height-for-width.
    private static final TextMeasurer WRAPPING_TM = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode n, Axis axis, float sizePx) {
            return TM.intrinsic(n, axis, sizePx);
        }

        @Override
        public java.util.List<dev.vexelray.text.TextLayout.LineSpan> lineSpans(String text, float wrapWidth,
                                                                               float sizePx) {
            return dev.vexelray.text.TextLayout.breakLineSpans(text, (index, cp) -> sizePx * 0.5f, wrapWidth,
                    dev.vexelray.text.TextLayout.WrapMode.WORD_CHAR);
        }
    };

    /**
     * column(20rem) > [row(icon, wrapped title), detail]: the row is as tall as its title wraps to at the width the
     * row gives it, so the title's second line sits above the detail rather than drawn over it.
     */
    private static void assertWrappedTitleInRowPushesNextLineDown(Length titleWidth) {
        RetainedNode col = box(0);
        col.set(PropKey.DIRECTION, Direction.COLUMN);
        col.set(PropKey.ALIGN_ITEMS, AlignItems.START);
        RetainedNode panel = box(1);
        panel.set(PropKey.DIRECTION, Direction.COLUMN);
        panel.set(PropKey.WIDTH, Length.rem(20));             // 320px
        add(col, panel);
        RetainedNode head = box(2);
        head.set(PropKey.DIRECTION, Direction.ROW);
        head.set(PropKey.ALIGN_ITEMS, AlignItems.START);
        add(panel, head);
        RetainedNode icon = box(3);
        icon.set(PropKey.WIDTH, Length.rem(1.25f));
        icon.set(PropKey.HEIGHT, Length.rem(1.25f));
        add(head, icon);
        RetainedNode title = box(4);
        title.set(PropKey.WIDTH, titleWidth);
        title.set(PropKey.WORD_WRAP, true);
        title.set(PropKey.TEXT, "Updates are ready for the whole suite, close first and open again");
        add(head, title);
        RetainedNode detail = box(5);
        detail.set(PropKey.TEXT, "Detail");
        add(panel, detail);

        FlexLayout.layout(col, 1000f, 1000f, ctx(), WRAPPING_TM);

        assertTrue(title.lineSpans != null && title.lineSpans.size() > 1, "the title wraps");
        float titleH = title.lineSpans.size() * 16f;
        assertEquals(titleH, title.h, EPS, "the title is as tall as its lines");
        assertEquals(titleH, head.h, EPS, "the row is as tall as the title it wraps");
        assertTrue(title.y + title.h <= detail.y + EPS,
                () -> "the title's last line ends at " + (title.y + title.h) + ", over the detail at " + detail.y);
    }

    @Test
    void aGrowingWrappedTitleInARowPushesTheNextLineDown() {
        assertWrappedTitleInRowPushesNextLineDown(Length.grow(1f));
    }

    @Test
    void aFixedWidthWrappedTitleInARowPushesTheNextLineDown() {
        assertWrappedTitleInRowPushesNextLineDown(Length.rem(15));
    }

    @Test
    void aPercentWidthWrappedTitleInARowPushesTheNextLineDown() {
        assertWrappedTitleInRowPushesNextLineDown(Length.percent(80));
    }
}
