package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reordering a tree by dragging: the seam partition, and the guarantees the requirement asked for by name —
 * nothing commits that the user did not see, everything that commits is undoable, and Escape leaves no trace.
 */
class TreeReorderTest {

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
            return KIDS.containsKey(item);
        }

        @Override
        public List<String> children(String item) {
            return KIDS.getOrDefault(item, List.of());
        }
    }

    private final List<String> moves = new ArrayList<>();

    /** A reorder that records what it was asked to do, and refuses to drop an item onto itself. */
    private Reorder<String> recording() {
        return (moved, where, effect) -> {
            if (moved.equals(where.reference())) {
                return null;
            }
            String what = moved + " " + where.relation() + " "
                    + (where.isRoot() ? "<root>" : where.reference())
                    + (effect == dev.vexelray.gui.core.drop.DropEffect.MOVE ? "" : " (" + effect + ")");
            return change(what);
        };
    }

    private Change change(String what) {
        return new Change() {
            @Override
            public Change apply() {
                moves.add(what);
                return change("undo(" + what + ")");
            }
        };
    }

    /** The vertical middle of the row for {@code item}, in absolute px. */
    private static float midOf(TreeView<String> tree, String item) {
        Rect r = tree.rowNode(item).layout().rect();
        assertNotNull(r, "row must be laid out");
        return r.y() + r.h() / 2f;
    }

    private static Rect rowRect(TreeView<String> tree, String item) {
        return tree.rowNode(item).layout().rect();
    }

    private static void press(HeadlessGui h, float x, float y) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    private static void move(HeadlessGui h, float x, float y) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved((int) x, (int) y, 0, 40, 0));
    }

    private static void release(HeadlessGui h, float x, float y) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    /** A drag from one row to a point, over enough frames that the user would have seen it. */
    private static void drag(HeadlessGui h, float fromY, float toY) {
        press(h, 40f, fromY);
        move(h, 40f, toY);
        h.frame();          // the drag becomes visible here
        release(h, 40f, toY);
        h.frame();
    }

    @Test
    @DisplayName("a row dropped on the lower edge of another lands after it, and is undoable")
    void aDropOnASeamReordersAndUndoes() {
        try (HeadlessGui h = new HeadlessGui()) {
            History history = new History();
            h.gui.dropHistory(history);
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();

            Rect docs = rowRect(tree, "docs");
            drag(h, midOf(tree, "notes.txt"), docs.y() + docs.h() - 1f);   // the bottom seam of "docs"

            assertEquals(List.of("notes.txt AFTER docs"), moves);
            assertTrue(history.canUndo(), "a reorder is an edit like any other");

            history.undo();
            assertEquals(List.of("notes.txt AFTER docs", "undo(notes.txt AFTER docs)"), moves);
            tree.close();
        }
    }

    @Test
    @DisplayName("the middle of a row that can hold children means into it")
    void theMiddleOfABranchMeansInto() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();

            drag(h, midOf(tree, "notes.txt"), midOf(tree, "src"));

            assertEquals(List.of("notes.txt INTO src"), moves);
            tree.close();
        }
    }

    @Test
    @DisplayName("a leaf has no inside, so its middle is still a seam")
    void aLeafDividesInTwo() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();

            Rect notes = rowRect(tree, "notes.txt");
            // Just above the middle of the leaf: BEFORE, not a refused "into" band.
            drag(h, midOf(tree, "src"), notes.y() + notes.h() * 0.4f);

            assertEquals(List.of("src BEFORE notes.txt"), moves);
            tree.close();
        }
    }

    /**
     * Totality. Every pixel from the top of the first row to the bottom of the last must resolve to some
     * placement — no cracks between rows, and no band that looks live and is not.
     */
    @Test
    @DisplayName("every point over the rows resolves to a placement")
    void thereAreNoDeadPixels() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();
            tree.expand("src");
            h.frame();

            Rect first = rowRect(tree, "src");
            Rect last = rowRect(tree, "notes.txt");
            // Whole pixels, and the same ones the assertion reasons about: an event carries integer coordinates,
            // so sweeping in fractional steps would test points the pointer can never actually be at.
            int top = (int) Math.ceil(first.y());
            int bottom = (int) Math.floor(last.y() + last.h());

            // Drag "docs", which every placement below accepts except the three bands of its own row.
            press(h, 40f, midOf(tree, "docs"));
            move(h, 40f, top);
            h.frame();
            assertNotNull(h.gui.dragSession(), "the drag is in flight");

            List<Integer> dead = new ArrayList<>();
            for (int y = top; y < bottom; y++) {
                h.bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(40, y, 0, 1, 0));
                h.frame();
                Drop drop = h.gui.dragSession().drop();
                if (!drop.accepts() && !overOwnRow(tree, y)) {
                    dead.add(y);
                }
            }
            assertEquals(List.of(), dead, "a point that resolves to nothing is a place drops silently fail");

            release(h, 40f, bottom - 1);
            h.frame();
            tree.close();
        }
    }

    /** The rows of "docs" itself, where the recording reorder legitimately refuses (dropping onto itself). */
    private static boolean overOwnRow(TreeView<String> tree, int y) {
        Rect own = rowRect(tree, "docs");
        return own != null && y >= own.y() && y < own.y() + own.h();
    }

    @Test
    @DisplayName("below the last row is the top level, which is how an item leaves a branch")
    void belowTheLastRowMeansTheRoot() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();
            tree.expand("src");
            h.frame();

            Rect last = rowRect(tree, "notes.txt");
            drag(h, midOf(tree, "main"), last.y() + last.h() + 40f);

            assertEquals(List.of("main INTO <root>"), moves);
            tree.close();
        }
    }

    @Test
    @DisplayName("a refused placement shows as accepting nothing, before the user releases")
    void aRefusalIsVisibleBeforeTheDrop() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();

            press(h, 40f, midOf(tree, "src"));
            move(h, 40f, midOf(tree, "src") + 2f);
            h.frame();
            // Dragging "src" onto its own row: the reorder refuses, and it says so now.
            assertFalse(h.gui.dragSession().drop().accepts());

            release(h, 40f, midOf(tree, "src"));
            h.frame();
            assertEquals(List.of(), moves);
            tree.close();
        }
    }

    /** The requirement, stated as a test: a click too quick to see must not reorganise the tree. */
    @Test
    @DisplayName("a click that twitched is a click, not a reorder")
    void aFlickDoesNotReorganise() {
        try (HeadlessGui h = new HeadlessGui()) {
            History history = new History();
            h.gui.dropHistory(history);
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();

            float from = midOf(tree, "notes.txt");
            float to = midOf(tree, "src");
            press(h, 40f, from);
            move(h, 40f, to);
            release(h, 40f, to);
            h.frame();          // the whole gesture inside one drain: never drawn

            assertEquals(List.of(), moves, "the user saw no drag, so there was no drop");
            assertFalse(history.canUndo());
            tree.close();
        }
    }

    @Test
    @DisplayName("Escape abandons the drag and changes nothing")
    void escapeAbandons() {
        try (HeadlessGui h = new HeadlessGui()) {
            History history = new History();
            h.gui.dropHistory(history);
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();

            press(h, 40f, midOf(tree, "notes.txt"));
            move(h, 40f, midOf(tree, "src"));
            h.frame();
            assertNotNull(h.gui.dragSession());

            h.bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.ESCAPE, 0));
            h.frame();
            release(h, 40f, midOf(tree, "src"));
            h.frame();

            assertNull(h.gui.dragSession());
            assertEquals(List.of(), moves);
            assertFalse(history.canUndo());
            tree.close();
        }
    }

    @Test
    @DisplayName("a tree with no reorder registered drags nothing")
    void withoutReorderNothingDrags() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            h.gui.root().children(tree.node());
            h.frame();

            press(h, 40f, midOf(tree, "notes.txt"));
            move(h, 40f, midOf(tree, "src"));
            h.frame();

            assertNull(h.gui.dragSession(), "no drag source was registered");
            tree.close();
        }
    }

    @Test
    @DisplayName("starting a drag selects the row it started on")
    void draggingSelects() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new MapSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            h.frame();

            press(h, 40f, midOf(tree, "notes.txt"));
            move(h, 40f, midOf(tree, "src"));
            h.frame();

            assertEquals("notes.txt", tree.selected(), "the row the pointer grabbed is the row being acted on");
            tree.close();
        }
    }

    // --- an empty branch is still a branch ------------------------------------------------------------------

    /**
     * A board: its columns are branches because of what they <em>are</em>, not because of what happens to be in
     * them, and its cards are leaves for the same reason. {@code hasChildren} answers about content — which is
     * what decides whether a row can be opened — and {@code acceptsChildren} about kind.
     */
    private static final class BoardSource implements TreeView.Source<String> {

        final Map<String, List<String>> columns = new LinkedHashMap<>();

        BoardSource() {
            columns.put("Todo", new ArrayList<>(List.of("write it", "read it")));
            columns.put("Done", new ArrayList<>());
        }

        @Override
        public List<String> roots() {
            return List.copyOf(columns.keySet());
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return !columns.getOrDefault(item, List.of()).isEmpty();
        }

        @Override
        public List<String> children(String item) {
            return List.copyOf(columns.getOrDefault(item, List.of()));
        }

        @Override
        public boolean acceptsChildren(String item) {
            return columns.containsKey(item);
        }
    }

    /**
     * The bug this exists for: "can hold children" was answered with "has children", so taking the last card out
     * of a column turned it into a leaf — two bands, no inside, nowhere to drop into. Emptying a column is done
     * by dragging things out of it, so the tree let the user reach a state it then gave them no way to leave.
     */
    @Test
    @DisplayName("an empty branch still offers somewhere to drop into")
    void anEmptyBranchIsStillABranch() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new BoardSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            tree.expand("Todo");
            h.frame();

            drag(h, midOf(tree, "write it"), midOf(tree, "Done"));

            assertEquals(List.of("write it INTO Done"), moves,
                    "the middle of an empty column is its inside, not a seam beside it");
            tree.close();
        }
    }

    /** And a row that genuinely cannot hold anything still divides in two, so no band of it is a lie. */
    @Test
    @DisplayName("a leaf that cannot hold children has no into band")
    void aRealLeafStillDividesInTwo() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.dropHistory(new History());
            TreeView<String> tree = new TreeView<>(h.gui, new BoardSource());
            tree.reorderable(recording());
            h.gui.root().children(tree.node());
            tree.expand("Todo");
            h.frame();

            drag(h, midOf(tree, "Done"), midOf(tree, "read it") - 2f);

            assertEquals(List.of("Done BEFORE read it"), moves,
                    "the middle of a card is the boundary between before and after it");
            tree.close();
        }
    }
}
