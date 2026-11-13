package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link TreeView#revealPath} walk ends with its row on screen, in a tree that animates its folders open and in
 * one that is already there.
 *
 * <p>With motion attached, the walk lands while every level it opened is still opening from nothing: asked then, the
 * scroll would be worked out against a layout where the row sits inside boxes with no height yet, and would stop at
 * the first of them. So the reveal is asked again as each of those levels settles open.
 */
class TreeRevealScrollTest {

    /** Ten folders above the way down, then two levels, the last with twenty files: the end is far below the fold. */
    private static final class DeepSource implements Listing<String> {
        @Override
        public List<String> roots() {
            List<String> roots = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                roots.add(String.format("above%02d", i));
            }
            roots.add("deep");
            return roots;
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return item.equals("deep") || item.equals("mid") || item.startsWith("above");
        }

        @Override
        public List<String> children(String item) {
            if (item.equals("deep")) {
                return List.of("mid");
            }
            if (item.equals("mid")) {
                List<String> files = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    files.add(String.format("file%02d", i));
                }
                return files;
            }
            return List.of();
        }
    }

    /** Every animation the tree starts, held until the test finishes them all, as the frame clock would. */
    private static final class Held implements Ramp {
        final List<DoubleConsumer> progress = new ArrayList<>();
        final List<Runnable> done = new ArrayList<>();

        @Override
        public void run(DoubleConsumer p, Runnable d) {
            progress.add(p);
            done.add(d);
        }

        void finishAll() {
            List<DoubleConsumer> p = new ArrayList<>(progress);
            List<Runnable> d = new ArrayList<>(done);
            progress.clear();
            done.clear();
            p.forEach(c -> c.accept(1.0));
            d.forEach(Runnable::run);
        }
    }

    private static final List<String> WAY = List.of("deep", "mid", "file19");

    @Test
    void aRevealThroughFoldersStillOpeningEndsWithItsRowOnScreen() {
        try (HeadlessGui h = new HeadlessGui()) {
            Held ramp = new Held();
            TreeView<String> tree = shortTree(h).motion(TestClocks.drive(h, ramp));

            tree.revealPath(WAY);
            for (int i = 0; i < 6; i++) {   // each level lands, and its opening is finished, before the next
                h.frame();
                ramp.finishAll();
                h.frame();
            }

            assertEquals("file19", tree.selected());
            assertInView(tree, "file19");
            tree.close();
        }
    }

    @Test
    void aRevealWithoutMotionEndsWithItsRowOnScreen() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = shortTree(h);
            tree.revealPath(WAY);
            h.frames(6);

            assertEquals("file19", tree.selected());
            assertInView(tree, "file19");
            tree.close();
        }
    }

    /** Revealing the row already selected, after scrolling away from it, brings it back: a reveal is a request to see. */
    @Test
    void revealingTheSelectedRowAgainBringsItBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = shortTree(h);
            tree.revealPath(WAY);
            h.frames(6);
            Rect box = tree.node().layout().rect();
            h.wheel(0, 40, box.x() + box.w() / 2f, box.y() + box.h() / 2f);   // back up to the top
            h.frame();
            assertEquals(0f, tree.scroller().layout().scrollY(), 0.5f, "scrolled away to the top");

            tree.revealPath(WAY);
            h.frames(3);

            assertInView(tree, "file19");
            tree.close();
        }
    }

    // --- helpers ---

    /** The tree in a box a few rows tall, so anything past the first handful is off screen. */
    private static TreeView<String> shortTree(HeadlessGui h) {
        TreeView<String> tree = Listing.tree(h, new DeepSource());
        tree.node().height(Length.rem(8));
        h.gui.root().children(tree.node());
        h.frame();
        return tree;
    }

    private static void assertInView(TreeView<String> tree, String item) {
        Rect box = tree.node().layout().rect();
        Rect row = tree.rowNode(item).layout().rect();
        assertTrue(row.y() >= box.y() - 0.5f && row.y() + row.h() <= box.y() + box.h() + 0.5f,
                "the revealed row sits inside the tree's box: row " + row + " in " + box);
    }
}
