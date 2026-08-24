package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node differentiates itself. The tests are written against the derivative's <em>values</em> rather than its
 * shape, because the shape is folded on the way out and pinning it would make every future fold a broken test —
 * except where the folding is the point, which is the last two.
 */
class DerivativeTest {

    private static final Expr X = new Expr.Param("x");

    private static double at(Expr expr, double x) {
        double[] read = new double[1];
        expr.enclose(Interval.at(x)).emitTo(new Enclosure.Sink() {
            @Override
            public void bounded(BigDecimal lo, BigDecimal hi) {
                read[0] = lo.add(hi).doubleValue() / 2;
            }

            @Override
            public void unbounded() {
                read[0] = Double.NaN;
            }

            @Override
            public void undefined() {
                read[0] = Double.NaN;
            }
        });
        return read[0];
    }

    private static Expr slope(Expr expr) {
        Optional<Expr> d = expr.derivative("x");
        assertTrue(d.isPresent(), "every built-in node differentiates itself");
        return d.get();
    }

    @Test
    void aPolynomialDifferentiatesByThePowerRule() {
        // x³ − 3x, whose slope is 3x² − 3: zero at ±1, and −3 at the origin.
        Expr f = new Expr.Sub(new Expr.Power(X, new Expr.Const(3.0)),
                              new Expr.Mul(new Expr.Const(3.0), X));
        Expr d = slope(f);
        assertEquals(-3, at(d, 0), 1e-9);
        assertEquals(0, at(d, 1), 1e-9);
        assertEquals(0, at(d, -1), 1e-9);
        assertEquals(9, at(d, 2), 1e-9);
    }

    @Test
    void theQuotientRuleKeepsThePole() {
        // 1/(x²−1), whose slope is −2x/(x²−1)². It is zero at the origin and blows up where the curve does.
        Expr f = new Expr.Div(new Expr.Const(1.0),
                              new Expr.Sub(new Expr.Power(X, new Expr.Const(2.0)), new Expr.Const(1.0)));
        Expr d = slope(f);
        assertEquals(0, at(d, 0), 1e-9);
        assertTrue(Double.isNaN(at(d, 1)),
                "the slope of a curve with a pole has the pole too -- it is left as a quotient so that it does");
    }

    @Test
    void theChainRuleReachesThroughTheTranscendentals() {
        Expr f = new Expr.Sin(new Expr.Mul(new Expr.Const(2.0), X));      // sin 2x → 2 cos 2x
        assertEquals(2, at(slope(f), 0), 1e-9);
        assertEquals(-2, at(slope(f), Math.PI / 2), 1e-9);

        Expr g = new Expr.Exp(new Expr.Mul(new Expr.Const(3.0), X));      // e^3x → 3 e^3x
        assertEquals(3, at(slope(g), 0), 1e-9);

        Expr h = new Expr.Log(new Expr.Power(X, new Expr.Const(2.0)));    // ln x² → 2x/x² = 2/x
        assertEquals(2, at(slope(h), 1), 1e-9);
        assertEquals(1, at(slope(h), 2), 1e-9);
    }

    /** The name is what makes these partials: {@code y} is a constant when differentiating by {@code x}. */
    @Test
    void aParameterIsOneOnlyWithRespectToItself() {
        Expr y = new Expr.Param("y");
        Expr surface = new Expr.Mul(X, y);
        assertEquals(y, surface.derivative("x").orElseThrow(), "∂(xy)/∂x is y");
        assertEquals(X, surface.derivative("y").orElseThrow(), "∂(xy)/∂y is x");
        assertEquals(Expr.ZERO, y.derivative("x").orElseThrow(), "the other axis is a constant here");
    }

    /** An unknown node stops the whole expression, and stopping is the documented answer rather than a guess. */
    @Test
    void anExpressionCarryingAnOpaqueNodeDeclines() {
        Expr opaque = column -> Interval.UNIT;                            // a node that only encloses itself
        assertFalse(opaque.derivative("x").isPresent());
        assertFalse(new Expr.Add(X, opaque).derivative("x").isPresent(),
                "a composite is only as differentiable as its children");
        assertFalse(new Expr.Sin(new Expr.Mul(X, opaque)).derivative("x").isPresent());
    }

    /**
     * The folding is not cosmetic: the landmark finder bisects on {@code f′} some tens of times per candidate,
     * so a derivative that carried {@code 1·x + 0} everywhere would pay for it on every halving.
     */
    @Test
    void theDerivativeIsFoldedRatherThanLiteral() {
        assertEquals(Expr.ONE, slope(X), "x′ is 1, not 1·1 + 0");
        assertEquals(Expr.ZERO, slope(new Expr.Const(7.0)));
        assertEquals(Expr.ZERO, slope(new Expr.Mul(new Expr.Const(7.0), new Expr.Const(2.0))),
                "a product of constants has a zero derivative, and says so in one node");
        assertEquals(new Expr.Const(5.0), slope(new Expr.Mul(new Expr.Const(5.0), X)),
                "(5x)′ folds to the constant 5");
    }
}
