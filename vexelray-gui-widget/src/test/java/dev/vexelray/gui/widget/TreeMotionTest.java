package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expanding and collapsing with motion attached: the subtree grows and shrinks, and everything below it slides.
 *
 * <p><b>This one is a layout animation, and that is the point of it.</b> The transforms behind the tab transition
 * are drawing facts that reflow nothing; a subtree opening has to actually take up room, or the rows beneath it
 * do not move, the tree's own extent is wrong, and it scrolls as though the subtree were still shut. So the
 * assertions here are about laid-out geometry — where rows actually are — rather than about props.
 *
 * <p>The tree is driven by hand through a {@link Ramp} the test steps itself, which is possible because
 * {@code TreeView} names no clock.
 */
class TreeMotionTest {

    private static final Map<String, List<String>> KIDS = Map.of(
            "src", List.of("main", "test"),
            "main", List.of("App.java", "Util.java"),
            "test", List.of("AppTest.java"),
            "docs", List.of());

    private static final class MapSource implements TreeView.Source<String> {
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
            return KIDS.getOrDefault(item, List.of());
        }
    }

    /** A ramp the test steps by hand, standing in for the frame clock. */
    private static final class Steps implements Ramp {
        DoubleConsumer progress;
        Runnable done;

        @Override
        public void run(DoubleConsumer progress, Runnable done) {
            this.progress = progress;
            this.done = done;
        }

        void to(double p) {
            progress.accept(p);
        }

        void finish() {
            done.run();
        }
    }

    /** Where the "docs" row sits — the row below the subtree, and therefore the thing that has to slide. */
    private static float docsY(HeadlessGui h, TreeView<String> tree) {
        return tree.rowNode("docs").layout().rect().y();
    }

    @Test
    void openingSlidesTheRowsBelowRatherThanJumpingThem() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource()).motion(ramp);
            h.gui.root().children(tree.node());
            h.frame();
            float shut = docsY(h, tree);

            tree.expand("src");
            h.frame();
            float atStart = docsY(h, tree);
            assertEquals(shut, atStart, 0.5f,
                    "the row below has not moved yet: an animation that begins at its end state is a jump with a "
                            + "delay after it");

            ramp.to(0.5);
            h.frame();
            float half = docsY(h, tree);

            ramp.to(1.0);
            h.frame();
            ramp.finish();
            h.frame();
            float open = docsY(h, tree);

            assertTrue(open > shut, "an open subtree pushes the row below it down");
            assertTrue(half > shut && half < open,
                    "and it gets there by passing through the middle — was " + half + ", between " + shut
                            + " and " + open);
            tree.close();
        }
    }

    /**
     * At rest the container carries no height of its own. An explicit height is a promise about content that has
     * not happened yet, and a box still holding the number that was right when it stopped animating would clip
     * the next child added to it.
     */
    @Test
    void aSettledSubtreeIsSizedByItsContentAgain() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource()).motion(ramp);
            h.gui.root().children(tree.node());
            h.frame();

            tree.expand("src");
            h.frame();
            ramp.to(1.0);
            ramp.finish();
            h.frame();
            float openedOnce = docsY(h, tree);

            // Open a nested subtree, which adds rows the settled outer box never sized itself for.
            tree.expand("main");
            h.frame();
            ramp.to(1.0);
            ramp.finish();
            h.frame();

            assertTrue(docsY(h, tree) > openedOnce,
                    "the outer subtree grew to hold the rows the inner one added, so it was not still pinned to "
                            + "the height it settled at");
            tree.close();
        }
    }

    /** Collapsing runs the same machinery backwards, and the rows below come back to exactly where they began. */
    @Test
    void collapsingReturnsTheRowsBelowToWhereTheyStarted() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource()).motion(ramp);
            h.gui.root().children(tree.node());
            h.frame();
            float shut = docsY(h, tree);

            tree.expand("src");
            h.frame();
            ramp.to(1.0);
            ramp.finish();
            h.frame();
            assertTrue(docsY(h, tree) > shut);

            tree.collapse("src");
            h.frame();
            ramp.to(0.5);
            h.frame();
            assertTrue(docsY(h, tree) > shut, "still on its way down at halfway");

            ramp.to(1.0);
            ramp.finish();
            h.frame();
            assertEquals(shut, docsY(h, tree), 0.5f, "and back exactly where it was, not near it");
            tree.close();
        }
    }

    /**
     * A subtree toggled while it is still opening reverses from where it got to.
     *
     * <p>The alternative — restarting from the far end — makes a fast second click show the subtree jumping to a
     * state it was never in and then travelling back from there, which is more startling than no animation at
     * all. The superseded ramp is still running and still calling progress; it has to find nothing to write.
     */
    @Test
    void togglingMidAnimationReversesFromWhereItGotTo() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource()).motion(ramp);
            h.gui.root().children(tree.node());
            h.frame();
            float shut = docsY(h, tree);

            tree.expand("src");
            h.frame();
            ramp.to(0.5);
            h.frame();
            float caught = docsY(h, tree);
            assertTrue(caught > shut, "half open");

            DoubleConsumer superseded = ramp.progress;
            tree.collapse("src");
            h.frame();
            assertEquals(caught, docsY(h, tree), 0.5f, "the reversal starts from where the opening got to");

            superseded.accept(1.0);   // the old ramp, still running, arriving late
            h.frame();
            assertEquals(caught, docsY(h, tree), 0.5f, "and the ramp it superseded cannot move it");

            ramp.to(1.0);
            ramp.finish();
            h.frame();
            assertEquals(shut, docsY(h, tree), 0.5f, "the reversal still finishes shut");
            tree.close();
        }
    }

    /** With no ramp installed the tree behaves exactly as it did before there was any motion. */
    @Test
    void withNoMotionInstalledExpandingIsStillInstant() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            h.frame();
            float shut = docsY(h, tree);

            tree.expand("src");
            h.frame();

            assertTrue(docsY(h, tree) > shut, "open in the same frame, with no clock anywhere in sight");
            tree.close();
        }
    }
}
