package dev.vexelray.gui.plot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one property the whole module exists to have: <b>an enclosure over a column contains the value at every
 * point of that column.</b> Everything else — how tight it is, how fast it is, how it is dispatched — is
 * negotiable. This is not.
 *
 * <p>The check needs no separate point evaluator, and deliberately so. A degenerate column {@code [x, x]} <em>is</em>
 * a point evaluation, so each test walks the column, encloses each point through the same code path, and asserts
 * containment. A second implementation to compare against would only be a second thing to be wrong.
 *
 * <p>Points that are off-domain or on a pole are skipped rather than asserted about — there is nothing to
 * contain there — but a column has to produce a majority of real samples to count, or a broken evaluator that
 * answered Undefined everywhere would pass every test here without enclosing anything.
 */
class SoundnessTest {

    /** Enough to land inside every feature these columns contain; small enough to stay instant. */
    private static final int SAMPLES = 401;

    private static final Expr X = new Expr.Param("x");

    @Test
    void aQuotientAwayFromItsPole() {
        enclosesEveryPoint(new Expr.Div(new Expr.Const(1.0), X), 1, 2);
        enclosesEveryPoint(new Expr.Div(new Expr.Const(1.0), X), -3, -1);
    }

    @Test
    void aProductAcrossZero() {
        enclosesEveryPoint(new Expr.Mul(X, X), -1, 2);
    }

    /** The case endpoints alone would get wrong: a column holding a peak, and one holding a whole period. */
    @Test
    void trigonometryOverColumnsHoldingExtrema() {
        enclosesEveryPoint(new Expr.Sin(X), 1, 2);
        enclosesEveryPoint(new Expr.Sin(X), 0, 10);
        enclosesEveryPoint(new Expr.Cos(X), 3, 4);
    }

    @Test
    void theDoubleBackedTranscendentals() {
        enclosesEveryPoint(new Expr.Exp(X), -2, 2);
        enclosesEveryPoint(new Expr.Log(X), 1, 5);
        enclosesEveryPoint(new Expr.Tan(X), 0, 1);
    }

    @Test
    void oddAndEvenPowers() {
        enclosesEveryPoint(new Expr.Power(X, new Expr.Const(3.0)), -2, 1);
        enclosesEveryPoint(new Expr.Power(X, new Expr.Const(4.0)), -1.5, 1.5);
    }

    /** A partial domain: the samples below zero have no value, and the enclosure must still cover the rest. */
    @Test
    void aRootOverAColumnThatCrossesItsDomainEdge() {
        enclosesEveryPoint(new Expr.Power(X, new Expr.Const(0.5)), -1, 4);
    }

    /** Composition, where a sound piece can still be combined unsoundly if the propagation is wrong. */
    @Test
    void anExpressionBuiltFromSeveralOfThem() {
        Expr f = new Expr.Add(new Expr.Mul(X, X), new Expr.Sin(X));
        enclosesEveryPoint(f, -2, 2);

        // x² is written as a power rather than as x·x on purpose: a product of two intervals loses the
        // correlation between them and encloses [-9, 9] over this column, which straddles zero and would make
        // the divisor a pole it does not have. Sound, and a good reminder of what the fattening costs.
        Expr g = new Expr.Div(new Expr.Sin(X),
                new Expr.Add(new Expr.Power(X, new Expr.Const(2.0)), new Expr.Const(1.0)));
        enclosesEveryPoint(g, -3, 3);
    }

    /**
     * Walk the column, enclose each point through the same evaluator, and require the column's enclosure to
     * contain each one.
     */
    private static void enclosesEveryPoint(Expr f, double lo, double hi) {
        String where = f + " over [" + lo + ", " + hi + "]";
        Interval column = assertInstanceOf(Interval.class, f.enclose(Interval.of(lo, hi)),
                where + ": expected a bounded enclosure");
        int real = 0;
        for (int i = 0; i <= SAMPLES; i++) {
            double x = lo + (hi - lo) * i / SAMPLES;
            if (!(f.enclose(Interval.at(x)) instanceof Interval point)) {
                continue;                                   // off-domain or on a pole: nothing to contain
            }
            real++;
            assertTrue(column.contains(point.lo()) && column.contains(point.hi()),
                    where + ": the value at x = " + x + " is " + point + ", outside " + column);
        }
        assertTrue(real > SAMPLES / 2,
                where + ": only " + real + " of " + SAMPLES + " samples had a real value — the enclosure would "
                        + "pass this test without enclosing anything");
    }
}
