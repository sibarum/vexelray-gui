package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.EnterMotion;
import dev.vexelray.gui.core.layout.ExitMotion;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutMotion;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyed children: {@link Node#arrange} as the primitive, {@link Keyed} as the diff on top of it. What is asserted is
 * identity — the node a key had is the node it has — because that is the whole of what makes a rebuild from state
 * stop fighting motion, focus and widget state.
 */
class KeyedTest {

    private record Item(int id, String label) {
    }

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static RetainedNode frame(Gui gui) {
        return gui.frame(400f, 300f, KeyedTest::noText);
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

    private static List<Long> childIds(Gui gui, Node parent) {
        List<Long> ids = new ArrayList<>();
        for (RetainedNode c : find(frame(gui), parent.id()).children) {
            ids.add(c.id);
        }
        return ids;
    }

    private static Keyed<Item> rows(Gui gui, Node list, List<Integer> built) {
        return list.keyed(Item::id, item -> {
            built.add(item.id());
            return gui.box().width(Length.dp(40)).height(Length.dp(20));
        });
    }

    @Test
    void aKeyStillPresentKeepsItsNodeAndTheRestFollowTheItemsOrder() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node list = gui.column();
            gui.root().children(list);
            List<Integer> built = new ArrayList<>();
            Keyed<Item> rows = rows(gui, list, built);

            rows.reconcile(List.of(new Item(1, "a"), new Item(2, "b"), new Item(3, "c")));
            Node one = rows.node(1);
            Node three = rows.node(3);
            assertEquals(List.of(one.id(), rows.node(2).id(), three.id()), childIds(gui, list));

            rows.reconcile(List.of(new Item(3, "c"), new Item(4, "d"), new Item(1, "a")));
            assertSame(one, rows.node(1), "kept, not rebuilt");
            assertSame(three, rows.node(3));
            assertEquals(List.of(1, 2, 3, 4), built, "only the new key was built");
            assertEquals(List.of(three.id(), rows.node(4).id(), one.id()), childIds(gui, list),
                    "in the items' order, and the dropped key's node is gone");
            assertEquals(3, rows.size());
        }
    }

    @Test
    void updateChangesKeptNodesInPlace() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node list = gui.column();
            gui.root().children(list);
            Keyed<Item> rows = list.keyed(Item::id, item -> gui.text(""));

            rows.reconcile(List.of(new Item(1, "draft")), (item, row) -> row.text(item.label()));
            long id = rows.node(1).id();
            rows.reconcile(List.of(new Item(1, "final")), (item, row) -> row.text(item.label()));

            RetainedNode r = find(frame(gui), id);
            assertEquals("final", r.textString());
        }
    }

    @Test
    void aReorderedRowIsMovedSoALayoutMotionSeesItTravel() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            List<Long> moved = new ArrayList<>();
            gui.motion(new LayoutMotion() {
                @Override
                public void moved(RetainedNode node, float fromX, float fromY, float toX, float toY) {
                    moved.add(node.id);
                }

                @Override
                public float displacementX(RetainedNode node) {
                    return 0f;
                }

                @Override
                public float displacementY(RetainedNode node) {
                    return 0f;
                }
            });
            Node list = gui.column();
            gui.root().children(list);
            Keyed<Item> rows = rows(gui, list, new ArrayList<>());
            rows.reconcile(List.of(new Item(1, "a"), new Item(2, "b")));
            frame(gui);

            rows.reconcile(List.of(new Item(2, "b"), new Item(1, "a")));
            frame(gui);
            assertTrue(moved.contains(rows.node(1).id()) && moved.contains(rows.node(2).id()),
                    "a rebuild would have reported nothing moving: two rows gone and two strangers arrived");
        }
    }

    @Test
    void arrangeKeepsALeavingChildInItsPlaceAndAKeyShownAgainGetsItsNodeBack() {
        Map<Long, Float> presence = new HashMap<>();
        class Leaving implements EnterMotion, ExitMotion {
            @Override
            public void entered(RetainedNode node) {
                presence.put(node.id, 0.5f);
            }

            @Override
            public float presence(RetainedNode node) {
                return presence.getOrDefault(node.id, 1f);
            }

            @Override
            public float rise(RetainedNode node) {
                return 0f;
            }

            @Override
            public boolean leave(RetainedNode node) {
                presence.put(node.id, 0.5f);
                return true;
            }

            @Override
            public void left(RetainedNode node) {
                presence.remove(node.id);
            }
        }
        Leaving motion = new Leaving();
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.enterMotion(motion).exitMotion(motion);
            Node list = gui.column();
            gui.root().children(list);
            Keyed<Item> rows = rows(gui, list, new ArrayList<>());
            rows.reconcile(List.of(new Item(1, "a"), new Item(2, "b"), new Item(3, "c")));
            frame(gui);
            Node two = rows.node(2);

            rows.reconcile(List.of(new Item(3, "c"), new Item(1, "a")));
            RetainedNode r2 = find(frame(gui), two.id());
            assertTrue(r2.leaving, "removed through the exit motion");
            assertEquals(List.of(rows.node(3).id(), rows.node(1).id(), two.id()), childIds(gui, list),
                    "a leaving row stays right after what preceded it (1), wherever that went");

            rows.reconcile(List.of(new Item(1, "a"), new Item(2, "b"), new Item(3, "c")));
            assertSame(two, rows.node(2), "shown again before it had gone: the same node, turned round");
            RetainedNode back = find(frame(gui), two.id());
            assertSame(r2, back);
            assertFalse(back.leaving);
        }
    }

    @Test
    void twoItemsWithOneKeyAreRefused() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node list = gui.column();
            Keyed<Item> rows = rows(gui, list, new ArrayList<>());
            assertThrows(IllegalArgumentException.class,
                    () -> rows.reconcile(List.of(new Item(1, "a"), new Item(1, "again"))));
        }
    }
}
