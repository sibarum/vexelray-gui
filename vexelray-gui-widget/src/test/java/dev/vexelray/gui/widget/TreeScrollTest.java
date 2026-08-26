package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tree taller than its box follows its own selection. Every route to a selection goes through one method, so
 * this is one rule and not four: the arrow keys, Home/End, a search landing on a match, and the collapse that
 * pulls the selection up out of a subtree all leave the selected row on screen.
 */
class TreeScrollTest {

    /** Twenty roots, no children: enough rows to overflow a short box, and nothing to fetch. */
    private static final class FlatSource implements TreeView.Source<String> {
        @Override
        public List<String> roots() {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                items.add(String.format("item%02d", i));
            }
            return items;
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return false;
        }

        @Override
        public List<String> children(String item) {
            return List.of();
        }
    }

    @Test
    void walkingPastTheFoldBringsTheSelectionWithIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = shortTree(h);
            assertTrue(tree.scroller().layout().overflowY(), "twenty rows in a box that holds a few");
            assertEquals(0f, tree.scroller().layout().scrollY(), 0.5f, "and it opens at the top");

            tree.focus();
            for (int i = 0; i < 8; i++) {
                h.tap(Key.DOWN);
                h.frame();
            }

            assertEquals("item07", tree.selected());
            assertTrue(tree.scroller().layout().scrollY() > 0f, "the tree scrolled to keep up");
            assertInView(tree, "item07");
        }
    }

    /** End jumps to the last row, which is the furthest the scroller can be asked to go. */
    @Test
    void endJumpsToTheLastRowAndTheScrollerGoesWithIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = shortTree(h);
            tree.focus();
            h.tap(Key.END);
            h.frame();

            assertEquals("item19", tree.selected());
            assertInView(tree, "item19");

            h.tap(Key.HOME);
            h.frame();
            assertEquals("item00", tree.selected());
            assertEquals(0f, tree.scroller().layout().scrollY(), 0.5f, "and back to the top for the first row");
        }
    }

    /** A match found below the fold is one the user is looking at, not one the tree merely selected. */
    @Test
    void aSearchThatLandsBelowTheFoldScrollsToItsMatch() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = shortTree(h);
            tree.focus();
            h.chord(Key.F, Key.LEFT_CONTROL);
            h.frame();
            h.type("item18");
            h.frame();

            assertEquals("item18", tree.selected());
            assertInView(tree, "item18");
        }
    }

    /**
     * The wheel over a row scrolls the rows. It reaches the scroller by being the nearest scrollable ancestor of
     * whatever was under the pointer, so moving the rows into a box of their own changed where the scrolling
     * happens and nothing about how it is asked for.
     */
    @Test
    void theWheelOverARowScrollsTheRowsAndNotTheFrame() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = shortTree(h);
            Rect box = tree.node().layout().rect();
            h.wheel(0, -3, box.x() + box.w() / 2f, box.y() + box.h() / 2f);

            assertTrue(tree.scroller().layout().scrollY() > 0f, "the rows moved");
            assertEquals(0f, tree.node().layout().scrollY(), 0.5f, "and the frame around them did not");
            tree.close();
        }
    }

    /**
     * The find bar does not scroll away with the rows. It is chrome about the tree, not a row of it, so it lives
     * above the scroller rather than inside it — and the rows move under it while it stays where it was put.
     */
    @Test
    void theFindBarStaysPutWhileTheRowsScrollUnderIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = shortTree(h);
            tree.focus();
            h.chord(Key.F, Key.LEFT_CONTROL);
            h.frame();
            Rect opened = tree.findBar().layout().rect();
            assertTrue(opened.h() > 0f, "the bar has a strip of its own");

            tree.focus();          // back to the rows; the bar keeps its query and its place
            h.tap(Key.END);
            h.frame();
            h.frame();

            assertEquals("item19", tree.selected());
            assertTrue(tree.scroller().layout().scrollY() > 0f, "the rows really did scroll");
            Rect bar = tree.findBar().layout().rect();
            assertEquals(opened.y(), bar.y(), 0.5f, "and the bar is exactly where it was");
            Rect box = tree.node().layout().rect();
            assertTrue(bar.y() >= box.y() - 0.5f && bar.y() + bar.h() <= box.y() + box.h() + 0.5f,
                    "still inside the tree's box: bar " + bar + " in " + box);
            tree.close();
        }
    }

    // --- helpers ---

    /** The tree in a box a few rows tall, so anything past the first handful is off screen. */
    private static TreeView<String> shortTree(HeadlessGui h) {
        TreeView<String> tree = new TreeView<>(h.gui, new FlatSource());
        tree.node().height(Length.rem(8));   // ~4 rows of the 1.75em the tree gives each one
        h.gui.root().children(tree.node());
        h.frame();
        return tree;
    }

    /** Assert {@code item}'s row is inside the tree's own box — which is what "on screen" means for a row. */
    private static void assertInView(TreeView<String> tree, String item) {
        Rect box = tree.node().layout().rect();
        Rect row = tree.rowNode(item).layout().rect();
        assertTrue(row.y() >= box.y() - 0.5f && row.y() + row.h() <= box.y() + box.h() + 0.5f,
                "the selected row sits inside the tree's box: row " + row + " in " + box);
    }
}
