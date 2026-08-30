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

    // --- scrolling is not moving --------------------------------------------------------------------------

    /**
     * Scroll {@code scroller} to {@code offset}, carrying everything under it the way FlexLayout does.
     *
     * <p>The whole subtree, not the direct children: a scroller places its children at {@code baseY - scrollY}
     * and they place theirs relative to that, so an offset moves every descendant. Carrying only one level is
     * what an earlier version of this fixture did, and it made the nested case look like a bug in the code it
     * was testing rather than in the fixture — the grandchild really had moved relative to its own parent.
     */
    private static void scrollTo(RetainedNode scroller, float offset) {
        float delta = offset - scroller.scrollY;
        scroller.scrollY = offset;
        for (RetainedNode child : scroller.children) {
            carry(child, delta);
        }
    }

    private static void carry(RetainedNode node, float delta) {
        layOut(node, node.x, node.y - delta);
        for (RetainedNode child : node.children) {
            carry(child, delta);
        }
    }

    @Test
    void scrollingAContainerDoesNotMoveWhatIsInIt() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode scroller = child(root, 0f, 0f);
        child(scroller, 0f, 0f);
        child(scroller, 0f, 40f);
        Displacement.settle(root, motion);

        scrollTo(scroller, 25f);
        Displacement.settle(root, motion);

        assertTrue(motion.moves.isEmpty(),
                "a row carried by the scroll it is inside of has not moved: " + motion.moves);
    }

    @Test
    void aMoveDuringAScrollReportsTheMoveAndNotTheScroll() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode scroller = child(root, 0f, 0f);
        RetainedNode row = child(scroller, 0f, 40f);
        Displacement.settle(root, motion);

        scrollTo(scroller, 10f);        // row is now at 30
        layOut(row, 0f, row.y + 60f);   // and genuinely moves 60 further down, to 90
        Displacement.settle(root, motion);

        assertEquals(List.of(row.id + ":0.0,30.0->0.0,90.0"), motion.moves,
                "the reported distance is the move, measured from where the scroll had already put it");
    }

    @Test
    void nestedScrollersAccumulate() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode outer = child(root, 0f, 0f);
        RetainedNode inner = child(outer, 0f, 0f);
        child(inner, 0f, 50f);
        Displacement.settle(root, motion);

        // Both scroll, in the same direction: the leaf is carried by the sum, and neither half is a move.
        scrollTo(outer, 15f);
        scrollTo(inner, 20f);
        Displacement.settle(root, motion);

        assertTrue(motion.moves.isEmpty(), "two nested scrolls still add up to no movement: " + motion.moves);
    }

    /**
     * A container sliding down the page is a move of the container and of nothing inside it — its contents kept
     * their places within it, and {@link Displacement#displace} inherits the container's displacement down the
     * subtree, so they are carried by it.
     *
     * <p>This is the double-count, and it is the one that shows up as soon as a whole page is enrolled rather
     * than one list: a child reported as having moved gets a lag of its own <em>on top of</em> the one it
     * inherits, so it travels twice the distance and arrives from somewhere it never was. The deeper a control
     * sits, the further it overshoots.
     */
    @Test
    void aContainerThatMovesCarriesItsContentsRatherThanMovingThem() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode card = child(root, 0f, 0f);
        RetainedNode row = child(card, 0f, 40f);
        RetainedNode inRow = child(row, 0f, 48f);
        Displacement.settle(root, motion);

        layOut(card, 0f, 100f);       // the card slides down the page...
        layOut(row, 0f, 140f);        // ...and everything in it keeps its place inside it
        layOut(inRow, 0f, 148f);
        Displacement.settle(root, motion);

        assertEquals(List.of(card.id + ":0.0,0.0->0.0,100.0"), motion.moves,
                "one move, at the node that actually moved");
    }

    /** And a child that moves <em>while</em> its container does reports only its own share of the distance. */
    @Test
    void aMoveInsideAMovingContainerReportsOnlyItsOwnShare() {
        Recording motion = new Recording();
        RetainedNode root = node(0f, 0f);
        RetainedNode card = child(root, 0f, 0f);
        RetainedNode row = child(card, 0f, 40f);
        Displacement.settle(root, motion);

        layOut(card, 0f, 100f);       // carried 100...
        layOut(row, 0f, 165f);        // ...and moved 25 within the card on top of it
        Displacement.settle(root, motion);

        assertEquals(List.of(card.id + ":0.0,0.0->0.0,100.0", row.id + ":0.0,140.0->0.0,165.0"),
                motion.moves, "the row travels 25, not 125");
    }
}
