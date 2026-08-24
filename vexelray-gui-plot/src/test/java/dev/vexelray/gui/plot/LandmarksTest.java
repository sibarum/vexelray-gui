package dev.vexelray.gui.plot;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The landmark finder. Two things are being pinned here and they pull opposite ways: everything really there is
 * found and pinned to a precision a label could print, and <b>nothing that is not there survives</b> — which is
 * the harder half, because a discontinuity looks exactly like a root to any test that only samples.
 */
class LandmarksTest {

    private static final Expr X = new Expr.Param("x");

    private static Expr constant(double v) {
        return new Expr.Const(v);
    }

    /** {@code x² − 4}: roots at ±2, a minimum at the origin, no poles, and a y-intercept it does not need. */
    @Test
    void aParabolaHasTwoRootsAndOneMinimum() {
        Expr f = new Expr.Sub(new Expr.Power(X, constant(2)), constant(4));
        List<Landmark> found = Landmarks.find(f, "x", -10, 10);
        assertEquals(List.of(-2.0, 2.0), xs(found, Landmark.Kind.ROOT), "roots of x²−4");
        assertEquals(List.of(0.0), xs(found, Landmark.Kind.MINIMUM), "its floor is at the origin");
        assertEquals(-4, height(found, Landmark.Kind.MINIMUM), 1e-9, "and the floor is −4");
        assertTrue(only(found, Landmark.Kind.MAXIMUM).isEmpty(), "a parabola has no maximum");
        assertTrue(only(found, Landmark.Kind.POLE).isEmpty(), "nor a pole");
        assertTrue(only(found, Landmark.Kind.INFLECTION).isEmpty(), "a parabola never changes its bend");
        assertTrue(only(found, Landmark.Kind.Y_INTERCEPT).isEmpty(),
                "the y-axis crossing is the floor, and the floor is the better thing to have said");
    }

    /** {@code x³ − 3x}: three roots, a maximum left of the origin, a minimum right of it, an inflection between. */
    @Test
    void aCubicHasBothKindsOfTurningPointAndTheInflectionBetweenThem() {
        Expr f = new Expr.Sub(new Expr.Power(X, constant(3)), new Expr.Mul(constant(3), X));
        List<Landmark> found = Landmarks.find(f, "x", -10, 10);
        assertEquals(3, only(found, Landmark.Kind.ROOT).size(), "x³−3x crosses at −√3, 0 and √3");
        assertEquals(-Math.sqrt(3), only(found, Landmark.Kind.ROOT).get(0).x(), 1e-6);
        assertEquals(Math.sqrt(3), only(found, Landmark.Kind.ROOT).get(2).x(), 1e-6);
        assertEquals(List.of(-1.0), xs(found, Landmark.Kind.MAXIMUM), "the hump is at −1");
        assertEquals(2, height(found, Landmark.Kind.MAXIMUM), 1e-6);
        assertEquals(List.of(1.0), xs(found, Landmark.Kind.MINIMUM), "the dip is at 1");
        assertEquals(-2, height(found, Landmark.Kind.MINIMUM), 1e-6);
        assertEquals(List.of(0.0), xs(found, Landmark.Kind.INFLECTION), "and the bend turns over at the origin");
    }

    /**
     * The flagship, and the case that a naive finder gets four different ways wrong. {@code 1÷(x²−1)} has two
     * asymptotes and a single maximum at the origin — and, crucially, <b>no roots and no turning points at
     * ±1</b>, where the finite samples either side of the blow-up fake both.
     */
    @Test
    void aCurveWithPolesReportsThePolesAndNothingSpuriousBesideThem() {
        Expr f = new Expr.Div(constant(1), new Expr.Sub(new Expr.Power(X, constant(2)), constant(1)));
        List<Landmark> found = Landmarks.find(f, "x", -5, 5);
        List<Double> poles = xs(found, Landmark.Kind.POLE);
        assertEquals(2, poles.size(), "two asymptotes, found by the arithmetic: " + poles);
        assertEquals(-1, poles.get(0), 1e-4, "and pinned to the pole, not to the edge of the smear around it");
        assertEquals(1, poles.get(1), 1e-4);
        assertTrue(only(found, Landmark.Kind.ROOT).isEmpty(), "1÷(x²−1) is never zero: " + xs(found, Landmark.Kind.ROOT));
        assertEquals(List.of(0.0), xs(found, Landmark.Kind.MAXIMUM), "the one real turning point is at the origin");
        assertEquals(-1, height(found, Landmark.Kind.MAXIMUM), 1e-6);
        assertTrue(only(found, Landmark.Kind.MINIMUM).isEmpty(),
                "the blow-ups at ±1 are not minima: " + xs(found, Landmark.Kind.MINIMUM));
    }

    /** A hyperbola: one pole, no roots, no turning points, and the pole carries no height. */
    @Test
    void aPoleHasNoHeightAndSaysSo() {
        List<Landmark> found = Landmarks.find(new Expr.Div(constant(1), X), "x", -5, 5);
        List<Landmark> poles = only(found, Landmark.Kind.POLE);
        assertEquals(1, poles.size());
        assertEquals(0, poles.get(0).x(), 1e-4);
        assertFalse(Landmark.Kind.POLE.hasHeight(), "the kind is what says a pole has no y, not the number");
        assertTrue(Double.isNaN(poles.get(0).y()));
    }

    /** The y-intercept is reported only where nothing better is already being said about that spot. */
    @Test
    void theYInterceptYieldsToEverythingElse() {
        Expr line = new Expr.Add(new Expr.Mul(constant(2), X), constant(3));       // 2x + 3
        List<Landmark> found = Landmarks.find(line, "x", -10, 10);
        assertEquals(List.of(0.0), xs(found, Landmark.Kind.Y_INTERCEPT));
        assertEquals(3, height(found, Landmark.Kind.Y_INTERCEPT), 1e-9);
        assertEquals(List.of(-1.5), xs(found, Landmark.Kind.ROOT), "and the line crosses the x-axis at −1.5");

        // x³ − 3x passes through the origin, so the crossing is reported as the root it is and not twice.
        Expr through = new Expr.Sub(new Expr.Power(X, constant(3)), new Expr.Mul(constant(3), X));
        assertTrue(only(Landmarks.find(through, "x", -10, 10), Landmark.Kind.Y_INTERCEPT).isEmpty(),
                "a root at zero is the better statement of the same fact");

        // x² + 3 has its floor on the y-axis, and "local minimum" says more than "y-intercept" does.
        Expr floored = new Expr.Add(new Expr.Power(X, constant(2)), constant(3));
        List<Landmark> above = Landmarks.find(floored, "x", -10, 10);
        assertEquals(List.of(0.0), xs(above, Landmark.Kind.MINIMUM));
        assertTrue(only(above, Landmark.Kind.Y_INTERCEPT).isEmpty());
    }

    /**
     * Sanity, and the reason the caps exist. {@code tan(1÷x)} has infinitely many of everything near the origin;
     * what must come back is not "as many as fit" but <b>none of that kind, and a notice</b>.
     */
    @Test
    void anUnboundedlyOscillatingCurveSuppressesTheKindRatherThanTruncatingIt() {
        Expr f = new Expr.Tan(new Expr.Div(constant(1), X));
        Landmarks.Survey survey = Landmarks.survey(f, "x", -1, 1);
        assertFalse(survey.suppressed().isEmpty(), "something here must have been too much to draw");
        for (Landmark.Kind kind : survey.suppressed()) {
            assertTrue(only(survey.found(), kind).isEmpty(), kind + " was suppressed, so none may be reported");
        }
        assertTrue(survey.found().size() <= Landmarks.PER_KIND * Landmark.Kind.values().length,
                "and nothing may exceed the cap: " + survey.found().size());
        assertTrue(survey.notice().startsWith("too many"), "the suppression is said out loud: " + survey.notice());
    }

    /** Nothing is reported outside the window it was asked about: a landmark is found where it is looked for. */
    @Test
    void landmarksStayInsideTheWindow() {
        Expr f = new Expr.Sub(new Expr.Power(X, constant(2)), constant(4));
        List<Landmark> found = Landmarks.find(f, "x", 0, 10);
        for (Landmark l : found) {
            assertTrue(l.x() >= 0 && l.x() <= 10, "found at " + l.x() + ", outside [0, 10]");
        }
        assertEquals(List.of(2.0), xs(found, Landmark.Kind.ROOT),
                "the root at 2 is in this window; the one at −2 is not");
    }

    /** A curve with no derivative reports what can still be proven without one, and nothing that cannot. */
    @Test
    void anOpaqueNodeCostsTheExtremaAndNothingElse() {
        Expr opaque = cell -> cell.of("x");                      // an identity nobody can differentiate
        Expr f = new Expr.Sub(opaque, constant(4));
        List<Landmark> found = Landmarks.find(f, "x", -10, 10);
        assertEquals(List.of(4.0), xs(found, Landmark.Kind.ROOT), "a root is found by sampling, not by calculus");
        assertTrue(only(found, Landmark.Kind.MINIMUM).isEmpty(), "but an extremum is not");
        assertTrue(only(found, Landmark.Kind.MAXIMUM).isEmpty());
        assertTrue(only(found, Landmark.Kind.INFLECTION).isEmpty());
    }

    // --- reading the answers ----------------------------------------------------------------------------

    private static List<Landmark> only(List<Landmark> found, Landmark.Kind kind) {
        return found.stream().filter(l -> l.kind() == kind).toList();
    }

    /** The x positions of one kind, rounded to six decimals so an exact answer reads as one. */
    private static List<Double> xs(List<Landmark> found, Landmark.Kind kind) {
        return only(found, kind).stream().map(l -> Math.round(l.x() * 1e6) / 1e6).toList();
    }

    private static double height(List<Landmark> found, Landmark.Kind kind) {
        return only(found, kind).get(0).y();
    }
}
