package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Showing where a drop would land. The indicator is told <em>where</em> by the same resolution that produces the
 * change, and decides only <em>how</em> — so these pin the geometry it is handed, and that it is handed nothing
 * when there is nothing to show.
 */
class TreeDropIndicatorTest {

    private static final Map<String, List<String>> KIDS = Map.of("src", List.of("main"));

    private static final class MapSource implements TreeView.Source<String> {
        @Override
        public List<String> roots() {
            return List.of("src", "notes.txt");
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

    /** Records every rect the indicator is asked to paint, in the node frame it was given. */
    private final List<Rect> painted = new ArrayList<>();

    private TreeView<String> tree(HeadlessGui h) {
        TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
        tree.reorderable((moved, where, effect) -> moved.equals(where.reference()) ? null : () -> null);
        tree.dropIndicator((where, effect) -> {
            painted.add(where);
            return Picture.of(new Picture.Fill(where.x(), where.y(), where.w(), where.h(), 0, 0,
                    h.gui.theme().color(dev.vexelray.gui.core.style.Role.ACCENT)));
        });
        h.gui.dropHistory(new History());
        h.gui.root().children(tree.node());
        h.frame();
        return tree;
    }

    private static float midOf(TreeView<String> tree, String item) {
        Rect r = tree.rowNode(item).layout().rect();
        return r.y() + r.h() / 2f;
    }

    private static void press(HeadlessGui h, float y) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, 40, (int) y, 0));
    }

    private static void move(HeadlessGui h, float y) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(40, (int) y, 0, 40, 0));
    }

    @Test
    @DisplayName("nothing is painted until a drag is in flight")
    void noDragNoIndicator() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            h.frame();

            assertTrue(painted.isEmpty(), "a settled tree draws no drop indicator");
            tree.close();
        }
    }

    @Test
    @DisplayName("the indicator is placed in the scroller's own frame, not in screen coordinates")
    void theIndicatorIsNodeLocal() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            Rect box = tree.scroller().layout().rect();
            Rect target = tree.rowNode("src").layout().rect();

            press(h, midOf(tree, "notes.txt"));
            move(h, midOf(tree, "src"));
            h.frame();

            assertFalse(painted.isEmpty(), "the drop resolved, so it is shown");
            Rect where = painted.get(painted.size() - 1);
            assertEquals(target.y() - box.y(), where.y(), 0.01f,
                    "a Picture is drawn in the box it is attached to, so it must not carry the absolute position");
            assertEquals(target.h(), where.h(), 0.01f, "the middle of a branch means into it: the whole row");
            tree.close();
        }
    }

    @Test
    @DisplayName("a seam is thin and a target is the whole row, which is how they are told apart")
    void seamsAndTargetsDifferInShape() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            Rect src = tree.rowNode("src").layout().rect();

            // Drag the leaf, so every placement against "src" is accepted -- dragging "src" onto its own row
            // would be refused, and the refusal would paint nothing rather than a shape to compare.
            press(h, midOf(tree, "notes.txt"));
            move(h, src.y() + 1f);            // the top seam of the branch
            h.frame();
            float seam = painted.get(painted.size() - 1).h();

            move(h, midOf(tree, "src"));      // into the branch
            h.frame();
            float target = painted.get(painted.size() - 1).h();

            assertTrue(seam <= DropIndicator.SEAM_MAX_PX, "a seam reads as between things: " + seam);
            assertTrue(target > DropIndicator.SEAM_MAX_PX, "a target reads as a thing: " + target);
            tree.close();
        }
    }

    @Test
    @DisplayName("a refused placement shows nothing, rather than an indicator that would not fire")
    void refusalPaintsNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);

            press(h, midOf(tree, "src"));
            move(h, midOf(tree, "src") + 2f);   // onto its own row: the reorder refuses
            h.frame();

            assertTrue(painted.isEmpty(), "an indicator over a drop that will not happen is the lie to avoid");
            tree.close();
        }
    }

    @Test
    @DisplayName("Escape clears the indicator")
    void escapeClearsIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);

            press(h, midOf(tree, "notes.txt"));
            move(h, midOf(tree, "src"));
            h.frame();
            assertFalse(painted.isEmpty());

            int before = painted.size();
            h.bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.ESCAPE, 0));
            h.frame();
            h.frame();

            assertEquals(before, painted.size(), "a cancelled drag has nowhere to land, so it shows nothing");
            tree.close();
        }
    }

    @Test
    @DisplayName("the default indicator draws a bar for a seam and a ring for a box")
    void theDefaultDistinguishesTheTwo() {
        Picture seam = DropIndicator.of(dev.vexelray.canvas.Color.WHITE)
                .paint(new Rect(0f, 10f, 100f, 2f), dev.vexelray.gui.core.drop.DropEffect.MOVE);
        Picture box = DropIndicator.of(dev.vexelray.canvas.Color.WHITE)
                .paint(new Rect(0f, 10f, 100f, 28f), dev.vexelray.gui.core.drop.DropEffect.MOVE);

        assertTrue(seam.marks().get(0) instanceof Picture.Fill, "between two rows is a bar on the seam");
        assertTrue(box.marks().get(0) instanceof Picture.Outline,
                "into a row is a ring, so it does not compete with the row's own selected and hovered fills");
    }
}
