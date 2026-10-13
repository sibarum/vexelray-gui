package dev.vexelray.gui.core.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The focus ring fading: in round the node focus reaches, out round the one it left, and neither at all unless the
 * application asked — a fade of zero is the state the renderer was always handed.
 */
class FocusFadeTest {

    private static final long MS = 1_000_000L;

    @Test
    void withoutADurationNothingFades() {
        FocusFade f = new FocusFade();
        f.moved(1, FocusCause.KEYBOARD, 2);
        assertFalse(f.step(10 * MS), "nothing to step");
        assertEquals(new FocusState(2, FocusCause.KEYBOARD, true), f.applyTo(2, FocusCause.KEYBOARD, true),
                "the ring whole, and no ring leaving");
    }

    @Test
    void theNewRingFadesInAsTheOldOneFadesOut() {
        FocusFade f = new FocusFade();
        f.duration(100 * MS);
        f.moved(1, FocusCause.KEYBOARD, 2);

        FocusState start = f.applyTo(2, FocusCause.KEYBOARD, true);
        assertEquals(0f, start.shown(), 1e-6f, "the arriving ring starts unseen");
        assertEquals(1, start.leavingId(), "the node focus left still has its ring");
        assertEquals(1f, start.leavingShown(), 1e-6f, "whole, to begin with");

        long t = 1_000 * MS;
        assertTrue(f.step(t));
        for (int i = 0; i < 20; i++) {
            t += 10 * MS;
            f.step(t);
        }
        FocusState end = f.applyTo(2, FocusCause.KEYBOARD, true);
        assertEquals(1f, end.shown(), 1e-6f, "faded fully in");
        assertEquals(-1, end.leavingId(), "and the old ring gone");
        assertFalse(f.step(t + 10 * MS), "nothing left to step, so no frame is owed");
    }

    /** Back to the node whose ring is leaving: it picks up from where it had faded to, rather than blinking off. */
    @Test
    void comingStraightBackResumesTheLeavingRing() {
        FocusFade f = new FocusFade();
        f.duration(100 * MS);
        f.moved(1, FocusCause.KEYBOARD, 2);
        f.step(1_000 * MS);
        f.step(1_040 * MS);   // the first step counts one short frame, then 40ms: about halfway
        float left = f.applyTo(2, FocusCause.KEYBOARD, true).leavingShown();
        assertTrue(left > 0f && left < 1f, "mid-fade");

        f.moved(2, FocusCause.KEYBOARD, 1);
        FocusState back = f.applyTo(1, FocusCause.KEYBOARD, true);
        assertEquals(left, back.shown(), 1e-6f, "node 1's ring carries on from the same opacity");
        assertEquals(2, back.leavingId());
    }
}
