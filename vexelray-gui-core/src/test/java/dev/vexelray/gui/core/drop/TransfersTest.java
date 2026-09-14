package dev.vexelray.gui.core.drop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is held, and the drag as a published read-model — with no {@code Gui} and no pointer
 * (docs/plans/gui-decomposition.md §4).
 *
 * <p>The publish-on-change rule is the one worth a test. A drag held still over one seam is the common case, and
 * re-committing an identical value sixty times a second would wake every subscriber for nothing — but that is
 * invisible from outside, because the drawn result is identical either way. Counting commits is the only way to
 * see it.
 */
class TransfersTest {

    /** A real session opened at a point; the drop stays {@link Drop#NONE} because nothing resolves here. */
    private static DragSession at(float x, float y) {
        return DragSession.open(Payload.empty(), x, y);
    }

    /** Every drag state committed, in order — the count is what the publish-on-change rule is about. */
    private static List<DragState> watch(Transfers transfers) {
        List<DragState> seen = new ArrayList<>();
        transfers.drag().onCommit(versioned -> seen.add(versioned.value()));
        return seen;
    }

    @Test
    void nothingIsHeldUntilSomethingIsPutInHand() {
        Transfers transfers = new Transfers();

        assertSame(Transfer.NONE, transfers.held());

        Transfer something = Transfer.NONE;   // the identity matters here, not the payload
        transfers.hold(something);
        assertSame(something, transfers.held());

        transfers.hold(null);
        assertSame(Transfer.NONE, transfers.held(), "dropping what is held is saying null, not saying nothing");
    }

    /**
     * A drag held still is the common case, and publishing it again would wake every subscriber for nothing.
     * The record's equality is what makes that comparison honest rather than a field-by-field check that
     * forgets one.
     */
    @Test
    void aDragThatHasNotMovedIsNotRepublished() {
        Transfers transfers = new Transfers();
        List<DragState> seen = watch(transfers);

        transfers.publish(at(10, 20));
        transfers.publish(at(10, 20));
        transfers.publish(at(10, 20));

        assertEquals(1, seen.size(), "one commit for three identical frames");
    }

    @Test
    void aDragThatMovedIsPublishedAgain() {
        Transfers transfers = new Transfers();
        List<DragState> seen = watch(transfers);

        transfers.publish(at(10, 20));
        transfers.publish(at(11, 20));

        assertEquals(2, seen.size());
        assertEquals(11f, seen.get(1).x(), 0.0001f);
    }

    /** No session is a real state rather than an absence: the ghost has to stop being drawn. */
    @Test
    void theEndOfADragIsItselfPublished() {
        Transfers transfers = new Transfers();
        List<DragState> seen = watch(transfers);

        transfers.publish(at(10, 20));
        transfers.publish(null);

        assertEquals(2, seen.size());
        assertTrue(seen.get(0).active());
        assertSame(DragState.NONE, seen.get(1), "and it settles back to nothing rather than a stale position");
    }

    @Test
    void noDragAtAllPublishesNothing() {
        Transfers transfers = new Transfers();
        List<DragState> seen = watch(transfers);

        transfers.publish(null);
        transfers.publish(null);

        assertEquals(0, seen.size(), "the initial value is already NONE, so there is nothing to say");
    }
}
