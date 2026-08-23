package dev.vexelray.gui.plot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enclosure algebra's behaviour, case by case. Each test is one claim about what evaluating over a column
 * must answer, and between them they pin the three outcomes, the propagation order, where widening happens and
 * where it must not.
 *
 * <p>These are the cases the substrate was originally proven with, restated against this module's types. They
 * are worth reading as documentation of the contract rather than as regression tests: the pole in
 * {@link #aPoleIsUnbounded} is found by division, the gap in {@link #whollyOutsideTheDomainIsUndefined} is a
 * different answer from the pole and must stay one, and {@link #aSquareRootAcrossZeroEnclosesTheDefinedPart} is
 * the case where the obvious implementation is wrong.
 */
class EnclosureTest {

    private static final Expr X = new Expr.Param("x");

    /**
     * {@code 1/x} over a column containing zero. The divisor's enclosure contains zero, so the quotient runs
     * off to infinity somewhere inside — reported without solving anything, which is the whole technique.
     */
    @Test
    void aPoleIsUnbounded() {
        Expr f = new Expr.Div(new Expr.Const(1.0), X);
        assertEquals(Enclosure.UNBOUNDED, f.enclose(Interval.of(-1, 1)));
    }

    /**
     * {@code x·x} over {@code [2, 3]} is exactly {@code [4, 9]}. Nothing here is rounded, so nothing is
     * widened: a fat answer is permitted by the contract but is not the price of using it.
     */
    @Test
    void aTameColumnEnclosesExactly() {
        Interval enclosed = bounded(new Expr.Mul(X, X).enclose(Interval.of(2, 3)));
        assertEquals(0, enclosed.lo().compareTo(new BigDecimal("4")), "lo was " + enclosed.lo());
        assertEquals(0, enclosed.hi().compareTo(new BigDecimal("9")), "hi was " + enclosed.hi());
    }

    /** {@code log(x)} over {@code [-4, -1]}: no real value anywhere on the column. A gap, not a pole. */
    @Test
    void whollyOutsideTheDomainIsUndefined() {
        assertEquals(Enclosure.UNDEFINED, new Expr.Log(X).enclose(Interval.of(-4, -1)));
    }

    /**
     * {@code log(x)} over {@code [-1, 2]} is Unbounded and not Undefined, and the difference matters: the curve
     * genuinely exists on {@code (0, 2]}, it just runs off the bottom of any frame. Calling it a gap would
     * erase a curve that is there.
     */
    @Test
    void aDomainEdgeRunningToMinusInfinityIsUnbounded() {
        assertEquals(Enclosure.UNBOUNDED, new Expr.Log(X).enclose(Interval.of(-1, 2)));
    }

    /**
     * {@code sin(x)} over {@code [0, 10]} spans more than a period, so the answer is exactly {@code [-1, 1]} —
     * the endpoints are irrelevant, and an implementation that used them would report roughly
     * {@code [-0.54, 0]} and be wrong by the whole range.
     */
    @Test
    void sineOverAWideColumnSaturates() {
        Interval enclosed = bounded(new Expr.Sin(X).enclose(Interval.of(0, 10)));
        assertEquals(0, enclosed.lo().compareTo(BigDecimal.ONE.negate()), "lo was " + enclosed.lo());
        assertEquals(0, enclosed.hi().compareTo(BigDecimal.ONE), "hi was " + enclosed.hi());
    }

    /**
     * A column that encloses a peak but neither of whose ends is near it. {@code sin} over {@code [1, 2]}
     * straddles π/2, so the enclosure must reach 1 even though the endpoints are 0.841 and 0.909.
     */
    @Test
    void sineReachesAnExtremumItsEndpointsMiss() {
        Interval enclosed = bounded(new Expr.Sin(X).enclose(Interval.of(1, 2)));
        assertEquals(0, enclosed.hi().compareTo(BigDecimal.ONE), "hi was " + enclosed.hi());
        assertTrue(enclosed.lo().doubleValue() <= 0.8415, "lo must still cover sin(1): " + enclosed.lo());
    }

    /**
     * {@code 1/3} does not terminate, so the endpoints must round <em>outward</em> and the answer is a
     * hair-wide interval that contains it. A truncated point would be a value the function does not take.
     */
    @Test
    void anInexactQuotientRoundsOutward() {
        Expr f = new Expr.Div(new Expr.Const(1.0), X);
        Interval enclosed = bounded(f.enclose(Interval.at(3)));
        assertTrue(enclosed.lo().compareTo(enclosed.hi()) < 0, "expected a widened interval: " + enclosed);
        // Checked by multiplying back rather than against a double: the endpoints differ in the 34th digit, so
        // both round to the same double and the comparison that looks obvious cannot see the widening at all.
        BigDecimal three = new BigDecimal("3");
        assertTrue(enclosed.lo().multiply(three).compareTo(BigDecimal.ONE) < 0,
                "lo must be strictly below 1/3: " + enclosed.lo());
        assertTrue(enclosed.hi().multiply(three).compareTo(BigDecimal.ONE) > 0,
                "hi must be strictly above 1/3: " + enclosed.hi());
    }

    /** And the counterpart: {@code 1/2} terminates, so it stays a point. We widen only where we must. */
    @Test
    void anExactQuotientStaysAPoint() {
        Expr f = new Expr.Div(new Expr.Const(1.0), X);
        Interval enclosed = bounded(f.enclose(Interval.at(2)));
        assertEquals(0, enclosed.lo().compareTo(enclosed.hi()), "expected a point: " + enclosed);
        assertEquals(0, enclosed.lo().compareTo(new BigDecimal("0.5")), "value was " + enclosed.lo());
    }

    /**
     * {@code sqrt(x)} over {@code [-1, 4]} is <b>partially</b> out of domain, and the tempting answer —
     * Undefined — is the wrong one: it would punch a gap into a curve that genuinely exists on {@code [0, 4]}.
     * The enclosure covers the defined part instead. Over-painting a few pixels at the domain edge is the price,
     * and subdivision would sharpen it later.
     */
    @Test
    void aSquareRootAcrossZeroEnclosesTheDefinedPart() {
        Expr f = new Expr.Power(X, new Expr.Const(0.5));
        Interval enclosed = bounded(f.enclose(Interval.of(-1, 4)));
        assertTrue(enclosed.lo().doubleValue() <= 0.0, "lo must cover sqrt(0): " + enclosed.lo());
        assertTrue(enclosed.hi().doubleValue() >= 2.0, "hi must cover sqrt(4): " + enclosed.hi());
    }

    /**
     * The exact-power machinery earning its keep: {@code sqrt(4)} is 2, so the enclosure is 2 plus the fixed
     * transcendental margin and not a floating-point approximation of it. This is what lets a feature land on
     * an integer rather than near one.
     */
    @Test
    void anExactRootStaysTight() {
        Expr f = new Expr.Power(X, new Expr.Const(0.5));
        Interval enclosed = bounded(f.enclose(Interval.at(4)));
        assertTrue(enclosed.contains(new BigDecimal("2")), "must contain 2: " + enclosed);
        assertTrue(enclosed.width().doubleValue() < 1e-10, "must be tight around 2: " + enclosed);
    }

    /** An even power over a column crossing zero bottoms out at zero, not at the smaller endpoint's square. */
    @Test
    void anEvenPowerAcrossZeroFloorsAtZero() {
        Expr f = new Expr.Power(X, new Expr.Const(2.0));
        Interval enclosed = bounded(f.enclose(Interval.of(-1, 2)));
        assertEquals(0, enclosed.lo().compareTo(BigDecimal.ZERO), "lo was " + enclosed.lo());
        assertEquals(0, enclosed.hi().compareTo(new BigDecimal("4")), "hi was " + enclosed.hi());
    }

    /** A negative power is a reciprocal, so a column through zero is a pole — the same one division finds. */
    @Test
    void aNegativePowerThroughZeroIsAPole() {
        Expr f = new Expr.Power(X, new Expr.Const(-1.0));
        assertEquals(Enclosure.UNBOUNDED, f.enclose(Interval.of(-1, 1)));
    }

    /** Tangent's poles surface from the same arithmetic: a column containing π/2 is unbounded. */
    @Test
    void aTangentPoleIsUnbounded() {
        assertEquals(Enclosure.UNBOUNDED, new Expr.Tan(X).enclose(Interval.of(1, 2)));
    }

    /** On a single branch, tangent is monotone and bounded — the pole test must not fire everywhere. */
    @Test
    void tangentOnOneBranchIsBounded() {
        Interval enclosed = bounded(new Expr.Tan(X).enclose(Interval.of(0, 1)));
        assertTrue(enclosed.contains(new BigDecimal("1.5574")), "must contain tan(1): " + enclosed);
    }

    /** Overflow is not a bounded answer. {@code exp(2000)} is not a large number, it is off the top. */
    @Test
    void anOverflowingExponentialIsUnbounded() {
        Expr f = new Expr.Exp(new Expr.Mul(X, new Expr.Const(1000.0)));
        assertEquals(Enclosure.UNBOUNDED, f.enclose(Interval.of(1, 2)));
    }

    /**
     * An exponent that varies across the column cannot be bounded by this arithmetic, so it spills. Sound, and
     * rare enough that paying a painted column for it is the right trade.
     */
    @Test
    void aVaryingExponentSpills() {
        assertEquals(Enclosure.UNBOUNDED, new Expr.Power(X, X).enclose(Interval.of(1, 2)));
    }

    /** Undefined absorbs: a gap in one operand is a gap in the whole expression, whatever the other is. */
    @Test
    void aGapAbsorbsABoundedOperand() {
        Expr f = new Expr.Add(new Expr.Log(X), new Expr.Const(1.0));
        assertEquals(Enclosure.UNDEFINED, f.enclose(Interval.of(-4, -1)));
    }

    /**
     * And it absorbs a pole too, which is the propagation <em>order</em>: a column with no values does not
     * acquire infinite ones by being multiplied by something that has them. Here {@code log(x)} is a gap over
     * {@code [-4, -1]} while {@code 1/(x+2)} has a pole inside it.
     */
    @Test
    void aGapAbsorbsAPole() {
        Expr pole = new Expr.Div(new Expr.Const(1.0), new Expr.Add(X, new Expr.Const(2.0)));
        Expr f = new Expr.Mul(new Expr.Log(X), pole);
        assertEquals(Enclosure.UNDEFINED, f.enclose(Interval.of(-4, -1)));
    }

    /** A pole spills through everything bounded — nothing downstream can bound it again. */
    @Test
    void aPoleSpillsThroughABoundedOperand() {
        Expr f = new Expr.Add(new Expr.Div(new Expr.Const(1.0), X), new Expr.Const(1.0));
        assertEquals(Enclosure.UNBOUNDED, f.enclose(Interval.of(-1, 1)));
    }

    /**
     * The dependency problem, on the record: {@code x − x} is zero everywhere, and this arithmetic says
     * {@code [-1, 1]}, because the two occurrences of {@code x} are treated as moving independently. The point
     * of pinning it is the direction of the error — the enclosure is too <em>fat</em>, never too thin, so a
     * curve is over-warned about and never missed.
     */
    @Test
    void theDependencyProblemWidensRatherThanMisses() {
        Interval enclosed = bounded(new Expr.Sub(X, X).enclose(Interval.of(0, 1)));
        assertTrue(enclosed.contains(BigDecimal.ZERO), "the true value must still be inside: " + enclosed);
        assertTrue(enclosed.width().doubleValue() > 1.0, "and it is known to be fat here: " + enclosed);
    }

    /** A degenerate column is a point evaluation — there is no second evaluator to disagree with this one. */
    @Test
    void aDegenerateColumnIsAPointEvaluation() {
        Expr f = new Expr.Add(new Expr.Mul(X, X), new Expr.Const(1.0));
        Interval enclosed = bounded(f.enclose(Interval.at(3)));
        assertEquals(0, enclosed.lo().compareTo(enclosed.hi()), "expected a point: " + enclosed);
        assertEquals(0, enclosed.lo().compareTo(new BigDecimal("10")), "value was " + enclosed.lo());
    }

    private static Interval bounded(Enclosure enclosure) {
        return assertInstanceOf(Interval.class, enclosure, "expected a bounded enclosure");
    }
}
