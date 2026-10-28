package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.input.MenuItem;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands on a tree row's menu: the two the tree ships and runs, the ones an application declares and is told
 * were chosen, and the rule over the tree's walks — <b>one at a time</b>. Expand and Collapse are recursive on
 * purpose (the single-level flip is what the disclosure glyph and the arrow keys already are). What a row offers is
 * its entry's to say, so a right click runs nothing of the application's.
 */
class TreeActionsTest {

    private static final Map<String, List<String>> KIDS = Map.of(
            "src", List.of("main", "test"),
            "main", List.of("App.java", "Util.java"),
            "test", List.of("AppTest.java"),
            "docs", List.of());

    /** A source over {@link #KIDS} that counts its fetches, so what a walk actually asked for is assertable. */
    private static class MapSource implements Listing<String> {
        final AtomicInteger fetches = new AtomicInteger();

        @Override
        public List<String> roots() {
            return List.of("src", "docs");
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return KIDS.containsKey(item);
        }

        @Override
        public List<String> children(String item) {
            fetches.incrementAndGet();
            return KIDS.getOrDefault(item, List.of());
        }
    }

    /**
     * A chain deep enough that walking it is several fetches long — l0 → l1 → … → l4 — with a hook run inside
     * {@code children}. That is where a walk over a real hierarchy spends its time, so it is where a test has to
     * be able to stand something else up.
     */
    private static final class Chain implements Listing<String> {
        final AtomicInteger fetches = new AtomicInteger();
        volatile Runnable duringFetch = () -> { };

        @Override
        public List<String> roots() {
            return List.of("l0");
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return !item.equals("l4");
        }

        @Override
        public List<String> children(String item) {
            fetches.incrementAndGet();
            duringFetch.run();
            return List.of("l" + (Integer.parseInt(item.substring(1)) + 1));
        }
    }

    /**
     * Expand opens the whole subtree, fetching each level as it reaches it; Collapse shuts it all the way down,
     * which is what makes re-opening the top open only the top.
     */
    @Test
    void expandOpensTheWholeSubtreeAndCollapseShutsItAllTheWayDown() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = Listing.tree(h, source);
            h.gui.root().children(tree.node());
            h.frame();

            rightClickRow(h, tree, "src");
            choose(h, "Expand");
            h.frame();

            assertEquals(List.of("src", "main", "App.java", "Util.java", "test", "AppTest.java", "docs"),
                    visibleRows(h, tree), "every level opened, in document order");
            assertEquals(3, source.fetches.get(), "one fetch per level that had children to ask for");

            rightClickRow(h, tree, "src");
            choose(h, "Collapse");
            h.frame();
            assertEquals(List.of("src", "docs"), visibleRows(h, tree), "the subtree is shut");

            tree.expand("src");   // one level, the way the disclosure control opens it
            h.frame();
            assertEquals(List.of("src", "main", "test", "docs"), visibleRows(h, tree),
                    "and it came back shut all the way down: opening the top opened only the top");
            assertEquals(3, source.fetches.get(), "nothing was fetched twice — the rows were hidden, not discarded");
            tree.close();
        }
    }

    /** Shown and greyed, never taken away: the tree's own two describe what this row is, either way. */
    @Test
    void theTreesOwnCommandsAreEnabledByWhatTheRowIs() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = Listing.tree(h, source);
            h.gui.root().children(tree.node());
            h.frame();

            rightClickRow(h, tree, "src");
            assertEquals(List.of("Expand", "Collapse"), labels(h), "the menu a tree answers with on its own");
            assertSame(dev.vexelray.gui.icons.Icon.PLUS, item(h, "Expand").mark(), "marked open-all and shut-all");
            assertSame(dev.vexelray.gui.icons.Icon.MINUS, item(h, "Collapse").mark());
            assertTrue(enabled(h, "Expand"), "a shut folder can be expanded");
            assertFalse(enabled(h, "Collapse"), "and is already collapsed");

            choose(h, "Expand");
            h.frame();

            rightClickRow(h, tree, "src");
            assertTrue(enabled(h, "Collapse"), "an open folder can be shut");

            rightClickRow(h, tree, "App.java");
            assertEquals(List.of("Expand", "Collapse"), labels(h), "still on the menu on a leaf");
            assertFalse(enabled(h, "Expand"), "which has nothing to expand");
            assertFalse(enabled(h, "Collapse"), "and nothing to collapse");
            tree.close();
        }
    }

    /**
     * An application's command is declared, and each row's entry says which of three things it is there:
     * choosable, greyed, or not on the menu at all. Choosing it runs nothing; it is announced, with the item.
     */
    @Test
    void anAddedActionIsGreyedOrDroppedPerItemAndAnnounced() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource() {
                @Override
                public TreeView.Entry entry(String item) {
                    TreeView.Entry entry = super.entry(item);
                    if (item.equals("src")) {
                        return entry.hides("delete");
                    }
                    return item.endsWith(".java") ? entry : entry.greys("delete");
                }
            };
            TreeView<String> tree = Listing.tree(h, source)
                    .action(TreeView.Action.of("delete", "×", "Delete"));
            List<TreeView.Chosen> chosen = hear(h, tree);
            h.gui.root().children(tree.node());
            h.frame();
            tree.expandAll("src");
            h.frame();

            rightClickRow(h, tree, "src");
            assertEquals(List.of("Expand", "Collapse"), labels(h),
                    "not on the menu at all for a row the command has no business on");

            rightClickRow(h, tree, "main");
            assertEquals(Arrays.asList("Expand", "Collapse", null, "Delete"), labels(h),
                    "behind a rule, after the tree's own");
            assertFalse(enabled(h, "Delete"), "shown and greyed: it belongs on this row, it just does not apply");

            rightClickRow(h, tree, "App.java");
            assertTrue(enabled(h, "Delete"));
            assertEquals("×", item(h, "Delete").icon(), "and it is drawn with its own mark");
            choose(h, "Delete");
            assertEquals(List.of(new TreeView.Chosen(tree.address(), "delete", "App.java")), chosen,
                    "announced with the id and the item it was chosen on");
            tree.close();
        }
    }

    /** What a row offers is a fact the listing states, so a changed fact arrives the way a changed label does. */
    @Test
    void aRefreshChangesWhatARowOffers() {
        try (HeadlessGui h = new HeadlessGui()) {
            boolean[] locked = {true};
            MapSource source = new MapSource() {
                @Override
                public TreeView.Entry entry(String item) {
                    return locked[0] ? super.entry(item).greys("delete") : super.entry(item);
                }
            };
            TreeView<String> tree = Listing.tree(h, source).action(TreeView.Action.of("delete", "Delete"));
            h.gui.root().children(tree.node());
            h.frame();

            rightClickRow(h, tree, "docs");
            assertFalse(enabled(h, "Delete"));

            locked[0] = false;
            h.bus.publish(TreeView.REFRESH, new TreeView.Refresh(tree.address()));
            h.frame();
            h.frame();

            rightClickRow(h, tree, "docs");
            assertTrue(enabled(h, "Delete"), "the entry the refresh brought says so now");
            tree.close();
        }
    }

    /** The tree's own lines are offered by the tree's state, and an entry can still take one off its row. */
    @Test
    void anEntryCanTakeTheTreesOwnLineOffItsRow() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource() {
                @Override
                public TreeView.Entry entry(String item) {
                    return item.equals("docs") ? super.entry(item).hides(TreeView.EXPAND) : super.entry(item);
                }
            };
            TreeView<String> tree = Listing.tree(h, source);
            h.gui.root().children(tree.node());
            h.frame();

            rightClickRow(h, tree, "docs");
            assertEquals(List.of("Collapse"), labels(h));
            rightClickRow(h, tree, "src");
            assertEquals(List.of("Expand", "Collapse"), labels(h), "and only off its own");
            tree.close();
        }
    }

    /** An id is what an entry and a {@code Chosen} name an action by, so it means one line. */
    @Test
    void anActionsIdIsItsOwn() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource()).action(TreeView.Action.of("delete", "Delete"));
            assertThrows(IllegalArgumentException.class, () -> tree.action(TreeView.Action.of("delete", "Remove")),
                    "two lines one Chosen could not tell apart");
            assertThrows(IllegalArgumentException.class, () -> tree.action(TreeView.Action.of(TreeView.EXPAND, "More")),
                    "nor one of the tree's own");
            assertThrows(IllegalArgumentException.class, () -> TreeView.Action.of(" ", "Blank"));
            tree.close();
        }
    }

    /**
     * The one-at-a-time rule, staged where a walk actually spends its time: a second action triggered during a
     * fetch takes the tree over, and the walk stops at the level it had reached.
     */
    @Test
    void theNextActionTakesOverFromTheOneStillRunning() {
        try (HeadlessGui h = new HeadlessGui()) {
            Chain source = new Chain();
            TreeView<String> tree = Listing.tree(h, source);
            h.gui.root().children(tree.node());
            h.frame();

            source.duringFetch = () -> {
                if (source.fetches.get() == 2) {
                    tree.collapseAll("l0");   // the user chose something else while the tree was still unfolding
                }
            };

            rightClickRow(h, tree, "l0");
            choose(h, "Expand");
            h.frame();

            assertEquals(2, source.fetches.get(), "the walk stopped at the level it was superseded on");
            assertNull(tree.rowNode("l3"), "so the level below was never asked for");
            assertEquals(List.of("l0"), visibleRows(h, tree), "and what the collapse did stands");
            tree.close();
        }
    }

    /**
     * The disclosure glyph and the arrow keys are actions too. A row shut by hand while a recursive expand is
     * still unfolding stays shut — a walk that carried on would re-open it one level later.
     */
    @Test
    void aFlipByHandTakesTheTreeOverFromARunningWalk() {
        try (HeadlessGui h = new HeadlessGui()) {
            Chain source = new Chain();
            TreeView<String> tree = Listing.tree(h, source);
            h.gui.root().children(tree.node());
            h.frame();

            source.duringFetch = () -> {
                if (source.fetches.get() == 2) {
                    tree.collapse("l0");   // the single-level flip a click on the glyph makes
                }
            };

            rightClickRow(h, tree, "l0");
            choose(h, "Expand");
            h.frame();

            assertEquals(2, source.fetches.get(), "the walk gave way to the flip");
            assertEquals(List.of("l0"), visibleRows(h, tree), "and the row shut by hand stayed shut");
            tree.close();
        }
    }
    /**
     * Choosing an application's line stops the walk the tree is running, as choosing one of its own does: an
     * expand-all does not carry on unfolding underneath the thing the user chose instead.
     */
    @Test
    void choosingAnApplicationsLineStopsTheWalk() {
        try (HeadlessGui h = new HeadlessGui()) {
            Chain source = new Chain();
            TreeView<String> tree = Listing.tree(h, source).action(TreeView.Action.of("count", "Count"));
            List<TreeView.Chosen> chosen = hear(h, tree);
            h.gui.root().children(tree.node());
            h.frame();

            rightClickRow(h, tree, "l0");
            MenuItem count = item(h, "Count");
            menu(h).hide();
            source.duringFetch = () -> {
                if (source.fetches.get() == 2) {
                    count.action().run();   // the user chose Count while the tree was still unfolding
                }
            };
            tree.expandAll("l0");
            h.frame();
            h.frame();
            h.frame();

            assertEquals(2, source.fetches.get(), "the walk stopped at the level it was superseded on");
            assertNull(tree.rowNode("l3"), "so the level below was never asked for");
            assertEquals(1, chosen.size(), "and the choice was announced");
            tree.close();
        }
    }

    /** Every {@code Chosen} for {@code tree}, in order. */
    private static List<TreeView.Chosen> hear(HeadlessGui h, TreeView<String> tree) {
        List<TreeView.Chosen> heard = new ArrayList<>();
        h.bus.subscribe(TreeView.CHOSEN, c -> {
            if (c.tree().equals(tree.address())) {
                heard.add(c);
            }
        });
        return heard;
    }

    // --- helpers: drive the menu the way the presenter does, and read the tree the way the user does ---

    /**
     * Right-click a row by its published box, so nothing here guesses at a pixel — with any open menu dismissed
     * first, because an open one is a panel floating over the rows below the one it belongs to, and a click aimed
     * at a covered row lands on the menu (which is what it does on screen too).
     */
    private static void rightClickRow(HeadlessGui h, TreeView<String> tree, String item) {
        menu(h).hide();
        h.frame();   // hiding is a prop write: it reaches the tree the press is hit-tested against on the next frame
        var r = tree.rowNode(item).layout().rect();
        h.rightClick(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
        h.frame();
    }

    /**
     * Choose an item by label. Exactly what the presenter's row handler does, in its order: the menu goes away
     * first, then the action runs — and the order matters here as much as it does on screen, because a menu left
     * up is a floating panel over the row the next right click is aimed at.
     */
    private static void choose(HeadlessGui h, String label) {
        MenuItem chosen = item(h, label);
        menu(h).hide();
        chosen.action().run();
    }

    private static MenuItem item(HeadlessGui h, String label) {
        return menu(h).items().stream().filter(i -> label.equals(i.label())).findFirst().orElseThrow();
    }

    private static boolean enabled(HeadlessGui h, String label) {
        return item(h, label).enabled();
    }

    /** The labels currently on the menu, a separator reading as {@code null}. */
    private static List<String> labels(HeadlessGui h) {
        return menu(h).items().stream().map(MenuItem::label).toList();
    }

    private static ContextMenu menu(HeadlessGui h) {
        return (ContextMenu) Seams.of(h.gui).menus();
    }

    /** The rows the tree considers visible, read the way the user reads them: Home, then Down to the end. */
    private static List<String> visibleRows(HeadlessGui h, TreeView<String> tree) {
        tree.focus();
        h.tap(Key.HOME);
        h.frame();
        List<String> rows = new ArrayList<>();
        for (String at = tree.selected(); at != null && !rows.contains(at); at = tree.selected()) {
            rows.add(at);
            h.tap(Key.DOWN);   // stops moving at the last row, which is what ends the walk
            h.frame();
        }
        return rows;
    }
}
