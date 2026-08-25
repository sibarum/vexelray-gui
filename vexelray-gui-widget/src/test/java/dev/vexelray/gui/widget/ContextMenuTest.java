package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context menu, and the two primitives underneath it: right-button dispatch (a context click pairs a right
 * press and release on the same node and reports the position) and floating placement (a floating last child of
 * the root takes no space from the page, paints over it, and is hit before it).
 */
class ContextMenuTest {

    /** A full-viewport page with a target node that declares a two-item menu, and the panel that shows it. */
    private static ContextMenu wire(HeadlessGui h, Node target, Runnable... actions) {
        ContextMenu menu = new ContextMenu(h.gui);   // constructing it installs it as the tree's presenter
        h.gui.onContextMenu(target, m -> m
                .item("First", actions.length > 0 ? actions[0] : () -> { })
                .item("Second", actions.length > 1 ? actions[1] : () -> { }));
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

    /**
     * The rule that makes "a menu anywhere" composable: the innermost thing the user pointed at answers, and the
     * page it sits on — which has a menu of its own — does not get a say.
     */
    @Test
    void theNearestNodeWithAMenuOwnsTheClick() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            Node outer = page(h);
            Node inner = h.gui.box().size(Length.dp(100), Length.dp(100));
            h.gui.onContextMenu(outer, m -> m.item("Page", () -> { }));
            h.gui.onContextMenu(inner, m -> m.item("Row", () -> { }));
            h.gui.root().children(outer.children(inner));
            h.frame();

            h.rightClick(50f, 50f);
            assertEquals(List.of("Row"), labels(menu), "the row's menu, not the page's");

            h.rightClick(400f, 400f);
            assertEquals(List.of("Page"), labels(menu), "and the page's where the row is not");
            menu.close();
        }
    }

    /** No menu on the path from the click to the root means nothing opens — not an empty panel. */
    @Test
    void aClickWhereNothingDeclaresAMenuOpensNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            h.gui.root().children(page(h));
            h.frame();

            h.rightClick(200f, 150f);

            assertFalse(menu.shown(), "a right click on a page that offers nothing shows nothing");
            menu.close();
        }
    }

    /** A source that contributes nothing is the same case: the menu is what the sources say, and they said none. */
    @Test
    void aSourceThatOffersNothingOpensNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            Node target = page(h);
            h.gui.onContextMenu(target, m -> { /* nothing applies right now */ });
            h.gui.root().children(target);
            h.frame();

            h.rightClick(200f, 150f);

            assertFalse(menu.shown());
            menu.close();
        }
    }

    /**
     * Sources accumulate in registration order — a widget's defaults, then the application's additions — and each
     * may open its group with a rule without checking whether it is first: a separator that would land at either
     * end of the finished menu, or beside another, is dropped.
     */
    @Test
    void sourcesAccumulateAndAStrayRuleIsDropped() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            Node target = page(h);
            h.gui.onContextMenu(target, m -> m.separator().item("Copy", () -> { }));
            h.gui.onContextMenu(target, m -> m.separator().item("Open", () -> { }).separator());
            h.gui.root().children(target);
            h.frame();

            h.rightClick(200f, 150f);

            assertEquals(java.util.Arrays.asList("Copy", null, "Open"), labels(menu),
                    "the leading and trailing rules went; the one between the groups stayed");
            menu.close();
        }
    }

    /** Shown and greyed beats absent — and a greyed row is inert without being "somewhere else" to click. */
    @Test
    void anItemThatCannotApplyIsShownButInert() {
        try (HeadlessGui h = new HeadlessGui()) {
            int[] runs = {0};
            ContextMenu menu = new ContextMenu(h.gui);
            Node target = page(h);
            h.gui.onContextMenu(target, m -> m.item("Paste", false, () -> runs[0]++));
            h.gui.root().children(target);
            h.frame();

            h.rightClick(200f, 150f);
            assertEquals(List.of("Paste"), labels(menu));
            assertFalse(menu.items().getFirst().enabled(), "the item said it does not apply");

            var only = itemRect(menu, 0, 1);
            h.click(only.x() + only.w() / 2f, only.y() + only.h() / 2f);

            assertEquals(0, runs[0], "choosing it did nothing");
            assertTrue(menu.shown(), "and it is still the menu's own row, so the menu stayed up");
            menu.close();
        }
    }

    /**
     * The menu is built per opening, not held: that is what lets its contents describe the state of the
     * application at the instant the user asked, which is the whole reason a source is a function.
     */
    @Test
    void theMenuIsBuiltFreshEveryTimeItOpens() {
        try (HeadlessGui h = new HeadlessGui()) {
            int[] opened = {0};
            ContextMenu menu = new ContextMenu(h.gui);
            Node target = page(h);
            h.gui.onContextMenu(target, m -> m.item("Opened " + (++opened[0]), () -> { }));
            h.gui.root().children(target);
            h.frame();

            h.rightClick(200f, 150f);
            assertEquals(List.of("Opened 1"), labels(menu));

            h.rightClick(300f, 250f);
            assertEquals(List.of("Opened 2"), labels(menu), "the rows were replaced, not reused");
            menu.close();
        }
    }

    /**
     * A mark is drawn beside the label it belongs to — and the column that holds it belongs to the <em>menu</em>,
     * so a line with no mark still takes its share of it and every label starts at the same x.
     */
    @Test
    void aMarkSitsBesideItsLabelInAColumnTheWholeMenuShares() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            Node target = page(h);
            h.gui.onContextMenu(target, m -> m
                    .item("+", "Expand", () -> { })
                    .item("Properties", () -> { }));
            h.gui.root().children(target);
            h.frame();

            h.rightClick(200f, 150f);
            h.frame();

            assertEquals("+", menu.items().getFirst().icon(), "the item's mark reached the presenter");
            assertNull(menu.items().get(1).icon(), "and an item is free to have none");

            List<RetainedNode> rows = h.retained(menu.node()).children;
            assertEquals(2, rows.size());
            assertEquals(2, rows.get(0).children.size(), "a cell for the mark and a cell for the label");
            assertEquals(2, rows.get(1).children.size(), "including on the row that declined a mark");
            assertEquals(rows.get(0).children.get(1).x, rows.get(1).children.get(1).x, 0.01f,
                    "so the labels line up under each other, mark or no mark");
            menu.close();
        }
    }

    /** A menu whose items all decline a mark is the menu there was before there were any: no column at all. */
    @Test
    void aMenuOfPlainItemsReservesNothingForMarks() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node target = page(h);
            ContextMenu menu = wire(h, target);

            h.rightClick(200f, 150f);
            h.frame();

            for (RetainedNode row : h.retained(menu.node()).children) {
                assertEquals(1, row.children.size(), "a label and nothing else");
            }
            menu.close();
        }
    }

    /** The labels currently on the menu, a separator reading as {@code null}. */
    private static List<String> labels(ContextMenu menu) {
        return menu.items().stream().map(dev.vexelray.gui.core.input.MenuItem::label).toList();
    }

    /** The item's rect by arithmetic on the menu's published box: items stack inside the dp(4) padding. */
    private static dev.vexelray.gui.core.layout.Rect itemRect(ContextMenu menu, int index, int count) {
        var m = menu.node().layout().rect();
        float itemH = (m.h() - 8f) / count;
        return new dev.vexelray.gui.core.layout.Rect(
                m.x() + 4f, m.y() + 4f + index * itemH, m.w() - 8f, itemH);
    }

    /** The item's rect by arithmetic on the menu's published box: items stack inside the dp(4) padding. */
    private static dev.vexelray.gui.core.layout.Rect itemRect(ContextMenu menu, int index) {
        var m = menu.node().layout().rect();
        float itemH = (m.h() - 8f) / 2f;   // two items, dp(4) padding top and bottom
        return new dev.vexelray.gui.core.layout.Rect(
                m.x() + 4f, m.y() + 4f + index * itemH, m.w() - 8f, itemH);
    }
}
