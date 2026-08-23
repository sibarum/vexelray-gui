package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * An expression in one variable, and the one thing it can do: enclose itself over a column.
 *
 * <p>Twelve nodes are built in — enough for the algebraic and elementary transcendental functions a plot is
 * usually asked for — but the interface is <b>open</b>, and that is the difference between this and the closed
 * union it was ported from. There, {@code AlgExpr} was a sealed set of twelve and every operation over it was a
 * twelve-case match, so a thirteenth node meant editing the evaluator. Here a node encloses itself, so an
 * application adds {@code Sinh} by writing {@code Sinh} and nothing else changes. The soundness contract travels
 * with the interface: whatever {@link #enclose} returns must contain every value the node takes on that column.
 *
 * <h2>One variable, and the column is it</h2>
 * {@link Param} answers with the column itself, whatever its name. The name is carried so an expression can be
 * printed and round-tripped, but a single-column evaluation binds every parameter to the same column — this is
 * the substrate for graphing {@code y = f(x)}, not a multivariate evaluator.
 *
 * <h2>The dependency problem, stated plainly</h2>
 * {@code x − x} over {@code [0, 1]} encloses to {@code [-1, 1]}, not {@code [0, 0]}: the two occurrences of
 * {@code x} are treated as independent, because interval arithmetic has no way to know they move together.
 * Everything that follows from that is a false <em>positive</em> — an enclosure fatter than the truth, a pole
 * reported where {@code x/x} has none — and never a false negative. A plotter can live with over-warning; it
 * cannot live with drawing a confident line through a singularity. (Affine arithmetic tracks exactly these
 * correlations and is the known upgrade, entirely behind {@link Enclosure}.)
 */
public interface Expr {

    /** The enclosure of this expression over {@code column} — a value that contains everything it takes there. */
    Enclosure enclose(Interval column);

    /**
     * The constant this expression is, if it is one. Only {@link Power} asks, and only about its exponent: a
     * root needs an exact rational to know whether it is even, and there is nothing sound to do with an
     * exponent that varies across the column.
     */
    default Optional<BigDecimal> constant() {
        return Optional.empty();
    }

    /** A literal. */
    record Const(BigDecimal value) implements Expr {

        public Const(double value) {
            this(BigDecimal.valueOf(value));
        }

        @Override
        public Enclosure enclose(Interval column) {
            return new Interval(value, value);
        }

        @Override
        public Optional<BigDecimal> constant() {
            return Optional.of(value);
        }
    }

    /** The variable. Its enclosure over a column is the column — the base case everything else stands on. */
    record Param(String name) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return column;
        }
    }

    record Add(Expr left, Expr right) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return left.enclose(column).plus(right.enclose(column));
        }
    }

    record Sub(Expr left, Expr right) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return left.enclose(column).minus(right.enclose(column));
        }
    }

    record Mul(Expr left, Expr right) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return left.enclose(column).times(right.enclose(column));
        }
    }

    record Div(Expr left, Expr right) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return left.enclose(column).dividedBy(right.enclose(column));
        }
    }

    /**
     * A power with a constant exponent is a power; a power with a varying one is not something this arithmetic
     * can bound, so it spills to {@code Unbounded} — sound, and rare enough that paying a painted column for it
     * is the right trade. Note that spilling is {@link Enclosure#spill()} and not a bare {@code Unbounded}: an
     * undefined base stays undefined even when the exponent is unusable.
     */
    record Power(Expr base, Expr exponent) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            Enclosure enclosed = base.enclose(column);
            return exponent.constant().map(enclosed::raisedTo).orElseGet(enclosed::spill);
        }
    }

    record Sin(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return arg.enclose(column).sine();
        }
    }

    record Cos(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return arg.enclose(column).cosine();
        }
    }

    /** Tangent has poles of its own, and they surface the same way division's do — from the arithmetic. */
    record Tan(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return arg.enclose(column).tangent();
        }
    }

    record Exp(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return arg.enclose(column).exponential();
        }
    }

    record Log(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Interval column) {
            return arg.enclose(column).logarithm();
        }
    }
}
