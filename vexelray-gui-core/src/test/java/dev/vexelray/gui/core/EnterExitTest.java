package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.EnterMotion;
import dev.vexelray.gui.core.layout.ExitMotion;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the framework does with an {@link EnterMotion} and an {@link ExitMotion}: which edits are arrivals, what a
 * removal taken by the exit motion does to the tree while it leaves, when it is detached and released, and that a
 * node put back before it has gone is the same node. How a presence is timed is the source's, and gui-krono's
 * {@code Presence} is tested there; this one is scripted by hand.
 */
class EnterExitTest {

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** A source whose presences the test writes, recording what it was told. */
    private static final class Scripted implements EnterMotion, ExitMotion {
        final List<Long> entered = new ArrayList<>();
        final List<Long> left = new ArrayList<>();
        final Map<Long, Float> presence = new HashMap<>();
        boolean take = true;

        @Override
        public void entered(RetainedNode node) {
            entered.add(node.id);
            presence.putIfAbsent(node.id, 0f);   // a node coming back keeps the value it was leaving at
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
            return take;
        }

        @Override
        public void left(RetainedNode node) {
            left.add(node.id);
        }
    }

    private static RetainedNode frame(Gui gui) {
        return gui.frame(400f, 300f, EnterExitTest::noText);
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

    private static Node row(Gui gui) {
        return gui.box().width(Length.dp(40)).height(Length.dp(20));
    }

    private static Gui withScripted(Scripted s) {
        Gui gui = new Gui(Atchung.create(), Runnable::run);
        gui.enterMotion(s).exitMotion(s);
        return gui;
    }

    @Test
    void theFirstFramesTreeDoesNotArriveAndALaterAdditionDoes() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column();
            Node first = row(gui);
            gui.root().children(list.children(first));
            frame(gui);
            assertEquals(List.of(), s.entered, "a window drawn for the first time has nothing to arrive into");

            Node added = row(gui);
            list.append(added);
            RetainedNode r = find(frame(gui), added.id());
            assertEquals(List.of(added.id()), s.entered);
            assertEquals(0f, r.presence, "drawn at the presence the source gives, in the frame it appears");

            s.presence.put(added.id(), 1f);
            frame(gui);
            assertEquals(1f, r.presence);
        }
    }

    @Test
    void aSubtreeArrivesAtItsTopAndAMoveIsNotAnArrival() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node left = gui.column();
            Node right = gui.column();
            gui.root().children(left, right);
            frame(gui);

            Node card = gui.column();
            card.children(row(gui), row(gui));
            left.append(card);
            frame(gui);
            assertEquals(List.of(card.id()), s.entered, "built off-screen, it arrives once, as a whole");

            right.append(card);
            frame(gui);
            assertEquals(List.of(card.id()), s.entered, "moved, not arrived: it was on screen all along");
        }
    }

    @Test
    void aLeavingNodeKeepsItsPlaceStopsTakingThePointerAndIsDetachedWhenItReachesNothing() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column();
            Node a = row(gui);
            Node b = row(gui);
            gui.root().children(list.children(a, b));
            RetainedNode ra = find(frame(gui), a.id());
            float bY = find(frame(gui), b.id()).y;

            s.presence.put(a.id(), 0.6f);
            a.remove();
            RetainedNode root = frame(gui);
            assertSame(ra, find(root, a.id()), "still in the tree while it leaves");
            assertTrue(ra.leaving);
            assertTrue(ra.hitInert(), "a node on its way out is not the thing a click acts on");
            assertEquals(0.6f, ra.presence);
            assertEquals(bY, find(root, b.id()).y, "and still in the flow: nothing slides under it yet");

            s.presence.put(a.id(), 0f);
            root = frame(gui);
            assertNull(find(root, a.id()), "detached in the frame it reaches nothing");
            assertEquals(NodeLayout.ABSENT, a.layout(), "and the snapshot published that frame does not name it");
            assertEquals(List.of(), s.left, "but not released yet: an edit already on its way may want it back");

            root = frame(gui);
            assertEquals(List.of(a.id()), s.left, "released by the next frame");
            assertTrue(find(root, b.id()).y < bY, "and the gap it left is closed");
        }
    }

    @Test
    void aLeavingNodePutBackIsTheSameNodeArrivingFromWhereItWas() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column();
            Node a = row(gui);
            gui.root().children(list.children(a));
            RetainedNode ra = find(frame(gui), a.id());

            s.presence.put(a.id(), 0.4f);
            a.remove();
            frame(gui);
            list.append(a);
            RetainedNode root = frame(gui);

            assertSame(ra, find(root, a.id()), "not rebuilt");
            assertFalse(ra.leaving);
            assertFalse(ra.hitInert());
            assertEquals(List.of(a.id()), s.entered, "reported as arriving");
            assertEquals(0.4f, ra.presence, "from the presence it was drawn at");
        }
    }

    @Test
    void aNodePutBackInTheFrameAfterItDepartedIsStillTheSameNode() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column();
            Node a = row(gui);
            gui.root().children(list.children(a));
            RetainedNode ra = find(frame(gui), a.id());

            s.presence.put(a.id(), 0f);
            a.remove();
            assertNull(find(frame(gui), a.id()), "gone this frame");
            list.append(a);   // decided from the snapshot before that frame: it arrives at the next drain
            RetainedNode root = frame(gui);

            assertSame(ra, find(root, a.id()));
            assertEquals(List.of(), s.left, "never released");
        }
    }

    @Test
    void aSecondRemoveIsNowAndARemovalNotTakenIsImmediate() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column();
            Node a = row(gui);
            Node b = row(gui);
            gui.root().children(list.children(a, b));
            frame(gui);

            s.presence.put(a.id(), 0.5f);
            a.remove();
            assertNotNull(find(frame(gui), a.id()));
            a.remove();
            assertNull(find(frame(gui), a.id()), "a second remove of a leaving node takes it out at once");

            s.take = false;
            b.remove();
            assertNull(find(frame(gui), b.id()), "not taken by the exit motion: removed as before");
        }
    }

    @Test
    void anArrivalWithNoEnterMotionWalksNothing() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.exitMotion(new Scripted());   // an exit motion alone does not make an arrival owe a walk
            Node list = gui.column();
            gui.root().children(list.children(row(gui)));
            frame(gui);
            long before = gui.presenceWalks();
            for (int i = 0; i < 5; i++) {
                Node added = row(gui);
                list.append(added);
                RetainedNode r = find(frame(gui), added.id());
                assertEquals(1f, r.presence, "fully present the frame it is added");
            }
            assertEquals(before, gui.presenceWalks(), "an arrival under EnterMotion.NONE costs no whole-tree walk");
        }
    }

    @Test
    void anArrivalWithAnEnterMotionIsStillPresented() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column();
            gui.root().children(list);
            frame(gui);
            long before = gui.presenceWalks();
            Node added = row(gui);
            list.append(added);
            RetainedNode r = find(frame(gui), added.id());
            assertTrue(gui.presenceWalks() > before);
            assertEquals(0f, r.presence);
        }
    }

    @Test
    void aReturningNodeIsPresentedEvenWithNoEnterMotion() {
        Scripted s = new Scripted();
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.exitMotion(s);
            Node list = gui.column();
            Node a = row(gui);
            gui.root().children(list.children(a));
            RetainedNode ra = find(frame(gui), a.id());

            s.presence.put(a.id(), 0.4f);
            a.remove();
            frame(gui);
            assertEquals(0.4f, ra.presence);
            list.append(a);
            long before = gui.presenceWalks();
            frame(gui);
            assertTrue(gui.presenceWalks() > before, "a node back from leaving is owed a walk");
            assertFalse(ra.leaving);
            assertEquals(1f, ra.presence, "and the walk brings it back to whole");
        }
    }

    @Test
    void withNoSourcesARemovalIsImmediateAndEveryNodeIsFullyPresent() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node list = gui.column();
            Node a = row(gui);
            gui.root().children(list);
            frame(gui);
            list.append(a);
            RetainedNode ra = find(frame(gui), a.id());
            assertEquals(1f, ra.presence);
            a.remove();
            assertNull(find(frame(gui), a.id()));
        }
    }

    /**
     * A panel rebuilt by removing everything and appending new nodes is a replacement: what leaves lets go of its place
     * and fades where it stood, so what arrives takes its own place at once and the panel never doubles (MainFrame's
     * Open dialog, the Inspector's picker, Vexplore's rail).
     */
    @Test
    void aPanelRebuiltWholeIsReplacedInPlaceNotDoubled() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node panel = gui.column();
            Node old = row(gui);
            gui.root().children(panel.children(old));
            RetainedNode was = find(frame(gui), old.id());
            float oldY = was.y;

            old.remove();
            Node fresh = row(gui);
            panel.append(fresh);
            RetainedNode root = frame(gui);

            RetainedNode r = find(root, fresh.id());
            assertEquals(oldY, r.y, "the newcomer takes the place, not the place after the old row");
            assertTrue(was.leaving && was.lifted, "the old row is lifted out of the flow, fading");
            assertEquals(oldY, was.y, "where it stood");
        }
    }

    /** A keyed list that drops one row and adds another kept the rest: the dropped row keeps its place, as ever. */
    @Test
    void anEditThatKeepsSomethingIsNotAReplacement() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column();
            Node kept = row(gui);
            Node dropped = row(gui);
            gui.root().children(list.children(kept, dropped));
            RetainedNode d = find(frame(gui), dropped.id());

            dropped.remove();
            list.append(row(gui));
            frame(gui);

            assertTrue(d.leaving);
            assertFalse(d.lifted, "something was kept, so this is a removal, and it holds its place");
        }
    }

    /** A node the application already faded to nothing has gone: it leaves at once, not after one more fade. */
    @Test
    void aNodeFadedOutByHandLeavesAtOnce() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column();
            Node gone = row(gui);
            gui.root().children(list.children(gone, row(gui)));
            frame(gui);

            gone.opacity(0f);
            frame(gui);
            gone.remove();

            assertNull(find(frame(gui), gone.id()), "no dead pause before its neighbours close up");
        }
    }

    /** A virtualised list realizing a row as it scrolls is showing what was always there, not adding it. */
    @Test
    void aRecyclingContainersChildrenNeitherArriveNorLeave() {
        Scripted s = new Scripted();
        try (Gui gui = withScripted(s)) {
            Node list = gui.column().childMotion(false);
            Node first = row(gui);
            gui.root().children(list.children(first));
            frame(gui);

            list.append(row(gui));
            frame(gui);
            first.remove();
            RetainedNode root = frame(gui);

            assertEquals(List.of(), s.entered, "a realized row does not arrive");
            assertNull(find(root, first.id()), "a row scrolled away is gone at once, not left to fade");
        }
    }
}
