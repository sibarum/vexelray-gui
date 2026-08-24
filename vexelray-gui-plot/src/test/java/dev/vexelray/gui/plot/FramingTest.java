package dev.vexelray.gui.plot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framing policy. Nothing here is a theorem — a frame is a preference, and the tests are the preference
 * written down so that changing it is a decision rather than a drift.
 *
 * <p>Two of them are the cases the policy exists for, and they pull in opposite directions: a parabola must keep
 * its whole range (a robust fit that discards the tails would clip a curve that genuinely goes there), and a
 * curve with a pole must <em>not</em> be framed around the pole (a fit that keeps everything would leave the
 * interesting part a flat line on the axis). Any policy that passes only one of them is the naive one.
 */
class FramingTest {

    private static final Expr X = new Expr.Param("x");

    /** A curve whose values really are spread over two orders of magnitude keeps all of them. */
    @Test
    void aParabolaKeepsItsWholeRange() {
        Frame frame = Framing.automatic(new Expr.Power(X, new Expr.Const(2.0)));
        assertEquals(0.0, frame.yLo(), 1e-9, "the floor of x squared is zero, and zero is in reach of the fit");
        assertTrue(frame.yHi() >= 100, "x squared reaches 100 on [-10, 10]; the frame must too, got " + frame.yHi());
        assertTrue(frame.yHi() < 200, "and must not leave the curve in the bottom half, got " + frame.yHi());
    }

    /**
     * The case a naive min/max fit loses. Near the origin {@code 1/x} climbs past 120 on this window while the
     * bulk of the curve sits within a couple of units of the axis; framing to the extremes would draw the whole
     * curve as a horizontal line with two spikes.
     */
    @Test
    void aPoleDoesNotDragTheFrameOutWithIt() {
        Expr reciprocal = new Expr.Div(new Expr.Const(1.0), X);
        double reached = 1 / (0.5 / 240 * 2);          // the nearest column to the origin that is not the pole
        assertTrue(reached > 100, "the fixture only means something if the curve really does climb: " + reached);
        Frame frame = Framing.automatic(reciprocal, -0.5, 0.5);
        assertTrue(frame.yHi() < 50, "the fit must follow the bulk, not the spike, got " + frame.yHi());
        assertTrue(frame.yHi() > 1, "but must still show the curve, got " + frame.yHi());
    }

    /** Symmetric curve, symmetric frame — and the padding does not lose the sign. */
    @Test
    void aSineIsFramedAroundItsAxis() {
        Frame frame = Framing.automatic(new Expr.Sin(X));
        assertTrue(frame.yLo() < 0 && frame.yHi() > 0, "sine straddles zero: " + frame);
        assertEquals(frame.yHi(), -frame.yLo(), 1e-9, "and does so symmetrically");
        assertTrue(frame.yHi() >= 1, "the peaks are at 1 and must be inside the frame, got " + frame.yHi());
    }

    /**
     * A constant has no range at all. The fit gives it a window to sit in the middle of, and the result is
     * pinned here because it is the exact output of every clause in the policy — degenerate widening, then the
     * padding, then the snap to a round step.
     */
    @Test
    void aConstantIsGivenAWindowToSitIn() {
        Frame frame = Framing.automatic(new Expr.Const(1.0));
        assertEquals(0.0, frame.yLo(), 1e-9);
        assertEquals(2.2, frame.yHi(), 1e-9);
    }

    /** Zero is joined when it is nearly in view, and left alone when joining it would squash the curve. */
    @Test
    void theAxisIsJoinedOnlyWhenItIsNearby() {
        Frame near = Framing.automatic(new Expr.Add(X, new Expr.Const(1.0)), 0, 9);
        assertEquals(0.0, near.yLo(), 1e-9, "1..10: the axis is a third of the curve's height away");
        Frame far = Framing.automatic(new Expr.Add(X, new Expr.Const(1000.0)), 0, 2);
        assertTrue(far.yLo() > 900, "1000..1002 is nowhere near the axis; joining it would flatten the curve");
    }

    /** Every column a pole, so there is nothing to measure and nothing to be clever about. */
    @Test
    void anExpressionWithNothingBoundedFallsBackToTheDefault() {
        Expr everywhereUnbounded = new Expr.Div(new Expr.Const(1.0), new Expr.Sub(X, X));
        Frame frame = Framing.automatic(everywhereUnbounded);
        assertEquals(-Framing.DEFAULT_HALF_HEIGHT, frame.yLo(), 1e-9);
        assertEquals(Framing.DEFAULT_HALF_HEIGHT, frame.yHi(), 1e-9);
    }

    @Test
    void anExpressionWithNoRealValuesFallsBackToo() {
        Frame frame = Framing.automatic(new Expr.Log(new Expr.Const(-1.0)));
        assertEquals(-Framing.DEFAULT_HALF_HEIGHT, frame.yLo(), 1e-9);
        assertEquals(Framing.DEFAULT_HALF_HEIGHT, frame.yHi(), 1e-9);
    }

    /** Refitting is the same fit against a window the user has moved to. */
    @Test
    void refittingFollowsThePan() {
        Expr line = new Expr.Mul(X, new Expr.Const(3.0));
        Frame moved = Framing.refit(line, new Frame(100, 110, -1, 1));
        assertTrue(moved.yLo() >= 290 && moved.yHi() <= 340, "3x on [100, 110] runs 300..330, got " + moved);
        assertEquals(100.0, moved.xLo(), 1e-9, "and a refit is vertical only");
    }

    /** 1, 2 or 5 times a power of ten, and nothing else. */
    @Test
    void tickStepsAreRoundNumbers() {
        assertEquals(10.0, Framing.tickStep(100, 8), 1e-12);
        assertEquals(0.1, Framing.tickStep(1, 10), 1e-12);
        assertEquals(0.05, Framing.tickStep(0.35, 5), 1e-12);
        assertEquals(1000.0, Framing.tickStep(7000, 8), 1e-12);
        assertEquals(2.0, Framing.tickStep(20, 8), 1e-12, "to the nearest round step, not up to the next one");
    }
}
