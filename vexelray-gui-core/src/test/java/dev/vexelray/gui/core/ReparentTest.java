package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Moving a node: {@link Node#append} and {@link Node#insert} put a node where it now is, taking it out of
 * wherever it was.
 *
 * <p>Before this, an insert set the child's {@code parent} but left it in its previous parent's child list, so a
 * reparented node was in the tree <b>twice</b> — laid out twice, drawn twice, and hit-tested at whichever of the
 * two boxes the walk reached first. Nothing could want that, so it was a defect rather than a behaviour; but
 * because the only structural operations were "append" and "remove", nobody had ever asked a node to move, and
 * the defect had no way to be observed.
 *
 * <p>What made it worth finding is what depended on it. {@code Remove} releases the whole subtree — the handle,
 * every registration on it, and any widget state hanging off it — so remove-then-append is not a move either: it
 * destroys the row and builds a stranger that looks like it. A reorderable list, a reorderable tab bar and a
 * tree that can be dragged into all need exactly this, and none of them could be written without it.
 */
class ReparentTest {

    private static float noText(RetainedNode n, dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    private static Node cell(Gui gui) {
        return gui.box().width(Length.rem(1)).height(Length.rem(1));
    }

    /** The ids of {@code parent}'s children, in order — the tree as the renderer will walk it. */
    private static List<Long> childIds(Gui gui, Node parent) {
        RetainedNode r = find(gui.frame(400f, 200f, ReparentTest::noText), parent.id());
        List<Long> ids = new ArrayList<>();
        for (RetainedNode c : r.children) {
            ids.add(c.id);
        }
        return ids;
    }

    private static RetainedNode find(RetainedNode from, long id) {
        if (from.id == id) {
            return from;
        }
        for (RetainedNode c : from.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Test
    void appendingAnAdoptedChildTakesItOutOfItsOldParent() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node left = gui.column();
            Node right = gui.column();
            Node moved = cell(gui);
            gui.root().direction(Direction.ROW).children(left, right);
            left.append(moved);
            assertEquals(List.of(moved.id()), childIds(gui, left));

            right.append(moved);

            assertEquals(List.of(), childIds(gui, left), "it is not in two places");
            assertEquals(List.of(moved.id()), childIds(gui, right));
        }
    }

    @Test
    void aMovedNodeIsTheSameNodeAndKeepsWhatItWasGiven() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node left = gui.column();
            Node right = gui.column();
            Node moved = gui.text("row").width(Length.rem(4)).height(Length.rem(2));
            gui.root().direction(Direction.ROW).children(left, right);
            left.append(moved);
            RetainedNode before = find(gui.frame(400f, 200f, ReparentTest::noText), moved.id());

            right.append(moved);
            RetainedNode after = find(gui.frame(400f, 200f, ReparentTest::noText), moved.id());

            assertSame(before, after, "the node survives the move rather than being rebuilt as a copy");
            assertEquals("row", after.textString(), "and so does everything written on it");
        }
    }

    @Test
    void insertPutsAChildAtAGivenIndex() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node list = gui.column();
            Node a = cell(gui);
            Node b = cell(gui);
            Node c = cell(gui);
            gui.root().children(list);
            list.children(a, b, c);

            list.insert(c, 0);

            assertEquals(List.of(c.id(), a.id(), b.id()), childIds(gui, list));
        }
    }

    /**
     * The index is counted <em>after</em> the detach, which is the only reading that makes a within-parent move
     * mean anything: the alternative counts positions in a list the node is still in, so moving it one place
     * right would leave it where it was.
     */
    @Test
    void aWithinParentMoveCountsFromTheListWithoutIt() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node list = gui.column();
            Node a = cell(gui);
            Node b = cell(gui);
            Node c = cell(gui);
            gui.root().children(list);
            list.children(a, b, c);

            list.insert(a, 2);   // out of a list of three, back into a list of two, at the end

            assertEquals(List.of(b.id(), c.id(), a.id()), childIds(gui, list));
        }
    }

    /** Past the end appends, so a walk placing a list into order never special-cases its last element. */
    @Test
    void anIndexPastTheEndAppends() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node list = gui.column();
            Node a = cell(gui);
            Node b = cell(gui);
            gui.root().children(list);
            list.children(a, b);

            list.insert(a, 99);

            assertEquals(List.of(b.id(), a.id()), childIds(gui, list));
        }
    }
}
