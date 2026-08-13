package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.model.NodeKind;
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
        return new RetainedNode(id, NodeKind.BOX);
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
        RetainedNode label = new RetainedNode(2, NodeKind.TEXT);
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
        RetainedNode header = new RetainedNode(1, NodeKind.TEXT);
        header.set(PropKey.TEXT, "hi");            // narrow intrinsic width
        header.set(PropKey.WIDTH, Length.FILL);
        header.set(PropKey.HEIGHT, Length.rem(2));
        add(root, header);

        FlexLayout.layout(root, 400f, 200f, ctx(), TM);

        assertEquals(400f, header.w, EPS, "FILL width in a column stretches across the full content width");
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
}
