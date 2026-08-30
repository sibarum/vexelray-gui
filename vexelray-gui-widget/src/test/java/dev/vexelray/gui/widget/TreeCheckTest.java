package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkboxes on rows: the tree draws them and reports the clicks, the application owns what is ticked.
 *
 * <p>The same split as the hierarchy, for the same reason — a tick is a fact about the application's model, so
 * the tree can no more own it than it owns the tree. What that buys is the thing these tests are mostly about:
 * <b>cascading is the application's</b>, and every one of the incompatible policies real applications want is
 * expressible by answering {@code state} differently. A staging area cascades down; a filter does not; a
 * permission tree propagates up. A widget that picked one would be wrong for the other two.
 */
class TreeCheckTest {

    private static final Map<String, List<String>> KIDS = new LinkedHashMap<>(Map.of(
            "src", List.of("main", "test"),
            "docs", List.of()));

    private static final class MapSource implements TreeView.Source<String> {

        @Override
        public List<String> roots() {
            return List.of("src", "docs", "notes.txt");
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return !KIDS.getOrDefault(item, List.of()).isEmpty();
        }

        @Override
        public List<String> children(String item) {
            return List.copyOf(KIDS.getOrDefault(item, List.of()));
        }
    }

    /** A plain set of ticked items: no cascade at all, which is a filter's policy. */
    private static final class Ticks implements TreeView.Checkable<String> {

        final Set<String> on = new LinkedHashSet<>();
        final List<String> clicks = new ArrayList<>();

        @Override
        public TreeView.Check state(String item) {
            return on.contains(item) ? TreeView.Check.ON : TreeView.Check.OFF;
        }

        @Override
        public void toggled(String item) {
            clicks.add(item);
            if (!on.remove(item)) {
                on.add(item);
            }
        }
    }

    private static void click(HeadlessGui h, float x, float y) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    /** The middle of {@code item}'s checkbox — the target a user aims at. */
    private static float[] boxOf(TreeView<String> tree, String item) {
        Rect box = tree.checkBoxNode(item).layout().rect();
        assertTrue(box != null && box.w() > 0f, "the box must be laid out to be clicked");
        return new float[]{box.x() + box.w() / 2f, box.y() + box.h() / 2f};
    }

    @Test
    @DisplayName("a tree with no Checkable shows no boxes and reserves no width for them")
    void boxesAreAbsentUntilAskedFor() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            h.frame();

            Rect box = tree.checkBoxNode("docs").layout().rect();
            assertTrue(box == null || box.w() == 0f,
                    "a hidden node is out of the layout, so an unchecked tree pays no width");
            tree.close();
        }
    }

    @Test
    @DisplayName("clicking a box reports the item and redraws from the model's answer")
    void clickingABoxTogglesThroughTheApplication() {
        try (HeadlessGui h = new HeadlessGui()) {
            Ticks ticks = new Ticks();
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable(ticks);
            h.frame();

            float[] at = boxOf(tree, "notes.txt");
            click(h, at[0], at[1]);
            h.frame();

            assertEquals(List.of("notes.txt"), ticks.clicks);
            assertTrue(ticks.on.contains("notes.txt"));
            assertEquals(TreeView.Check.ON, ticks.state("notes.txt"));

            click(h, at[0], at[1]);
            h.frame();
            assertFalse(ticks.on.contains("notes.txt"), "and again turns it off");
            tree.close();
        }
    }

    @Test
    @DisplayName("clicking a box selects its row, so the keyboard carries on from there")
    void tickingSelects() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable(new Ticks());
            h.frame();

            float[] at = boxOf(tree, "docs");
            click(h, at[0], at[1]);
            h.frame();

            assertEquals("docs", tree.selected());
            tree.close();
        }
    }

    @Test
    @DisplayName("Space toggles the selected row")
    void spaceTogglesTheSelection() {
        try (HeadlessGui h = new HeadlessGui()) {
            Ticks ticks = new Ticks();
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable(ticks);
            tree.select("docs");
            tree.focus();
            h.frame();

            h.bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.SPACE, 0));
            h.frame();

            assertEquals(List.of("docs"), ticks.clicks, "the chord acts on the selection, which is the tree's");
            tree.close();
        }
    }

    /**
     * The point of leaving cascading out. This model ticks a folder by ticking everything under it and reports
     * a partly-ticked folder as MIXED — and the widget needed no notion of any of that.
     */
    @Test
    @DisplayName("a cascading model is expressible by answering state, with no help from the widget")
    void cascadingIsTheApplicationsToDefine() {
        Set<String> on = new LinkedHashSet<>();
        TreeView.Checkable<String> staged = new TreeView.Checkable<>() {

            @Override
            public TreeView.Check state(String item) {
                List<String> kids = KIDS.getOrDefault(item, List.of());
                if (kids.isEmpty()) {
                    return on.contains(item) ? TreeView.Check.ON : TreeView.Check.OFF;
                }
                long ticked = kids.stream().filter(on::contains).count();
                if (ticked == 0) {
                    return TreeView.Check.OFF;
                }
                return ticked == kids.size() ? TreeView.Check.ON : TreeView.Check.MIXED;
            }

            @Override
            public void toggled(String item) {
                List<String> kids = KIDS.getOrDefault(item, List.of());
                boolean turningOn = state(item) != TreeView.Check.ON;
                if (kids.isEmpty()) {
                    if (turningOn) {
                        on.add(item);
                    } else {
                        on.remove(item);
                    }
                    return;
                }
                for (String kid : kids) {
                    if (turningOn) {
                        on.add(kid);
                    } else {
                        on.remove(kid);
                    }
                }
            }
        };

        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable(staged);
            tree.expand("src");
            h.frame();

            float[] child = boxOf(tree, "main");
            click(h, child[0], child[1]);
            h.frame();

            assertEquals(TreeView.Check.MIXED, staged.state("src"),
                    "one of two children ticked, and the folder says so");

            float[] folder = boxOf(tree, "src");
            click(h, folder[0], folder[1]);
            h.frame();

            assertEquals(TreeView.Check.ON, staged.state("src"), "ticking a mixed folder fills it");
            assertTrue(on.containsAll(List.of("main", "test")));
            tree.close();
        }
    }

    /** A tick changed from somewhere else shows when the application says so — the same seam refresh() is. */
    @Test
    @DisplayName("recheck redraws marks the tree was never told about")
    void recheckPicksUpAChangeMadeElsewhere() {
        try (HeadlessGui h = new HeadlessGui()) {
            Ticks ticks = new Ticks();
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable(ticks);
            h.frame();

            ticks.on.add("docs");          // a "select all" button, a background job, another view
            tree.recheck();
            h.frame();

            assertTrue(tree.checkBoxNode("docs").layout().rect().w() > 0f);
            assertEquals(TreeView.Check.ON, ticks.state("docs"));
            assertTrue(ticks.clicks.isEmpty(), "nothing was clicked; the tree was only asked again");
            tree.close();
        }
    }

    /**
     * The three states are told apart by <b>shape</b>, not by size.
     *
     * <p>A checkbox is read at a glance, and at a glance a small square is a square — so MIXED is a bar, wider
     * than it is tall, rather than a smaller tick. This asserts the drawn geometry rather than the enum,
     * because the enum being right is not the part that could go wrong.
     */
    @Test
    @DisplayName("off, on and mixed are three different pictures")
    void theThreeStatesLookDifferent() {
        try (HeadlessGui h = new HeadlessGui()) {
            Ticks ticks = new Ticks();
            var mixed = new java.util.concurrent.atomic.AtomicBoolean();
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable(new TreeView.Checkable<>() {

                @Override
                public TreeView.Check state(String item) {
                    return mixed.get() ? TreeView.Check.MIXED : ticks.state(item);
                }

                @Override
                public void toggled(String item) {
                    ticks.toggled(item);
                }
            });
            h.frame();

            var off = markOf(h, tree, "docs");
            assertFalse(off.visible(), "nothing is drawn inside an unticked box");

            ticks.on.add("docs");
            tree.recheck();
            h.frame();
            var on = markOf(h, tree, "docs");
            assertTrue(on.visible());
            assertEquals(on.w, on.h, 0.5f, "ticked is a square");

            mixed.set(true);
            tree.recheck();
            h.frame();
            var bar = markOf(h, tree, "docs");
            assertTrue(bar.visible());
            assertTrue(bar.w > bar.h * 2f, "mixed is a bar, told apart by shape rather than by size");
            assertEquals(on.w, bar.w, 0.5f, "and it is the same width, so only the shape differs");
            tree.close();
        }
    }

    /** What is drawn inside {@code item}'s box: the box's only child. */
    private static dev.vexelray.gui.core.model.RetainedNode markOf(
            HeadlessGui h, TreeView<String> tree, String item) {
        var box = h.retained(tree.checkBoxNode(item));
        assertTrue(box != null && !box.children.isEmpty(), "the box draws its mark as its child");
        return box.children.get(0);
    }

    /** Rows built after the tree became checkable get boxes too, without being asked for separately. */
    @Test
    @DisplayName("a row materialised later still has a box")
    void rowsBuiltLaterAreCheckableToo() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable(new Ticks());
            h.frame();

            tree.expand("src");            // main and test are built now, long after checkable()
            h.frame();

            Rect box = tree.checkBoxNode("main").layout().rect();
            assertTrue(box != null && box.w() > 0f, "a row built later is a row like any other");
            tree.close();
        }
    }
}
