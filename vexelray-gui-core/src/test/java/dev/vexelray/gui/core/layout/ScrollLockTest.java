package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

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

    @Test
    void scrollToEdgeReattachesADetachedTail() {
        // The other way back to the tail. Scrolling onto the edge re-attaches on its own; this is for an
        // application that knows the reader has stopped reading history, because they just did something.
        try (Gui gui = new Gui(Atchung.create())) {
            Node log = gui.column().width(Length.percent(100)).height(px(100))
                    .scrollLock(ScrollLock.BOTTOM);
            gui.root().children(log);
            for (int i = 0; i < 20; i++) {
                log.append(gui.box().width(Length.percent(100)).height(px(10)));
            }
            RetainedNode retained = find(gui.frame(100f, 100f, TM), log.id());
            assertTrue(retained.scrollY > 0f, "the tail opens at the bottom of overflowing content");

            // The user scrolls away. Growth must not drag them back.
            retained.scrollAttached = false;
            retained.scrollY = 30f;
            log.append(gui.box().width(Length.percent(100)).height(px(10)));
            gui.frame(100f, 100f, TM);
            assertEquals(30f, retained.scrollY, 0.5f, "a detached tail holds its place as content grows");

            log.scrollToEdge();
            gui.frame(100f, 100f, TM);
            assertEquals(retained.contentH - retained.viewH, retained.scrollY, 0.5f,
                    "scrollToEdge lands on the bottom the content has now");

            // And it re-attached rather than jumping once: the next line has to carry it too.
            log.append(gui.box().width(Length.percent(100)).height(px(10)));
            gui.frame(100f, 100f, TM);
            assertEquals(retained.contentH - retained.viewH, retained.scrollY, 0.5f,
                    "and stays attached, so the tail keeps following");
        }
    }

    @Test
    void scrollToEdgeIsANoOpWithoutALock() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node list = gui.column().width(Length.percent(100)).height(px(100));
            gui.root().children(list);
            for (int i = 0; i < 20; i++) {
                list.append(gui.box().width(Length.percent(100)).height(px(10)));
            }
            RetainedNode retained = find(gui.frame(100f, 100f, TM), list.id());
            retained.scrollY = 30f;

            list.scrollToEdge();
            gui.frame(100f, 100f, TM);

            assertEquals(30f, retained.scrollY, 0.5f, "no lock, so there is no edge to be sent to");
        }
    }

    /** The retained node behind a handle, for a test that needs to put a scroller where a user would leave it. */
    private static RetainedNode find(RetainedNode root, long id) {
        if (root.id == id) {
            return root;
        }
        for (RetainedNode c : root.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
