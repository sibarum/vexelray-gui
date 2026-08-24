package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.util.function.BiFunction;

/**
 * What an expression is worth over a whole <b>column</b> of x, rather than at a point: a value that
 * <b>provably contains</b> every value the expression takes anywhere on that column.
 *
 * <p>This is the whole reason the module exists. Sampling a curve at points and joining them cannot be made
 * honest — {@code tan(1/x)} has infinitely many asymptotes in any neighbourhood of the origin, so between two
 * adjacent samples there can be thousands of poles and no sample count finds them. Evaluating over the column
 * instead gives an answer that is allowed to be vague but is never wrong, and a renderer built on it can say
 * "there is detail here finer than a pixel" rather than drawing a confident line through a singularity.
 *
 * <h2>Three answers, and each one is a type</h2>
 * <ul>
 *   <li>{@link Interval} — a bounded {@code [lo, hi]} that contains the true range;
 *   <li>{@link Unbounded} — the column runs off to ±∞ somewhere inside it: a pole, or detail too fine to
 *       resolve. Found by the <em>arithmetic</em> (a divisor range containing zero), never by root-solving;
 *   <li>{@link Undefined} — no real value anywhere on the column. A true gap, not a pole.
 * </ul>
 *
 * <h2>Soundness is the contract; tightness is not</h2>
 * Every operation returns an over-approximation. Inexact endpoints round <b>outward</b> (lo down, hi up) — the
 * one place this module deliberately widens instead of rounding to nearest — so an enclosure is always a true
 * superset. It may be fatter than it needs to be; it may never miss a value the expression actually takes.
 *
 * <p>The bias that follows is deliberate: <b>no false negatives, at the cost of false positives.</b> A real
 * asymptote is always caught, because soundness forces the divisor's enclosure to contain the zero that is
 * really there. The converse leaks — {@code x/x} flags a pole it does not have, because interval arithmetic
 * drops the correlation between the two occurrences of {@code x}. For a plotter that is the right way round:
 * never draw through a singularity, and at worst over-warn.
 *
 * <h2>Why this is an open interface and not a closed union</h2>
 * The three cases carry the operations, so the propagation law is written once as dispatch rather than three
 * times as a switch: {@link Undefined} absorbs anything, {@link Unbounded} spills through anything bounded, and
 * only when both operands are bounded does the arithmetic run. The law lives in the two-line
 * {@link #combine}/{@link #againstBounded} pair below, and adding a fourth answer (an affine form, say, which is
 * the known upgrade for interval arithmetic's over-fattening) means writing a type, not editing a switch.
 */
public interface Enclosure {

    /** No real value anywhere on the column — a true gap in the domain. */
    Enclosure UNDEFINED = new Undefined();

    /** The column spills to ±∞ somewhere inside it — a pole, or detail finer than the column. */
    Enclosure UNBOUNDED = new Unbounded();

    /**
     * Combine this enclosure, as the <b>left</b> operand, with {@code right}. Undefined absorbs; then Unbounded
     * spills; only if both are bounded does {@code whenBothBounded} run. Every binary operation goes through
     * here, which is what keeps the propagation law in one place.
     */
    Enclosure combine(Enclosure right, BiFunction<Interval, Interval, Enclosure> whenBothBounded);

    /** The other half of the dispatch: {@code this} is the <b>right</b> operand and the left one is bounded. */
    Enclosure againstBounded(Interval left, BiFunction<Interval, Interval, Enclosure> whenBothBounded);

    /**
     * "This column cannot be bounded here." Undefined stays undefined — a gap does not become a pole by being
     * divided into — and everything else becomes {@link #UNBOUNDED}. The one-line answer to every case where
     * the arithmetic gives up soundly.
     */
    Enclosure spill();

    /**
     * Express this enclosure as one call on {@code sink} — the closed alphabet every answer reduces to.
     *
     * <p>This is the seam a <em>consumer</em> needs, and the one it may not get by switching. Classification
     * ({@code enclosure, frame → span}) has to tell the three answers apart, but writing that as a three-case
     * {@code instanceof} chain would put the arithmetic's vocabulary back into a switch, and adding a fourth
     * answer — an affine form, the known upgrade — would then break every consumer instead of none. Inverting it
     * keeps each half open in the direction it has to be: the set of <b>operations</b> is closed, here, in
     * {@link Sink}; the set of <b>answers</b> stays open above it.
     *
     * <p>It is also what keeps this unit ignorant of plotting. A {@code spanIn(frame)} method would have been
     * shorter and would have made the interval algebra depend on the framing policy. The sink carries no
     * plotting vocabulary at all — only the arithmetic's own.
     */
    void emitTo(Sink sink);

    /**
     * What a consumer of enclosures implements: the three answers, as three calls. Write all three and you can
     * classify any enclosure, including one written after you.
     */
    interface Sink {

        /** A bounded answer: everything the expression takes on the column lies within {@code [lo, hi]}. */
        void bounded(BigDecimal lo, BigDecimal hi);

        /** The column spills to ±∞ somewhere inside it — a pole, or detail finer than the column. */
        void unbounded();

        /** No real value anywhere on the column — a true gap, not a pole. */
        void undefined();
    }

    /** Raised to a constant exponent: integer powers exactly, rational ones outward-rounded. */
    Enclosure raisedTo(BigDecimal exponent);

    Enclosure sine();

    Enclosure cosine();

    Enclosure tangent();

    Enclosure exponential();

    Enclosure logarithm();

    default Enclosure plus(Enclosure right) {
        return combine(right, Interval::sum);
    }

    default Enclosure minus(Enclosure right) {
        return combine(right, Interval::difference);
    }

    default Enclosure times(Enclosure right) {
        return combine(right, Interval::product);
    }

    default Enclosure dividedBy(Enclosure right) {
        return combine(right, Interval::quotient);
    }
}
