package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * An expression, and the two things it can do: enclose itself over a region, and differentiate itself.
 *
 * <p>Twelve nodes are built in — enough for the algebraic and elementary transcendental functions a plot is
 * usually asked for — but the interface is <b>open</b>, and that is the difference between this and the closed
 * union it was ported from. There, {@code AlgExpr} was a sealed set of twelve and every operation over it was a
 * twelve-case match, so a thirteenth node meant editing the evaluator. Here a node encloses itself, so an
 * application adds {@code Sinh} by writing {@code Sinh} and nothing else changes. The soundness contract travels
 * with the interface: whatever {@link #enclose} returns must contain every value the node takes on that region.
 *
 * <h2>The region is a cell, and one variable is the degenerate case</h2>
 * {@link Param} answers with the interval {@link Cell} binds its name to. A single-variable evaluation uses
 * {@link Cell#column}, which binds every name to the same column — so {@link #enclose(Interval)} means exactly
 * what it always did, and {@code y = f(x)} does not pay for the generality that lets {@code z = f(x, y)} exist.
 *
 * <h2>The dependency problem, stated plainly</h2>
 * {@code x − x} over {@code [0, 1]} encloses to {@code [-1, 1]}, not {@code [0, 0]}: the two occurrences of
 * {@code x} are treated as independent, because interval arithmetic has no way to know they move together.
 * Everything that follows from that is a false <em>positive</em> — an enclosure fatter than the truth, a pole
 * reported where {@code x/x} has none — and never a false negative. A plotter can live with over-warning; it
 * cannot live with drawing a confident line through a singularity. (Affine arithmetic tracks exactly these
 * correlations and is the known upgrade, entirely behind {@link Enclosure}.)
 *
 * <h2>A node differentiates itself too</h2>
 * {@link #derivative} is shaped the same way and for the same reason: extrema are roots of {@code f′} and
 * inflections are roots of {@code f″}, and {@link Landmarks} needs both without anyone writing a switch over
 * twelve cases. A node that cannot differentiate itself answers empty, and empty propagates through every
 * composite containing it, so the honest consequence is stated rather than guessed at: an expression carrying
 * such a node reports no extrema. All twelve built-in nodes can.
 */
public interface Expr {

    /** The enclosure of this expression over {@code cell} — a value that contains everything it takes there. */
    Enclosure enclose(Cell cell);

    /**
     * The enclosure over a single column, with every parameter bound to it. The one-variable case, and the
     * shape every {@code y = f(x)} consumer uses.
     */
    default Enclosure enclose(Interval column) {
        return enclose(Cell.column(column));
    }

    /**
     * The derivative of this expression with respect to {@code parameter}, or empty if this node does not know
     * how to differentiate itself. A composite whose child answers empty answers empty.
     *
     * <p>The result is constant-folded on the way out — {@code 1·u} is {@code u} and {@code 0 + v} is
     * {@code v} — so the derivative of {@code x³ − 3x} is {@code 3x² − 3} rather than a tree six times that
     * size. That is not tidiness: the landmark finder bisects on {@code f′} some tens of times per candidate,
     * and every folded node is arithmetic not done on each of them.
     */
    default Optional<Expr> derivative(String parameter) {
        return Optional.empty();
    }

    /**
     * The constant this expression is, if it is one. {@link Power} asks about its exponent — a root needs an
     * exact rational to know whether it is even, and there is nothing sound to do with an exponent that varies
     * across the region — and the folding below asks about everything.
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
        public Enclosure enclose(Cell cell) {
            return new Interval(value, value);
        }

        @Override
        public Optional<Expr> derivative(String parameter) {
            return Optional.of(ZERO);
        }

        @Override
        public Optional<BigDecimal> constant() {
            return Optional.of(value);
        }
    }

    /** The variable. Its enclosure is whatever the cell binds its name to — the base case everything stands on. */
    record Param(String name) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return cell.of(name);
        }

        /** 1 with respect to itself, 0 with respect to any other axis — which is what makes these partials. */
        @Override
        public Optional<Expr> derivative(String parameter) {
            return Optional.of(name.equals(parameter) ? ONE : ZERO);
        }
    }

    record Add(Expr left, Expr right) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return left.enclose(cell).plus(right.enclose(cell));
        }

        @Override
        public Optional<Expr> derivative(String parameter) {
            return both(left, right, parameter, Expr::sum);
        }
    }

    record Sub(Expr left, Expr right) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return left.enclose(cell).minus(right.enclose(cell));
        }

        @Override
        public Optional<Expr> derivative(String parameter) {
            return both(left, right, parameter, Expr::difference);
        }
    }

    record Mul(Expr left, Expr right) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return left.enclose(cell).times(right.enclose(cell));
        }

        /** {@code (uv)′ = u′v + uv′}. */
        @Override
        public Optional<Expr> derivative(String parameter) {
            return both(left, right, parameter,
                    (dl, dr) -> sum(product(dl, right), product(left, dr)));
        }
    }

    record Div(Expr left, Expr right) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return left.enclose(cell).dividedBy(right.enclose(cell));
        }

        /** {@code (u/v)′ = (u′v − uv′)/v²}, left as a quotient so the pole at {@code v = 0} survives into it. */
        @Override
        public Optional<Expr> derivative(String parameter) {
            return both(left, right, parameter,
                    (dl, dr) -> quotient(difference(product(dl, right), product(left, dr)),
                                         product(right, right)));
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
        public Enclosure enclose(Cell cell) {
            Enclosure enclosed = base.enclose(cell);
            return exponent.constant().map(enclosed::raisedTo).orElseGet(enclosed::spill);
        }

        /**
         * {@code (uⁿ)′ = n·uⁿ⁻¹·u′}, for a constant {@code n} only. A varying exponent is
         * {@code uᵛ = exp(v·ln u)} and needs a domain argument about {@code u} that this node cannot make, so
         * it declines rather than differentiating something it is not.
         */
        @Override
        public Optional<Expr> derivative(String parameter) {
            return exponent.constant().flatMap(n -> base.derivative(parameter).map(du ->
                    product(product(new Const(n), new Power(base, new Const(n.subtract(BigDecimal.ONE)))), du)));
        }
    }

    record Sin(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return arg.enclose(cell).sine();
        }

        @Override
        public Optional<Expr> derivative(String parameter) {
            return arg.derivative(parameter).map(du -> product(new Cos(arg), du));
        }
    }

    record Cos(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return arg.enclose(cell).cosine();
        }

        @Override
        public Optional<Expr> derivative(String parameter) {
            return arg.derivative(parameter).map(du -> difference(ZERO, product(new Sin(arg), du)));
        }
    }

    /** Tangent has poles of its own, and they surface the same way division's do — from the arithmetic. */
    record Tan(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return arg.enclose(cell).tangent();
        }

        /**
         * {@code u′/cos²u} rather than {@code u′·sec²u}: written as a quotient, the derivative's own poles fall
         * out of the same divisor-straddles-zero test everything else's do, with no new node for it.
         */
        @Override
        public Optional<Expr> derivative(String parameter) {
            return arg.derivative(parameter)
                    .map(du -> quotient(du, product(new Cos(arg), new Cos(arg))));
        }
    }

    record Exp(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return arg.enclose(cell).exponential();
        }

        @Override
        public Optional<Expr> derivative(String parameter) {
            return arg.derivative(parameter).map(du -> product(this, du));
        }
    }

    record Log(Expr arg) implements Expr {

        @Override
        public Enclosure enclose(Cell cell) {
            return arg.enclose(cell).logarithm();
        }

        @Override
        public Optional<Expr> derivative(String parameter) {
            return arg.derivative(parameter).map(du -> quotient(du, arg));
        }
    }

    // --- the folding the derivative rules are written against ---------------------------------------------

    /** {@code 0}, and the identity every fold below tests against. */
    Expr ZERO = new Const(BigDecimal.ZERO);

    /** {@code 1}. */
    Expr ONE = new Const(BigDecimal.ONE);

    /** Both children's derivatives, or empty if either declines: how a composite propagates "I cannot". */
    private static Optional<Expr> both(Expr left, Expr right, String parameter,
                                       java.util.function.BinaryOperator<Expr> join) {
        return left.derivative(parameter)
                .flatMap(dl -> right.derivative(parameter).map(dr -> join.apply(dl, dr)));
    }

    private static Expr sum(Expr left, Expr right) {
        if (isZero(left)) {
            return right;
        }
        if (isZero(right)) {
            return left;
        }
        return folded(left, right, BigDecimal::add).orElseGet(() -> new Add(left, right));
    }

    private static Expr difference(Expr left, Expr right) {
        if (isZero(right)) {
            return left;
        }
        return folded(left, right, BigDecimal::subtract).orElseGet(() -> new Sub(left, right));
    }

    private static Expr product(Expr left, Expr right) {
        if (isZero(left) || isZero(right)) {
            return ZERO;
        }
        if (isOne(left)) {
            return right;
        }
        if (isOne(right)) {
            return left;
        }
        return folded(left, right, BigDecimal::multiply).orElseGet(() -> new Mul(left, right));
    }

    /**
     * A quotient folds only in the numerator. A constant divisor is deliberately <b>not</b> divided through:
     * {@code BigDecimal} cannot hold a third, and the arithmetic below is built to divide with its own outward
     * rounding — folding here would round to nearest first and hand the sound layer a number that is already
     * slightly the wrong one.
     */
    private static Expr quotient(Expr left, Expr right) {
        if (isZero(left)) {
            return ZERO;
        }
        if (isOne(right)) {
            return left;
        }
        return new Div(left, right);
    }

    private static Optional<Expr> folded(Expr left, Expr right,
                                         java.util.function.BinaryOperator<BigDecimal> op) {
        return left.constant().flatMap(a -> right.constant().map(b -> new Const(op.apply(a, b))));
    }

    private static boolean isZero(Expr e) {
        return e.constant().filter(v -> v.signum() == 0).isPresent();
    }

    private static boolean isOne(Expr e) {
        return e.constant().filter(v -> v.compareTo(BigDecimal.ONE) == 0).isPresent();
    }
}
