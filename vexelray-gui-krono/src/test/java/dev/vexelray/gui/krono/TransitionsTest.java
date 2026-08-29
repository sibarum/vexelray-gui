package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.anim.Ease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * Displacement decay, driven headlessly.
 *
 * <p>{@code Gui.frame} is never called and no layout runs: these drive {@link Transitions#moved} directly with
 * the two positions the framework would have handed it, and read back the displacement it reports. That keeps
 * the module boundary honest — placing nodes is {@code gui-core}'s job and {@code DisplacementTest} covers it —
 * while pinning the part that is this module's: how a lag decays, and what happens when one is interrupted.
 */
class TransitionsTest {

    private static final Dur OVER = ms(200);

    private long nextId = 1;

    private KronoGui headless() {
        return KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
    }

    /** A retained node standing in for one the layout pass would have produced. */
    private RetainedNode retained(Node handle) {
        return new RetainedNode(handle == null ? nextId++ : handle.id(), NodeKind.BOX);
    }

    private RetainedNode retainedUnder(RetainedNode parent) {
        RetainedNode n = new RetainedNode(nextId++, NodeKind.BOX);
        n.parent = parent;
        parent.children.add(n);
        return n;
    }

    @Test
    @DisplayName("a node nobody enrolled never lags")
    void unenrolledNodesSnap() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            RetainedNode row = retained(null);

            moves.moved(row, 0f, 0f, 0f, 100f);

            assertEquals(0f, moves.displacementY(row), "not enrolled, so it is drawn where layout put it");
            assertTrue(moves.settled());
        }
    }

    @Test
    @DisplayName("an enrolled node starts a full step behind and arrives exactly")
    void anEnrolledNodeDecaysToZero() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node handle = krono.gui().box();
            moves.follow(handle, OVER, Ease.LINEAR);
            RetainedNode row = retained(handle);

            moves.moved(row, 0f, 0f, 0f, 100f);
            assertEquals(-100f, moves.displacementY(row), "drawn where it was, which is 100px above where it is");
            assertFalse(moves.settled());

            krono.tick(ms(100));
            assertEquals(-50f, moves.displacementY(row), 0.5f, "half the time, half the distance");

            krono.tick(ms(200));
            assertEquals(0f, moves.displacementY(row), "the resting state is zero, not nearly zero");
            assertTrue(moves.settled());
        }
    }

    @Test
    @DisplayName("both axes lag independently")
    void horizontalAndVerticalDecayTogether() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node handle = krono.gui().box();
            moves.follow(handle, OVER, Ease.LINEAR);
            RetainedNode row = retained(handle);

            moves.moved(row, 10f, 0f, 40f, 100f);

            assertEquals(-30f, moves.displacementX(row));
            assertEquals(-100f, moves.displacementY(row));
        }
    }

    @Test
    @DisplayName("a container enrols its rows, which it does not have to know by name")
    void followChildrenEnrolsByParent() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node body = krono.gui().box();
            moves.followChildren(body, OVER, Ease.LINEAR);
            RetainedNode retainedBody = retained(body);
            RetainedNode row = retainedUnder(retainedBody);

            moves.moved(row, 0f, 0f, 0f, 60f);

            assertEquals(-60f, moves.displacementY(row));
        }
    }

    @Test
    @DisplayName("a row's own enrolment beats the container's")
    void ownTermsWin() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node body = krono.gui().box();
            Node special = krono.gui().box();
            moves.followChildren(body, OVER, Ease.LINEAR);
            moves.follow(special, Dur.ZERO, Ease.LINEAR);       // this one does not animate
            RetainedNode retainedBody = retained(body);
            RetainedNode row = new RetainedNode(special.id(), NodeKind.BOX);
            row.parent = retainedBody;
            retainedBody.children.add(row);

            moves.moved(row, 0f, 0f, 0f, 60f);

            assertEquals(0f, moves.displacementY(row), "its own zero duration wins over the container's 200ms");
        }
    }

    /**
     * The property the whole class exists for. A user who re-drops a row before the first slide has finished
     * must never see it snap back to its old slot, so a second move is measured from where the row is currently
     * drawn rather than from where layout last put it.
     */
    @Test
    @DisplayName("a move mid-flight continues from where the node is drawn")
    void interruptionIsContinuous() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node handle = krono.gui().box();
            moves.follow(handle, OVER, Ease.LINEAR);
            RetainedNode row = retained(handle);

            moves.moved(row, 0f, 0f, 0f, 100f);
            krono.tick(ms(100));
            float lagNow = moves.displacementY(row);            // ≈ -50: drawn at 50, belongs at 100
            float drawnAt = 100f + lagNow;

            // Layout moves it again, now to 200. It is drawn at ~50, so it is 150 short of the new home.
            moves.moved(row, 0f, 100f, 0f, 200f);

            assertEquals(drawnAt, 200f + moves.displacementY(row), 0.5f,
                    "the drawn position is unchanged by the move itself; only its destination changed");
            assertEquals(-150f, moves.displacementY(row), 0.5f);
        }
    }

    @Test
    @DisplayName("the superseded ramp stops writing rather than fighting the new one")
    void anInterruptedRampDoesNotResurrect() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node handle = krono.gui().box();
            moves.follow(handle, OVER, Ease.LINEAR);
            RetainedNode row = retained(handle);

            moves.moved(row, 0f, 0f, 0f, 100f);
            krono.tick(ms(100));
            moves.moved(row, 0f, 100f, 0f, 200f);

            // Long enough for the *first* ramp to have finished several times over.
            krono.tick(ms(1000));

            assertEquals(0f, moves.displacementY(row), "the loser's completion must not zero — or move — anything");
            assertTrue(moves.settled());
        }
    }

    @Test
    @DisplayName("a zero duration is the honest reduced-motion setting")
    void zeroDurationNeverDisplaces() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node handle = krono.gui().box();
            moves.follow(handle, Dur.ZERO, Ease.LINEAR);
            RetainedNode row = retained(handle);

            moves.moved(row, 0f, 0f, 0f, 100f);

            assertEquals(0f, moves.displacementY(row));
            assertTrue(moves.settled());
        }
    }

    @Test
    @DisplayName("unfollowing drops the lag a node was carrying")
    void unfollowSnapsToTheLayoutPosition() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node handle = krono.gui().box();
            moves.follow(handle, OVER, Ease.LINEAR);
            RetainedNode row = retained(handle);
            moves.moved(row, 0f, 0f, 0f, 100f);

            moves.unfollow(handle);

            assertEquals(0f, moves.displacementY(row));
            assertTrue(moves.settled());

            krono.tick(ms(100));
            assertEquals(0f, moves.displacementY(row), "the abandoned ramp has no one left to write to");
        }
    }

    @Test
    @DisplayName("a node that did not actually move starts nothing")
    void aZeroLengthMoveIsNotATransition() {
        try (KronoGui krono = headless()) {
            Transitions moves = Transitions.on(krono);
            Node handle = krono.gui().box();
            moves.follow(handle, OVER, Ease.LINEAR);
            RetainedNode row = retained(handle);

            moves.moved(row, 0f, 100f, 0f, 100f);

            assertTrue(moves.settled());
        }
    }
}
