package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scroll-lock pins the offset to the locked edge while attached, and respects detachment (§8.5). */
class ScrollLockTest {

    private static final TextMeasurer TM = (node, axis, px) -> 0f;

    /** Fixed pixel length via rem (1rem = 16px in the default context). */
    private static Length px(float p) {
        return Length.rem(p / 16f);
    }

    /** A vertical scroller of {@code lineCount} fixed-height lines inside a fixed viewport. */
    private static RetainedNode scroller(int lineCount, float lineH, float viewportH, ScrollLock lock) {
        RetainedNode col = new RetainedNode(0, NodeKind.BOX);
        col.set(PropKey.DIRECTION, Direction.COLUMN);
        col.set(PropKey.HEIGHT, px(viewportH));
        col.set(PropKey.WIDTH, px(100));
        if (lock != ScrollLock.NONE) {
            col.set(PropKey.SCROLL_LOCK, lock);
        }
        for (int i = 0; i < lineCount; i++) {
            RetainedNode line = new RetainedNode(i + 1, NodeKind.BOX);
            line.set(PropKey.HEIGHT, px(lineH));
            line.set(PropKey.WIDTH, px(100));
            line.parent = col;
            col.children.add(line);
        }
        return col;
    }

    private static void layout(RetainedNode n, float w, float h) {
        FlexLayout.layout(n, w, h, LayoutContext.of(w, h), TM);
    }

    @Test
    void bottomLockPinsToBottomAndFollowsGrowth() {
        RetainedNode col = scroller(20, 10f, 100f, ScrollLock.BOTTOM); // 200px content, 100px view
        layout(col, 100f, 100f);
        float maxY = col.contentH - col.viewH;
        assertTrue(maxY > 0f, "content overflows");
        assertEquals(maxY, col.scrollY, 0.5f, "attached bottom lock opens pinned to the bottom");

        // Grow the content: a still-attached tail follows to the new bottom.
        RetainedNode extra = new RetainedNode(999, NodeKind.BOX);
        extra.set(PropKey.HEIGHT, px(10));
        extra.set(PropKey.WIDTH, px(100));
        extra.parent = col;
        col.children.add(extra);
        layout(col, 100f, 100f);
        assertEquals(col.contentH - col.viewH, col.scrollY, 0.5f, "growth keeps the tail pinned to the bottom");
    }

    @Test
    void detachedLockLeavesScrollAlone() {
        RetainedNode col = scroller(20, 10f, 100f, ScrollLock.BOTTOM);
        col.scrollAttached = false; // user scrolled away
        col.scrollY = 30f;
        layout(col, 100f, 100f);
        assertEquals(30f, col.scrollY, 0.5f, "a detached lock does not re-pin the scroll offset");
    }

    @Test
    void topLockPinsToTop() {
        RetainedNode col = scroller(20, 10f, 100f, ScrollLock.TOP);
        col.scrollY = 50f; // pretend it was scrolled down
        layout(col, 100f, 100f);
        assertEquals(0f, col.scrollY, 0.5f, "attached top lock pins to the top");
    }
}
