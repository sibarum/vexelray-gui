package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tree explorer, and the two properties that make it one: children are fetched lazily and exactly once,
 * and a collapsed subtree is hidden, not removed — so it comes back with its rows, handlers and expansion
 * state intact, and the selection is never left resting on something invisible.
 */
class TreeViewTest {

    /** A small fixed hierarchy: two roots, one of which nests two levels deep. */
    private static final Map<String, List<String>> KIDS = Map.of(
            "src", List.of("main", "test"),
            "main", List.of("App.java", "Util.java"),
            "test", List.of("AppTest.java"),
            "docs", List.of());

    /** A source over {@link #KIDS} that counts children() calls, so laziness is assertable. */
    private static final class MapSource implements TreeView.Source<String> {
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

    @Test
    void rootsAreListedCollapsedAndNothingIsFetched() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            assertEquals(0, source.fetches.get(), "listing the roots asks hasChildren only, never children");
            assertNull(tree.selected(), "nothing is selected until the user or the app selects");
            tree.close();
        }
    }

    @Test
    void expandingFetchesChildrenOnceAndReexpandingDoesNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.expand("src");
            h.frame();
            assertEquals(1, source.fetches.get(), "first expansion fetches");

            tree.collapse("src");
            h.frame();
            tree.expand("src");
            h.frame();
            assertEquals(1, source.fetches.get(),
                    "the subtree was hidden, not discarded — re-expanding is prop flips, no I/O");
            tree.close();
        }
    }

    @Test
    void collapsedChildrenOccupyNoLayoutAndExpandedOnesDo() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.expand("src");
            h.frame();
            tree.select("main");
            assertEquals("main", tree.selected(), "a materialised child is selectable");

            tree.collapse("src");
            h.frame();
            assertEquals("src", tree.selected(), "collapse pulled the selection up");
            tree.select("main");   // hidden now: the select must refuse
            assertEquals("src", tree.selected(), "a hidden row cannot be selected");
            tree.close();
        }
    }

    /** Collapse with the selection inside the subtree lands the selection on the collapsed row itself. */
    @Test
    void collapsePullsTheSelectionUpOntoTheCollapsedRow() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.expand("src");
            h.frame();
            tree.select("test");
            assertEquals("test", tree.selected());

            tree.collapse("src");
            h.frame();
            assertEquals("src", tree.selected(), "the selection never rests on a hidden row");
            tree.close();
        }
    }

    @Test
    void arrowKeysWalkTheVisibleRows() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();
            tree.focus();

            h.tap(Key.DOWN);
            assertEquals("src", tree.selected(), "first Down lands on the first row");
            h.frame();
            h.tap(Key.DOWN);
            assertEquals("docs", tree.selected(), "collapsed src has no visible children between the roots");
            h.frame();
            h.tap(Key.UP);
            assertEquals("src", tree.selected());
            tree.close();
        }
    }

    @Test
    void rightExpandsThenEntersAndLeftCollapsesThenExits() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();
            tree.focus();

            h.tap(Key.DOWN);           // -> src
            h.frame();
            h.tap(Key.RIGHT);          // expand src (fetches)
            h.frame();
            assertEquals("src", tree.selected(), "expanding stays put");
            assertEquals(1, source.fetches.get());

            h.tap(Key.RIGHT);          // step into
            h.frame();
            assertEquals("main", tree.selected(), "Right on an open item enters its first child");

            h.tap(Key.LEFT);           // main is collapsed-but-expandable... Left exits to parent? No:
            h.frame();
            // main canExpand and is not expanded, so Left steps out to the parent.
            assertEquals("src", tree.selected(), "Left on a closed item exits to the parent");

            h.tap(Key.LEFT);           // src is expanded: Left collapses it
            h.frame();
            assertEquals("src", tree.selected());
            h.tap(Key.DOWN);
            h.frame();
            assertEquals("docs", tree.selected(), "the subtree is closed again");
            tree.close();
        }
    }

    @Test
    void keyboardWalksIntoAnExpandedSubtreeInDocumentOrder() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();
            tree.expand("src");
            h.frame();
            tree.focus();

            h.tap(Key.DOWN);
            assertEquals("src", tree.selected());
            h.frame();
            h.tap(Key.DOWN);
            assertEquals("main", tree.selected());
            h.frame();
            h.tap(Key.DOWN);
            assertEquals("test", tree.selected());
            h.frame();
            h.tap(Key.DOWN);
            assertEquals("docs", tree.selected());
            h.frame();
            h.tap(Key.END);
            assertEquals("docs", tree.selected(), "End clamps at the last visible row");
            h.frame();
            h.tap(Key.HOME);
            assertEquals("src", tree.selected());
            tree.close();
        }
    }

    @Test
    void enterActivatesTheSelectedItem() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            String[] activated = {null};
            TreeView<String> tree = new TreeView<>(h.gui, source).onActivate(item -> activated[0] = item);
            h.gui.root().children(tree.node());
            h.frame();
            tree.focus();

            h.tap(Key.DOWN);
            h.frame();
            h.tap(Key.ENTER);
            assertEquals("src", activated[0]);
            tree.close();
        }
    }

    @Test
    void clickingARowSelectsIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            String[] selectedSeen = {null};
            TreeView<String> tree = new TreeView<>(h.gui, source).onSelect(item -> selectedSeen[0] = item);
            h.gui.root().children(tree.node());
            h.frame();

            tree.expand("src");
            h.frame();
            h.frame();   // second frame: materialised rows get their layout rects

            var rows = tree.node().layout();
            // Click the second visible row ("main"): rows are uniform, so aim one row below the first.
            var treeRect = rows.rect();
            float rowH = rowHeight(h, tree);
            h.click(treeRect.x() + treeRect.w() / 2f, firstRowCenterY(h, tree) + rowH);
            h.frame();
            assertEquals("main", tree.selected());
            assertEquals("main", selectedSeen[0], "the app heard about it on the handler lane");
            tree.close();
        }
    }

    /** An item whose source lists no children demotes to a leaf instead of showing an empty open branch. */
    @Test
    void anEmptyBranchDemotesToALeaf() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.expand("docs");   // KIDS lists docs as expandable but empty
            h.frame();
            tree.focus();
            h.tap(Key.DOWN);       // src
            h.frame();
            h.tap(Key.DOWN);       // docs — nothing appeared under it
            assertEquals("docs", tree.selected());
            h.frame();
            h.tap(Key.DOWN);
            assertEquals("docs", tree.selected(), "nothing below docs: it is the last row");
            tree.close();
        }
    }

    @Test
    void expandIsTopDownAProgrammaticExpandOfAnUnrealisedItemIsANoOp() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.expand("main");   // src was never expanded: main has no row yet
            h.frame();
            assertEquals(0, source.fetches.get(), "no row, no fetch — expansion works top-down");
            tree.close();
        }
    }

    /** A context click selects the row first — the menu that opens is about the row under the pointer. */
    @Test
    void aContextClickSelectsTheRowAndReportsItWithThePointer() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            String[] contextItem = {null};
            float[] at = {-1f, -1f};
            TreeView<String> tree = new TreeView<>(h.gui, source)
                    .onContext((item, e) -> {
                        contextItem[0] = item;
                        at[0] = e.x();
                        at[1] = e.y();
                    });
            h.gui.root().children(tree.node());
            h.frame();

            float x = tree.node().layout().rect().x() + 100f;
            float y = firstRowCenterY(h, tree);
            h.rightClick(x, y);
            h.frame();

            assertEquals("src", tree.selected(), "the row under the pointer became the selection");
            assertEquals("src", contextItem[0], "and the app was told which item, with the pointer position");
            assertEquals(x, at[0], 0.5f);
            assertEquals(y, at[1], 0.5f);
            tree.close();
        }
    }

    // --- geometry helpers: read the published layout rather than guessing pixels ---

    private static float rowHeight(HeadlessGui h, TreeView<String> tree) {
        // ROW_EM at the harness's 16px root em: uniform for every row by construction.
        return 1.75f * 16f;
    }

    private static float firstRowCenterY(HeadlessGui h, TreeView<String> tree) {
        var r = tree.node().layout();
        return r.rect().y() + 4f /* padding dp(4) at dpi 1 */ + rowHeight(h, tree) / 2f;
    }
}
