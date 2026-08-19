package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context menu, and the two primitives underneath it: right-button dispatch (a context click pairs a right
 * press and release on the same node and reports the position) and floating placement (a floating last child of
 * the root takes no space from the page, paints over it, and is hit before it).
 */
class ContextMenuTest {

    /** A full-viewport page with a target node, plus a menu wired to open on the target's context click. */
    private static ContextMenu wire(HeadlessGui h, Node target, Runnable... actions) {
        ContextMenu menu = new ContextMenu(h.gui);
        menu.item("First", actions.length > 0 ? actions[0] : null);
        menu.item("Second", actions.length > 1 ? actions[1] : null);
        h.gui.onContextClick(target, e -> menu.show(e.x(), e.y()));
        h.gui.root().children(target);
        h.frame();
        return menu;
    }

    private static Node page(HeadlessGui h) {
        return h.gui.box().width(Length.FILL).height(Length.FILL);
    }

    @Test
    void aRightClickOpensTheMenuAtThePointer() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node target = page(h);
            ContextMenu menu = wire(h, target);

            h.rightClick(200f, 150f);
            h.frame();

            assertTrue(menu.shown(), "a right press and release on the target opened the menu");
            var r = menu.node().layout().rect();
            assertEquals(200f, r.x(), 0.5f, "anchored at the pointer x");
            assertEquals(150f, r.y(), 0.5f, "and the pointer y");
            assertTrue(r.w() > 0f && r.h() > 0f, "the floating menu sized itself to its items");
            menu.close();
        }
    }

    /** The float is out of flow: opening the menu takes nothing from the page. */
    @Test
    void openingTheMenuReflowsNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node target = page(h);
            ContextMenu menu = wire(h, target);
            float pageH = target.layout().rect().h();

            h.rightClick(100f, 100f);
            h.frame();

            assertEquals(pageH, target.layout().rect().h(), 0.01f,
                    "the page keeps the whole viewport — the floating menu took no space from the flow");
            menu.close();
        }
    }

    /** The float is on top: a click where the menu is hits the menu, not the page underneath. */
    @Test
    void theMenuIsHitBeforeThePageItCovers() {
        try (HeadlessGui h = new HeadlessGui()) {
            boolean[] fired = {false};
            Node target = page(h);
            ContextMenu menu = wire(h, target, () -> fired[0] = true);

            h.rightClick(200f, 150f);
            h.frame();
            var first = itemRect(menu, 0);
            h.click(first.x() + first.w() / 2f, first.y() + first.h() / 2f);

            assertTrue(fired[0], "the click landed on the item drawn over the page");
            assertFalse(menu.shown(), "and choosing an item closed the menu");
            menu.close();
        }
    }

    @Test
    void escapeClosesTheMenuWhileItIsOpen() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node target = page(h);
            ContextMenu menu = wire(h, target);

            h.rightClick(200f, 150f);
            h.frame();
            assertTrue(menu.shown());

            h.tap(Key.ESCAPE);
            assertFalse(menu.shown(), "Escape is claimed while the menu is up");
            menu.close();
        }
    }

    /** The Escape claim is released on hide, so a closed menu shadows nothing. */
    @Test
    void aClosedMenuDoesNotOwnEscape() {
        try (HeadlessGui h = new HeadlessGui()) {
            boolean[] pageSawEscape = {false};
            Node target = page(h);
            ContextMenu menu = wire(h, target);
            h.gui.shortcut(Key.ESCAPE, () -> pageSawEscape[0] = true);

            h.rightClick(200f, 150f);
            h.frame();
            h.tap(Key.ESCAPE);           // closes the menu
            h.frame();
            h.tap(Key.ESCAPE);           // reaches the page's own binding

            assertTrue(pageSawEscape[0], "with the menu closed, Escape flows to whoever else wants it");
            menu.close();
        }
    }

    @Test
    void clickingAnywhereElseClosesTheMenu() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node target = page(h);
            ContextMenu menu = wire(h, target);

            h.rightClick(200f, 150f);
            h.frame();
            assertTrue(menu.shown());

            h.click(600f, 500f);   // far from the menu
            assertFalse(menu.shown(), "a left click that lands elsewhere dismisses");
            menu.close();
        }
    }

    /** Right-clicking somewhere else on the owner re-anchors the open menu instead of closing it. */
    @Test
    void aSecondRightClickMovesTheMenu() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node target = page(h);
            ContextMenu menu = wire(h, target);

            h.rightClick(200f, 150f);
            h.frame();
            h.rightClick(400f, 300f);
            h.frame();

            assertTrue(menu.shown(), "the reopening right click did not race the menu closed");
            assertEquals(400f, menu.node().layout().rect().x(), 0.5f);
            assertEquals(300f, menu.node().layout().rect().y(), 0.5f);
            menu.close();
        }
    }

    /** The layout clamps a floating node into its parent: a menu opened at the edge slides in, never crops. */
    @Test
    void aMenuOpenedAtTheEdgeStaysOnScreen() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node target = page(h);
            ContextMenu menu = wire(h, target);

            h.rightClick(795f, 595f);   // bottom-right corner of the 800x600 viewport
            h.frame();

            var r = menu.node().layout().rect();
            assertTrue(r.x() + r.w() <= 800f + 0.5f, "the menu's right edge stays inside the viewport");
            assertTrue(r.y() + r.h() <= 600f + 0.5f, "and so does its bottom");
            menu.close();
        }
    }

    @Test
    void anItemActionRunsExactlyOnce() {
        try (HeadlessGui h = new HeadlessGui()) {
            int[] runs = {0};
            Node target = page(h);
            ContextMenu menu = wire(h, target, () -> runs[0]++);

            h.rightClick(200f, 150f);
            h.frame();
            var first = itemRect(menu, 0);
            h.click(first.x() + 5f, first.y() + first.h() / 2f);

            assertEquals(1, runs[0]);
            menu.close();
        }
    }

    /** The item's rect by arithmetic on the menu's published box: items stack inside the dp(4) padding. */
    private static dev.vexelray.gui.core.layout.Rect itemRect(ContextMenu menu, int index) {
        var m = menu.node().layout().rect();
        float itemH = (m.h() - 8f) / 2f;   // two items, dp(4) padding top and bottom
        return new dev.vexelray.gui.core.layout.Rect(
                m.x() + 4f, m.y() + 4f + index * itemH, m.w() - 8f, itemH);
    }
}
