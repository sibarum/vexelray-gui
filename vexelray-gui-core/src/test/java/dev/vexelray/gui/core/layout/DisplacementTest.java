package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplacementTest {

    /** A motion source that holds whatever displacement it is told to, and records what it was told moved. */
    private static final class Recording implements LayoutMotion {

        final List<String> moves = new ArrayList<>();
        final Map<Long, float[]> offsets = new HashMap<>();

        void offset(RetainedNode n, float dx, float dy) {
            offsets.put(n.id, new float[] {dx, dy});
        }

        @Override
        public void moved(RetainedNode node, float fromX, float fromY, float toX, float toY) {
            moves.add(node.id + ":" + fromX + "," + fromY + "->" + toX + "," + toY);
        }

        @Override
        public float displacementX(RetainedNode node) {
            float[] o = offsets.get(node.id);
            return o == null ? 0f : o[0];
        }

        @Override
        public float displacementY(RetainedNode node) {
            float[] o = offsets.get(node.id);
            return o == null ? 0f : o[1];
        }
    }

    private static long nextId = 1;

    private static RetainedNode node(float x, float y) {
        RetainedNode n = new RetainedNode(nextId++, NodeKind.BOX);
        n.x = x;
        n.y = y;
        n.viewX = x;
        n.viewY = y;
        return n;
    }

    private static RetainedNode child(RetainedNode parent, float x, float y) {
        RetainedNode n = node(x, y);
        n.parent = parent;
        parent.children.add(n);
        return n;
    }

    /** Layout writes a rect; settle records it. Nothing has moved yet, so nothing is reported. */
    private static void layOut(RetainedNode n, float x, float y) {
        n.x = x;
        n.y = y;
        n.viewX = x;
        n.viewY = y;
    }

    @Test
    void aNodeSeenForTheFirstTimeArrivedRatherThanMoved() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        child(root, 10f, 20f);

        Displacement.settle(root, motion);

        assertTrue(motion.moves.isEmpty(), "a first layout is an arrival, not a journey from the origin");
    }

    @Test
    void aMoveIsReportedWithBothEnds() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 0f, 40f);
        Displacement.settle(root, motion);

        layOut(row, 0f, 100f);
        Displacement.settle(root, motion);

        assertEquals(List.of(row.id + ":0.0,40.0->0.0,100.0"), motion.moves);
    }

    @Test
    void anUndisplacedTreeIsDrawnWhereLayoutPutItAndReportsStill() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 5f, 40f);
        Displacement.settle(root, motion);

        assertFalse(Displacement.displace(root, motion), "nothing is catching up");
        assertEquals(5f, row.x);
        assertEquals(40f, row.y);
    }

    @Test
    void aDisplacedNodeIsDrawnShortOfItsLayoutPosition() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 0f, 100f);
        Displacement.settle(root, motion);
        motion.offset(row, 0f, -60f);   // still 60px above where it belongs

        assertTrue(Displacement.displace(root, motion));
        assertEquals(40f, row.y);
        assertEquals(40f, row.viewY, "the content viewport travels with the border box, or the clip lags behind");
    }

    @Test
    void displacementIsInheritedBySubtrees() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 0f, 100f);
        RetainedNode label = child(row, 8f, 104f);
        Displacement.settle(root, motion);
        motion.offset(row, 0f, -60f);

        Displacement.displace(root, motion);

        assertEquals(44f, label.y, "a row that slides carries its label with it");
    }

    @Test
    void nestedDisplacementsAdd() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 0f, 100f);
        RetainedNode label = child(row, 0f, 100f);
        Displacement.settle(root, motion);
        motion.offset(row, 0f, -10f);
        motion.offset(label, 0f, -5f);

        Displacement.displace(root, motion);

        assertEquals(90f, row.y);
        assertEquals(85f, label.y, "the label lags its row, which lags its own destination");
    }

    /**
     * The property that makes the whole thing safe: displacing is a function of the settled position, not of
     * whatever the rect currently holds. Frames that lay out and frames that do not must agree, or a transition
     * accumulates a little more error every frame that skips layout and never gets it back.
     */
    @Test
    void displacingTwiceWithoutARelayoutIsTheSameAsDisplacingOnce() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 0f, 100f);
        RetainedNode label = child(row, 0f, 100f);
        Displacement.settle(root, motion);
        motion.offset(row, 0f, -60f);

        Displacement.displace(root, motion);
        float once = row.y;
        float onceLabel = label.y;
        Displacement.displace(root, motion);
        Displacement.displace(root, motion);

        assertEquals(once, row.y, "displacement is absolute, so repeating it changes nothing");
        assertEquals(onceLabel, label.y);
    }

    @Test
    void settlingReadsThroughADisplacementRatherThanRecordingIt() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 0f, 100f);
        Displacement.settle(root, motion);
        motion.offset(row, 0f, -60f);
        Displacement.displace(root, motion);   // drawn at 40, belongs at 100

        // A later frame lays out and puts it in the same place. It has not moved, whatever the rect says.
        layOut(row, 0f, 100f);
        motion.moves.clear();
        Displacement.settle(root, motion);

        assertTrue(motion.moves.isEmpty(), "a node mid-transition has not moved just because it is drawn early");
        assertEquals(100f, row.layoutY);
    }

    @Test
    void aDecayedDisplacementLandsExactlyOnTheLayoutPosition() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 0f, 100f);
        Displacement.settle(root, motion);

        motion.offset(row, 0f, -60f);
        Displacement.displace(root, motion);
        motion.offset(row, 0f, 0f);
        assertFalse(Displacement.displace(root, motion), "zero displacement is the resting state, not near it");
        assertEquals(100f, row.y);
    }

    @Test
    void everyChildIsPlacedEvenWhenAnEarlierSiblingIsStill() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode first = child(root, 0f, 0f);
        RetainedNode second = child(root, 0f, 40f);
        Displacement.settle(root, motion);
        motion.offset(second, 0f, -20f);   // only the second is moving

        assertTrue(Displacement.displace(root, motion), "one moving sibling makes the tree in motion");
        assertEquals(0f, first.y);
        assertEquals(20f, second.y);
    }

    @Test
    void aNodeAddedMidTransitionIsLeftWhereLayoutPutIt() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode row = child(root, 0f, 100f);
        Displacement.settle(root, motion);
        motion.offset(row, 0f, -60f);

        RetainedNode fresh = child(root, 0f, 200f);   // never settled
        Displacement.displace(root, motion);

        assertEquals(200f, fresh.y, "no recorded position means nothing to displace from");
    }
}
