package dev.vexelray.gui.core.nav;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.atchung.Atchung;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Landmarks and walks, against a hand-built tree and a stub {@link Navigator.Tree} — no {@code Gui}, no
 * reconciler, no mutation sink (docs/plans/gui-decomposition.md §4).
 *
 * <p>The walk was the part that made this worth extracting: it was an inner class of {@code Gui}, so it reached
 * the reconciler, the sink and {@code focus} through the enclosing instance, and nothing could step one without
 * a whole composition root. Here a walk is stepped by hand, one frame at a time, which is the only way to see
 * what each frame actually does.
 */
class NavigatorTest {

    private static long ids = 1L;

    /** A tree that answers what a walk asks, and records the commands it was given. */
    private static final class Fake implements Navigator.Tree {
        private final Map<Long, RetainedNode> nodes = new HashMap<>();
        private final List<Long> focused = new ArrayList<>();
        private final List<Long> revealed = new ArrayList<>();

        RetainedNode add(RetainedNode parent, RetainedNode n) {
            if (parent != null) {
                n.parent = parent;
                parent.children.add(n);
            }
            nodes.put(n.id, n);
            return n;
        }

        @Override
        public RetainedNode node(long id) {
            return nodes.get(id);
        }

        @Override
        public void scrollIntoView(long id) {
            revealed.add(id);
        }

        @Override
        public void focus(long id) {
            focused.add(id);
        }

        @Override
        public Node arrived(long id) {
            return null;   // nothing here asserts on the handle, only on what the walk did to get there
        }
    }

    private static RetainedNode box() {
        return new RetainedNode(ids++);
    }

    private static Navigator navigator(Navigator.Tree tree) {
        return new Navigator(tree, Atchung.create(), why -> { });
    }

    // --- naming -------------------------------------------------------------------------------------------

    @Test
    void aLandmarkIsANameForANodeAndRebindsRatherThanCollides() {
        Fake tree = new Fake();
        RetainedNode first = tree.add(null, box());
        RetainedNode second = tree.add(null, box());
        Navigator nav = navigator(tree);

        nav.landmark("prefs.accent", first.id);
        assertEquals("prefs.accent", nav.landmarkName(first.id));

        nav.landmark("prefs.accent", second.id);
        assertEquals("prefs.accent", nav.landmarkName(second.id), "re-using a name rebinds it");
        assertEquals("", nav.landmarkName(first.id), "and the node it left does not keep it");
    }

    /** {@code '/'} separates the window from the landmark in an address, so a name may not contain one. */
    @Test
    void aNameMayNotBeBlankOrContainTheSeparator() {
        Navigator nav = navigator(new Fake());

        assertThrows(IllegalArgumentException.class, () -> nav.landmark("", 1L));
        assertThrows(IllegalArgumentException.class, () -> nav.landmark("  ", 1L));
        assertThrows(IllegalArgumentException.class, () -> nav.landmark("window/thing", 1L));
    }

    /** Every registration keyed by a node id goes when the node leaves the tree, in both directions. */
    @Test
    void aNodeLeavingTakesItsNameWithIt() {
        Fake tree = new Fake();
        RetainedNode n = tree.add(null, box());
        Navigator nav = navigator(tree);
        nav.landmark("gone", n.id);

        nav.forget(n.id);

        assertEquals("", nav.landmarkName(n.id));
        assertTrue(nav.landmarkNode("gone").isEmpty(), "and the forward map went too, not just the reverse");
    }

    // --- walking ------------------------------------------------------------------------------------------

    /**
     * A walk asks each concealing ancestor to reveal the target, outermost first and <b>one per frame</b> —
     * each reveal changes what the next one is looking at, so they cannot be run in a batch.
     */
    @Test
    void revealsRunOutermostFirstAndOnePerFrame() {
        Fake tree = new Fake();
        RetainedNode outer = tree.add(null, box());
        RetainedNode inner = tree.add(outer, box());
        RetainedNode target = tree.add(inner, box());
        Navigator nav = navigator(tree);
        nav.landmark("deep", target.id);

        List<String> asked = new ArrayList<>();
        nav.reveals(outer.id, id -> {
            asked.add("outer");
            return true;             // it acted: the frame should end here
        });
        nav.reveals(inner.id, id -> {
            asked.add("inner");
            return true;
        });

        nav.navigate(Address.of("deep"));

        nav.step();
        assertEquals(List.of("outer"), asked, "the outermost acted, and the frame stopped so its work can land");

        nav.step();
        assertEquals(List.of("outer", "inner"), asked, "the next frame carries on from where it had got to");
    }

    /** A container that declined is not asked again every frame — it said no once. */
    @Test
    void aContainerThatDeclinesIsNotAskedAgain() {
        Fake tree = new Fake();
        RetainedNode outer = tree.add(null, box());
        RetainedNode target = tree.add(outer, box());
        Navigator nav = navigator(tree);
        nav.landmark("t", target.id);

        int[] asks = {0};
        nav.reveals(outer.id, id -> {
            asks[0]++;
            return false;            // nothing to do
        });

        nav.navigate(Address.of("t"));
        nav.step();
        nav.step();

        assertEquals(1, asks[0]);
    }

    /**
     * Still hidden after everything that could reveal it was asked is a failure with a reason, not a walk that
     * quietly runs out its frame budget. The container concealing it declares no {@link Reveal}.
     */
    @Test
    void aTargetNothingCanRevealFailsWithWhy() {
        Fake tree = new Fake();
        RetainedNode drawer = tree.add(null, box());
        drawer.set(PropKey.VISIBLE, false);
        RetainedNode target = tree.add(drawer, box());
        Navigator nav = navigator(tree);
        nav.landmark("hidden", target.id);

        Navigation walk = nav.navigate(Address.of("hidden"));
        nav.step();

        assertTrue(walk.arrival().isCompletedExceptionally());
        assertFalse(nav.walking(), "and the walk is dropped rather than retried for 240 frames");
    }

    /** A name the tree does not have yet is not an error: the tree may not have been built. */
    @Test
    void aWalkWaitsForATreeThatDoesNotHaveTheLandmarkYet() {
        Fake tree = new Fake();
        Navigator nav = navigator(tree);

        Navigation walk = nav.navigate(Address.of("later"));
        nav.step();

        assertFalse(walk.done(), "still waiting");
        assertTrue(nav.walking());
    }

    // --- addresses for other windows ----------------------------------------------------------------------

    /**
     * An address naming another window is published rather than walked, and the caller is told so rather than
     * handed a future that never completes — this tree cannot say when somebody else's window arrives.
     */
    @Test
    void anAddressForAnotherWindowIsNotWalkedHere() {
        Navigator nav = navigator(new Fake());
        nav.windowKey("main");

        Navigation forwarded = nav.navigate(new Address("other", "thing"));

        assertTrue(forwarded.arrival().isCompletedExceptionally());
        assertFalse(nav.walking(), "nothing to step: it is not this window's to walk");
    }

    /** And one off the bus for another window, or for a name this tree does not own, is simply not ours. */
    @Test
    void anAcceptedAddressIsCheckedAgainstThisWindowAndThisTree() {
        Fake tree = new Fake();
        RetainedNode n = tree.add(null, box());
        Navigator nav = navigator(tree);
        nav.windowKey("main");
        nav.landmark("mine", n.id);

        nav.accept(new Address("other", "mine"));
        assertFalse(nav.walking(), "another window on this bus owns that");

        nav.accept(new Address("main", "not-a-name-here"));
        assertFalse(nav.walking(), "and nobody owns that");

        nav.accept(new Address("main", "mine"));
        assertTrue(nav.walking(), "but this one is ours");
    }
}
