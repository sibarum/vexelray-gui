package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkboxes on rows: the tree draws them and says when one is clicked ({@code TOGGLED}), and the application owns
 * what is ticked and says so ({@code MARKS}).
 *
 * <p>The same split as the hierarchy, for the same reason — a tick is a fact about the application's model, so
 * the tree can no more own it than it owns the tree. What that buys is the thing these tests are mostly about:
 * <b>cascading is the application's</b>, and every one of the incompatible policies real applications want is one
 * message naming what changed. A staging area cascades down; a filter does not; a permission tree propagates up.
 * A widget that picked one would be wrong for the other two.
 */
class TreeCheckTest {

    private static final Map<String, List<String>> KIDS = new LinkedHashMap<>(Map.of(
            "src", List.of("main", "test"),
            "docs", List.of()));

    private static final class MapSource implements Listing<String> {

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

    /**
     * The application's side, answering inline as the test {@link Listing} does: a plain set of ticked items, which
     * is a filter's policy, unless a subclass cascades.
     */
    private static class Ticks {

        final Set<String> on = new LinkedHashSet<>();
        final List<String> clicks = new ArrayList<>();

        TreeView.Check state(String item) {
            return on.contains(item) ? TreeView.Check.ON : TreeView.Check.OFF;
        }

        /** Flip {@code item}, returning every item whose box that changed. */
        List<String> toggled(String item) {
            if (!on.remove(item)) {
                on.add(item);
            }
            return List.of(item);
        }

        /** Answer {@code tree}'s clicks from here on. */
        Ticks answer(HeadlessGui h, TreeView<String> tree) {
            h.bus.subscribe(TreeView.TOGGLED, t -> {
                if (t.tree().equals(tree.address())) {
                    clicks.add((String) t.item());
                    tell(h, tree, toggled((String) t.item()));
                }
            });
            return this;
        }

        /** Tell {@code tree} what is ticked for {@code items}: what a change made anywhere else does. */
        void tell(HeadlessGui h, TreeView<String> tree, List<String> items) {
            Map<Object, TreeView.Check> marks = new HashMap<>();
            for (String item : items) {
                marks.put(item, state(item));
            }
            h.bus.publish(TreeView.MARKS, new TreeView.Marks(tree.address(), marks));
        }
    }

    private static void click(HeadlessGui h, float x, float y) {
        h.bus.publish(h.gui.input(), new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
        h.bus.publish(h.gui.input(), new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    /** The frame the input lands in, and the one the application's answer lands in. */
    private static void settle(HeadlessGui h) {
        h.frame();
        h.frame();
    }

    /** The middle of {@code item}'s checkbox — the target a user aims at. */
    private static float[] boxOf(TreeView<String> tree, String item) {
        Rect box = tree.checkBoxNode(item).layout().rect();
        assertTrue(box != null && box.w() > 0f, "the box must be laid out to be clicked");
        return new float[]{box.x() + box.w() / 2f, box.y() + box.h() / 2f};
    }

    @Test
    @DisplayName("a tree that is not checkable shows no boxes and reserves no width for them")
    void boxesAreAbsentUntilAskedFor() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            h.frame();

            Rect box = tree.checkBoxNode("docs").layout().rect();
            assertTrue(box == null || box.w() == 0f,
                    "a hidden node is out of the layout, so an unchecked tree pays no width");
            tree.close();
        }
    }

    @Test
    @DisplayName("clicking a box says so, and the box shows what the application answers")
    void clickingABoxTogglesThroughTheApplication() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
            Ticks ticks = new Ticks().answer(h, tree);
            h.frame();

            float[] at = boxOf(tree, "notes.txt");
            click(h, at[0], at[1]);
            settle(h);

            assertEquals(List.of("notes.txt"), ticks.clicks);
            assertNotNull(markOf(h, tree, "notes.txt").field(), "the answer ticked it");

            click(h, at[0], at[1]);
            settle(h);
            assertNull(markOf(h, tree, "notes.txt").field(), "and again unticked it");
            tree.close();
        }
    }

    /** What is ticked is the application's: a click nobody answers is a click, and the box keeps what it was told. */
    @Test
    @DisplayName("a click changes no box until the application answers")
    void aClickAloneTicksNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
            List<Object> heard = new ArrayList<>();
            h.bus.subscribe(TreeView.TOGGLED, t -> heard.add(t.item()));
            h.frame();

            float[] at = boxOf(tree, "docs");
            click(h, at[0], at[1]);
            settle(h);

            assertEquals(List.of("docs"), heard);
            assertNull(markOf(h, tree, "docs").field(), "nothing answered, so nothing is ticked");
            tree.close();
        }
    }

    @Test
    @DisplayName("clicking a box selects its row, so the keyboard carries on from there")
    void tickingSelects() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
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
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
            Ticks ticks = new Ticks().answer(h, tree);
            tree.select("docs");
            tree.focus();
            h.frame();

            h.bus.publish(h.gui.input(), new InputEvent.KeyPressed(Key.SPACE, 0));
            settle(h);

            assertEquals(List.of("docs"), ticks.clicks, "the chord acts on the selection, which is the tree's");
            assertNotNull(markOf(h, tree, "docs").field());
            tree.close();
        }
    }

    /**
     * The point of leaving cascading out. This model ticks a folder by ticking everything under it and reports
     * a partly-ticked folder as MIXED — and the widget needed no notion of any of that.
     */
    @Test
    @DisplayName("a cascading model is one message naming what changed, with no help from the widget")
    void cascadingIsTheApplicationsToDefine() {
        Ticks staged = new Ticks() {

            @Override
            TreeView.Check state(String item) {
                List<String> kids = KIDS.getOrDefault(item, List.of());
                if (kids.isEmpty()) {
                    return super.state(item);
                }
                long ticked = kids.stream().filter(on::contains).count();
                if (ticked == 0) {
                    return TreeView.Check.OFF;
                }
                return ticked == kids.size() ? TreeView.Check.ON : TreeView.Check.MIXED;
            }

            @Override
            List<String> toggled(String item) {
                boolean turningOn = state(item) != TreeView.Check.ON;
                List<String> kids = KIDS.getOrDefault(item, List.of());
                List<String> changed = new ArrayList<>(kids.isEmpty() ? List.of(item) : kids);
                for (String leaf : List.copyOf(changed)) {
                    if (turningOn) {
                        on.add(leaf);
                    } else {
                        on.remove(leaf);
                    }
                }
                changed.add("src");   // the one folder here with anything in it, which a change under it may turn
                changed.add(item);
                return changed;
            }
        };

        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
            staged.answer(h, tree);
            tree.expand("src");
            h.frame();
            h.frame();

            float[] child = boxOf(tree, "main");
            click(h, child[0], child[1]);
            settle(h);
            var tick = markOf(h, tree, "main").field().region();
            var mixed = markOf(h, tree, "src").field();
            assertNotNull(mixed, "one of two children ticked, and the folder says so");
            assertNotEquals(tick, mixed.region(), "as a bar, not a tick");

            float[] folder = boxOf(tree, "src");
            click(h, folder[0], folder[1]);
            settle(h);

            assertEquals(tick, markOf(h, tree, "src").field().region(), "ticking a mixed folder fills it");
            assertEquals(tick, markOf(h, tree, "test").field().region(), "and everything under it");
            tree.close();
        }
    }

    /** A tick changed from somewhere else shows when the application says so — as a change to the hierarchy does. */
    @Test
    @DisplayName("marks the tree was never asked about are drawn when they arrive")
    void marksFromElsewhereAreDrawn() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
            Ticks ticks = new Ticks().answer(h, tree);
            h.frame();

            ticks.on.add("docs");          // a "select all" button, a background job, another view
            ticks.tell(h, tree, List.of("docs"));
            settle(h);

            assertNotNull(markOf(h, tree, "docs").field());
            assertTrue(ticks.clicks.isEmpty(), "nothing was clicked; the tree was only told");
            tree.close();
        }
    }

    /** A level and its marks may arrive in either order; a row is never drawn empty that the application ticked. */
    @Test
    @DisplayName("marks for an item with no row yet are kept for when its row is built")
    void marksAheadOfTheirRowsAreKept() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
            Ticks ticks = new Ticks();
            ticks.on.add("test");
            ticks.tell(h, tree, List.of("test"));
            settle(h);
            assertNull(tree.checkBoxNode("test"), "src has not been opened, so test has no row");

            tree.expand("src");
            settle(h);
            assertNotNull(markOf(h, tree, "test").field(), "its row is built ticked");
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
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
            h.frame();

            var off = markOf(h, tree, "docs");
            assertNull(off.field(), "nothing is drawn inside an unticked box");

            mark(h, tree, "docs", TreeView.Check.ON);
            var on = markOf(h, tree, "docs");
            assertNotNull(on.field(), "ticked draws the tick");
            float onW = on.w;
            var tick = on.field().region();

            mark(h, tree, "docs", TreeView.Check.MIXED);
            var bar = markOf(h, tree, "docs");
            assertNotNull(bar.field(), "mixed draws a mark too");
            assertNotEquals(tick, bar.field().region(),
                    "mixed is a bar, told apart by shape rather than by size");
            assertEquals(onW, bar.w, 0.5f, "in the same box, so only the shape differs");
            tree.close();
        }
    }

    private static void mark(HeadlessGui h, TreeView<String> tree, String item, TreeView.Check check) {
        h.bus.publish(TreeView.MARKS, new TreeView.Marks(tree.address(), Map.of(item, check)));
        settle(h);
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
            TreeView<String> tree = Listing.tree(h, new MapSource());
            h.gui.root().children(tree.node());
            tree.checkable();
            h.frame();

            tree.expand("src");            // main and test are built now, long after checkable()
            settle(h);

            Rect box = tree.checkBoxNode("main").layout().rect();
            assertTrue(box != null && box.w() > 0f, "a row built later is a row like any other");
            tree.close();
        }
    }
}
