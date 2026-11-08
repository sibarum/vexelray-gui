package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.drop.Drop;
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
 * Reordering a tree by dragging: the seam partition, what the tree refuses in the frame, and the guarantees the
 * requirement asked for by name — nothing lands that the user did not see, and Escape leaves no trace. What lands is
 * announced on {@code PLACED}; the application's model, and its undo, are not the tree's.
 */
class TreeReorderTest {

    private static final Map<String, List<String>> KIDS = new LinkedHashMap<>(Map.of(
            "src", List.of("main", "test"),
            "docs", List.of()));

    private static class MapSource implements Listing<String> {
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

    /** Make {@code tree} reorderable by moves, and write down every placement it announces. */
    private void recording(HeadlessGui h, TreeView<String> tree) {
        tree.reorderable(dev.vexelray.gui.core.drop.DropEffect.MOVE);
        h.bus.subscribe(TreeView.PLACED, p -> {
            if (p.at().equals(tree.address())) {
                moves.add(p.item() + " " + p.where().relation() + " "
                        + (p.where().isRoot() ? "<root>" : p.where().reference())
                        + (p.effect() == dev.vexelray.gui.core.drop.DropEffect.MOVE ? "" : " (" + p.effect() + ")"));
            }
        });
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
        h.bus.publish(h.gui.input(), new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    private static void move(HeadlessGui h, float x, float y) {
        h.bus.publish(h.gui.input(), new InputEvent.PointerMoved((int) x, (int) y, 0, 40, 0));
    }

    private static void release(HeadlessGui h, float x, float y) {
        h.bus.publish(h.gui.input(), new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    /** The click that puts a latched drag down: a press and its release. */
    private static void click(HeadlessGui h, float x, float y) {
        press(h, x, y);
        release(h, x, y);
    }

    /**
     * A drag from one row to a point, over enough frames that the user would have seen it: picked up, let go —
     * which keeps it, latched — and put down with a click.
     */
    private static void drag(HeadlessGui h, float fromY, float toY) {
        press(h, 40f, fromY);
        move(h, 40f, toY);
        h.frame();          // the drag becomes visible here
        release(h, 40f, toY);
        h.frame();
        click(h, 40f, toY);
        h.frame();
        h.frame();          // and the announcement is delivered
    }

    @Test
    @DisplayName("a row dropped on the upper edge of another is announced as before it, and moves no row")
    void aDropOnASeamIsAnnounced() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
            h.gui.root().children(tree.node());
            h.frame();

            Rect docs = rowRect(tree, "docs");
            float notesAt = midOf(tree, "notes.txt");
            drag(h, notesAt, docs.y() + 1f);   // the top seam of "docs"

            assertEquals(List.of("notes.txt BEFORE docs"), moves);
            assertEquals(notesAt, midOf(tree, "notes.txt"), 0.5f,
                    "the hierarchy is the application's: the row stays until the tree is told to look again");
            tree.close();
        }
    }

    /** A move that would leave an item exactly where it is changes nothing, and the tree can see that itself. */
    @Test
    @DisplayName("a placement that leaves the item where it is is refused in the frame")
    void aNoOpIsRefusedInTheFrame() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
            h.gui.root().children(tree.node());
            h.frame();

            Rect docs = rowRect(tree, "docs");
            press(h, 40f, midOf(tree, "notes.txt"));
            move(h, 40f, docs.y() + docs.h() - 1f);   // the bottom seam of "docs": where notes.txt already is
            h.frame();
            assertFalse(h.gui.dragSession().drop().accepts(), "notes.txt is already after docs");

            h.bus.publish(h.gui.input(), new InputEvent.KeyPressed(Key.ESCAPE, 0));
            h.frame();
            tree.close();
        }
    }

    @Test
    @DisplayName("an item cannot be put under itself")
    void notUnderItself() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
            h.gui.root().children(tree.node());
            tree.expand("src");
            h.frame();
            h.frame();

            press(h, 40f, midOf(tree, "src"));
            move(h, 40f, midOf(tree, "test"));   // beside test, which is inside src
            h.frame();
            assertFalse(h.gui.dragSession().drop().accepts(), "src cannot hold itself");

            h.bus.publish(h.gui.input(), new InputEvent.KeyPressed(Key.ESCAPE, 0));
            h.frame();
            tree.close();
        }
    }

    @Test
    @DisplayName("refresh, sent as a message, is what moves the rows")
    void refreshMovesTheRows() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> roots = new ArrayList<>(List.of("src", "docs", "notes.txt"));
            TreeView<String> tree = Listing.tree(h, new MapSource() {
                @Override
                public List<String> roots() {
                    return List.copyOf(roots);
                }
            });
            h.gui.root().children(tree.node());
            h.frame();
            float docsAt = midOf(tree, "docs");

            roots.remove("notes.txt");
            roots.add(0, "notes.txt");
            h.bus.publish(TreeView.REFRESH, new TreeView.Refresh(tree.address()));
            h.frame();
            h.frame();
            h.frame();

            assertTrue(midOf(tree, "notes.txt") < midOf(tree, "src"), "notes.txt is first now");
            assertTrue(midOf(tree, "docs") > docsAt, "and everything after it moved down");
            tree.close();
        }
    }

    @Test
    @DisplayName("the middle of a row that can hold children means into it")
    void theMiddleOfABranchMeansInto() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
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
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
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
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
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

            // Drag "notes.txt", which every placement below accepts but its own row and the one it is already at.
            press(h, 40f, midOf(tree, "notes.txt"));
            move(h, 40f, top);
            h.frame();
            assertNotNull(h.gui.dragSession(), "the drag is in flight");

            List<Integer> dead = new ArrayList<>();
            for (int y = top; y < bottom; y++) {
                h.bus.publish(h.gui.input(), new InputEvent.PointerMoved(40, y, 0, 1, 0));
                h.frame();
                Drop drop = h.gui.dragSession().drop();
                if (!drop.accepts() && !whereItIs(tree, y)) {
                    dead.add(y);
                }
            }
            assertEquals(List.of(), dead, "a point that resolves to nothing is a place drops silently fail");

            release(h, 40f, bottom - 1);
            h.frame();
            tree.close();
        }
    }

    /**
     * Where the tree rightly refuses notes.txt: its own row, and the bottom band of "docs", which is after docs, where
     * it already is.
     */
    private static boolean whereItIs(TreeView<String> tree, int y) {
        Rect own = rowRect(tree, "notes.txt");
        Rect docs = rowRect(tree, "docs");
        return y >= own.y() && y < own.y() + own.h()
                || y >= docs.y() + docs.h() * 0.75f && y < docs.y() + docs.h();
    }

    @Test
    @DisplayName("below the last row is the top level, which is how an item leaves a branch")
    void belowTheLastRowMeansTheRoot() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
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
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
            h.gui.root().children(tree.node());
            h.frame();

            press(h, 40f, midOf(tree, "src"));
            move(h, 40f, midOf(tree, "src") + 2f);
            h.frame();
            // Dragging "src" onto its own row: the reorder refuses, and it says so now.
            assertFalse(h.gui.dragSession().drop().accepts());

            release(h, 40f, midOf(tree, "src"));
            click(h, 40f, midOf(tree, "src"));
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
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
            h.gui.root().children(tree.node());
            h.frame();

            float from = midOf(tree, "notes.txt");
            float to = midOf(tree, "src");
            press(h, 40f, from);
            move(h, 40f, to);
            release(h, 40f, to);
            click(h, 40f, to);
            h.frame();          // the whole gesture inside one drain: never drawn

            assertEquals(List.of(), moves, "the user saw no drag, so there was no drop");
            tree.close();
        }
    }

    @Test
    @DisplayName("Escape abandons the drag and changes nothing")
    void escapeAbandons() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
            h.gui.root().children(tree.node());
            h.frame();

            press(h, 40f, midOf(tree, "notes.txt"));
            move(h, 40f, midOf(tree, "src"));
            h.frame();
            assertNotNull(h.gui.dragSession());

            h.bus.publish(h.gui.input(), new InputEvent.KeyPressed(Key.ESCAPE, 0));
            h.frame();
            release(h, 40f, midOf(tree, "src"));
            h.frame();

            assertNull(h.gui.dragSession());
            assertEquals(List.of(), moves);
            tree.close();
        }
    }

    @Test
    @DisplayName("a row carried and held still owes no frames: the label is written only when it changes")
    void aStillCarryParks() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
            h.gui.root().children(tree.node());
            h.frame();

            press(h, 40f, midOf(tree, "notes.txt"));
            move(h, 40f, midOf(tree, "src"));
            h.frame();
            release(h, 40f, midOf(tree, "src"));   // latched: still carried, the pointer now still
            h.frame();
            h.frame();
            h.frame();

            assertNotNull(h.gui.dragSession(), "still mid-drag");
            assertFalse(h.gui.frameOwed(), "nothing changed, so nothing was written, so nothing is owed");
            tree.close();
        }
    }

    @Test
    @DisplayName("a tree with no reorder registered drags nothing")
    void withoutReorderNothingDrags() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = Listing.tree(h, new MapSource());
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
            TreeView<String> tree = Listing.tree(h, new MapSource());
            recording(h, tree);
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
    private static final class BoardSource implements Listing<String> {

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
            TreeView<String> tree = Listing.tree(h, new BoardSource());
            recording(h, tree);
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
            TreeView<String> tree = Listing.tree(h, new BoardSource());
            recording(h, tree);
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
