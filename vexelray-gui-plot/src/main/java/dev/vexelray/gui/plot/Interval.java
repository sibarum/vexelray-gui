package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.function.BiFunction;

/**
 * A bounded enclosure {@code [lo, hi]} — and, read the other way, the <b>column of x</b> an expression is
 * evaluated over. The two are the same type on purpose: {@code Param} answers a column with the column itself,
 * which is the base case the whole evaluation stands on, and a degenerate column {@code [x, x]} is exactly a
 * point evaluation. There is no separate point evaluator to keep in agreement with this one.
 *
 * <h2>Where the widening happens</h2>
 * Addition, subtraction, multiplication and integer powers are <b>exact</b>: {@code BigDecimal} carries them
 * without rounding, so {@code x·x} over {@code [2, 3]} is precisely {@code [4, 9]} with no fattening at all.
 * Division rounds its endpoints outward — floor for {@code lo}, ceiling for {@code hi} — so {@code 1/3} becomes
 * a hair-wide interval containing the true value rather than a truncated point that does not.
 *
 * <p>The transcendentals are evaluated in {@code double} and then widened by {@link #MARGIN_RELATIVE} /
 * {@link #MARGIN_ABSOLUTE}, a margin far larger than double's own error. It costs nothing visible — 1e-12 is
 * invisible at any pixel scale — and it buys the guarantee outright instead of reasoning about the last bit.
 *
 * <h2>Periodic extrema</h2>
 * {@code sin} and {@code cos} cannot be enclosed from their endpoints: a column can straddle a peak that both
 * ends miss. {@link #trigonometric} tests for an enclosed extremum directly — "does the column contain a point
 * congruent to π/2 mod 2π?" — which is exact, cheap, and the reason {@code sin} over a wide column saturates to
 * {@code [-1, 1]} rather than reporting whatever its two ends happened to be.
 */
public record Interval(BigDecimal lo, BigDecimal hi) implements Enclosure {

    /** {@code [-1, 1]} — the range of sine and cosine, whatever the argument. */
    public static final Interval UNIT = new Interval(BigDecimal.ONE.negate(), BigDecimal.ONE);

    /** {@code [1, 1]} — the identity for powers, and the numerator of a reciprocal. */
    private static final Interval ONE = new Interval(BigDecimal.ONE, BigDecimal.ONE);

    /** Quotient endpoints round outward: toward −∞ for {@code lo}, toward +∞ for {@code hi}. */
    private static final MathContext DIVIDE_DOWN = new MathContext(34, RoundingMode.FLOOR);
    private static final MathContext DIVIDE_UP = new MathContext(34, RoundingMode.CEILING);

    /**
     * The margin the double-backed transcendentals are widened by. Their true error is around 1e-16 relative;
     * this is four orders of magnitude more generous, which is the point — the enclosure stays a true superset
     * without anyone having to be right about floating-point error.
     */
    private static final BigDecimal MARGIN_RELATIVE = new BigDecimal("1e-12");
    private static final BigDecimal MARGIN_ABSOLUTE = new BigDecimal("1e-12");

    /** Beyond this an integer power is not evaluated at all — {@code BigDecimal.pow} would not take it. */
    private static final int LARGEST_POWER = 1_000_000;

    public Interval {
        if (lo == null || hi == null) {
            throw new IllegalArgumentException("an interval needs both endpoints");
        }
        if (lo.compareTo(hi) > 0) {
            // Not a defensive nicety: an inverted interval encloses nothing, so every claim made about it is
            // false. Failing here means a rounding direction is wrong somewhere, which is worth stopping for.
            throw new IllegalArgumentException("lo > hi: [" + lo + ", " + hi + "]");
        }
    }

    /** The column {@code [lo, hi]}. */
    public static Interval of(double lo, double hi) {
        return new Interval(BigDecimal.valueOf(lo), BigDecimal.valueOf(hi));
    }

    /** The degenerate column {@code [x, x]} — evaluating over it is evaluating at a point. */
    public static Interval at(double x) {
        BigDecimal v = BigDecimal.valueOf(x);
        return new Interval(v, v);
    }

    /** Whether the interval contains zero — the test that finds a pole, with no root-solving anywhere. */
    public boolean straddlesZero() {
        return lo.signum() <= 0 && hi.signum() >= 0;
    }

    public boolean contains(BigDecimal value) {
        return lo.compareTo(value) <= 0 && hi.compareTo(value) >= 0;
    }

    public BigDecimal width() {
        return hi.subtract(lo);
    }

    // --- the propagation law's bounded case -------------------------------------------------------------

    @Override
    public Enclosure combine(Enclosure right, BiFunction<Interval, Interval, Enclosure> whenBothBounded) {
        return right.againstBounded(this, whenBothBounded);
    }

    @Override
    public Enclosure againstBounded(Interval left, BiFunction<Interval, Interval, Enclosure> whenBothBounded) {
        return whenBothBounded.apply(left, this);
    }

    @Override
    public Enclosure spill() {
        return UNBOUNDED;
    }

    // --- arithmetic (exact) ------------------------------------------------------------------------------

    /** Endpoint-wise, and exact: the extremes of a sum are the sums of the extremes. */
    public Interval sum(Interval other) {
        return new Interval(lo.add(other.lo), hi.add(other.hi));
    }

    /** Note the crossed endpoints: the smallest difference is the smallest minus the <em>largest</em>. */
    public Interval difference(Interval other) {
        return new Interval(lo.subtract(other.hi), hi.subtract(other.lo));
    }

    /** Signs make a product non-monotone, so take the extremes of all four endpoint products. */
    public Interval product(Interval other) {
        BigDecimal a = lo.multiply(other.lo);
        BigDecimal b = lo.multiply(other.hi);
        BigDecimal c = hi.multiply(other.lo);
        BigDecimal d = hi.multiply(other.hi);
        return new Interval(minOf(minOf(a, b), minOf(c, d)), maxOf(maxOf(a, b), maxOf(c, d)));
    }

    /**
     * <b>The pole, and the whole of how poles are found.</b> If the divisor's range contains zero then the
     * quotient runs off to infinity somewhere on the column — not "probably", not "worth checking", but as a
     * consequence of the divisor provably taking every value its enclosure covers. No solver is involved, and
     * nothing has to know that {@code x² + 3x − 4} has a root at 1.
     */
    public Enclosure quotient(Interval other) {
        if (other.straddlesZero()) {
            return UNBOUNDED;
        }
        BigDecimal low = null;
        BigDecimal high = null;
        for (BigDecimal numerator : new BigDecimal[]{lo, hi}) {
            for (BigDecimal denominator : new BigDecimal[]{other.lo, other.hi}) {
                BigDecimal down = numerator.divide(denominator, DIVIDE_DOWN);
                BigDecimal up = numerator.divide(denominator, DIVIDE_UP);
                low = low == null ? down : minOf(low, down);
                high = high == null ? up : maxOf(high, up);
            }
        }
        return new Interval(low, high);
    }

    // --- powers ------------------------------------------------------------------------------------------

    @Override
    public Enclosure raisedTo(BigDecimal exponent) {
        BigDecimal stripped = exponent.stripTrailingZeros();
        if (stripped.scale() > 0) {
            return rationalPower(exponent);
        }
        int n;
        try {
            n = stripped.intValueExact();
        } catch (ArithmeticException tooLarge) {
            return UNBOUNDED;
        }
        return Math.abs(n) > LARGEST_POWER ? UNBOUNDED : integerPower(n);
    }

    /** Exact, and shaped by parity: an even power is a U with its floor at zero, an odd one is monotone. */
    private Enclosure integerPower(int n) {
        if (n == 0) {
            return ONE;
        }
        if (n < 0) {
            return ONE.combine(integerPower(-n), Interval::quotient);   // 1/xⁿ — a pole at 0 falls out
        }
        if (n % 2 != 0) {
            return new Interval(lo.pow(n), hi.pow(n));
        }
        if (lo.signum() >= 0) {
            return new Interval(lo.pow(n), hi.pow(n));
        }
        if (hi.signum() <= 0) {
            return new Interval(hi.pow(n), lo.pow(n));
        }
        return new Interval(BigDecimal.ZERO, maxOf(lo.abs().pow(n), hi.abs().pow(n)));
    }

    /**
     * A rational exponent is a root, so the question is the domain. An even denominator needs a non-negative
     * base: wholly negative is {@link Enclosure#UNDEFINED}, but a column that merely <em>crosses</em> zero has
     * its negative part clamped away and stays an interval of the part that is defined — a partial domain must
     * never punch a false gap into a curve that genuinely exists on the other half.
     */
    private Enclosure rationalPower(BigDecimal exponent) {
        ExactPower.Rational power = ExactPower.Rational.of(exponent);
        BigDecimal low = lo;
        BigDecimal high = hi;
        if (!power.den().testBit(0)) {                 // even denominator — an even root
            if (high.signum() < 0) {
                return UNDEFINED;                      // no real value anywhere on the column
            }
            if (low.signum() < 0) {
                low = BigDecimal.ZERO;                 // enclose the defined part
            }
        }
        try {
            if (exponent.signum() >= 0) {
                return spanning(ExactPower.pow(low, power), ExactPower.pow(high, power));
            }
            if (low.signum() == 0 || straddlesZero()) {
                return UNBOUNDED;                      // a negative power of something reaching zero
            }
            return spanning(ExactPower.pow(high, power), ExactPower.pow(low, power));
        } catch (ExactPower.Unevaluable edge) {
            return UNBOUNDED;                          // a domain edge we did not anticipate: paint, never gap
        }
    }

    /** The two computed endpoints, rounded outward and ordered — the caller need not know which is smaller. */
    private static Interval spanning(BigDecimal first, BigDecimal second) {
        BigDecimal a = outward(first, false);
        BigDecimal b = outward(second, true);
        return new Interval(minOf(a, b), maxOf(a, b));
    }

    // --- transcendentals ---------------------------------------------------------------------------------

    @Override
    public Enclosure sine() {
        return trigonometric(true);
    }

    @Override
    public Enclosure cosine() {
        return trigonometric(false);
    }

    @Override
    public Enclosure tangent() {
        double low = lo.doubleValue();
        double high = hi.doubleValue();
        if (!Double.isFinite(low) || !Double.isFinite(high)) {
            return UNBOUNDED;
        }
        if (high - low >= Math.PI) {
            return UNBOUNDED;                                              // a whole period: every value
        }
        if (containsCongruent(low, high, Math.PI / 2, Math.PI)) {
            return UNBOUNDED;                                              // a pole inside the column
        }
        double atLow = Math.tan(low);                                      // one branch — increasing
        double atHigh = Math.tan(high);
        return spanning(BigDecimal.valueOf(Math.min(atLow, atHigh)),
                        BigDecimal.valueOf(Math.max(atLow, atHigh)));
    }

    @Override
    public Enclosure exponential() {
        double high = Math.exp(hi.doubleValue());
        if (!Double.isFinite(high)) {
            return UNBOUNDED;                                              // overflowed: unbounded above
        }
        double low = Math.exp(lo.doubleValue());
        return spanning(BigDecimal.valueOf(low), BigDecimal.valueOf(high));
    }

    @Override
    public Enclosure logarithm() {
        if (hi.signum() <= 0) {
            return UNDEFINED;                                              // wholly non-positive
        }
        if (lo.signum() <= 0) {
            return UNBOUNDED;                                              // reaches zero, where log → −∞
        }
        double low = Math.log(lo.doubleValue());
        double high = Math.log(hi.doubleValue());
        if (!Double.isFinite(low) || !Double.isFinite(high)) {
            return UNBOUNDED;
        }
        return spanning(BigDecimal.valueOf(low), BigDecimal.valueOf(high));
    }

    /**
     * Sine or cosine over the column. The endpoints give a starting range; then each extremum the column
     * actually encloses pins that side of it to ±1. A column wider than a full period encloses both, which is
     * why {@code sin} over {@code [0, 10]} is exactly {@code [-1, 1]} and not the two values at its ends.
     */
    private Enclosure trigonometric(boolean sine) {
        double low = lo.doubleValue();
        double high = hi.doubleValue();
        if (!Double.isFinite(low) || !Double.isFinite(high) || high - low >= 2 * Math.PI) {
            return UNIT;
        }
        double atLow = sine ? Math.sin(low) : Math.cos(low);
        double atHigh = sine ? Math.sin(high) : Math.cos(high);
        double least = Math.min(atLow, atHigh);
        double most = Math.max(atLow, atHigh);
        double peak = sine ? Math.PI / 2 : 0.0;                            // where the function attains +1
        double trough = sine ? -Math.PI / 2 : Math.PI;                     // and where it attains −1
        if (containsCongruent(low, high, peak, 2 * Math.PI)) {
            most = 1.0;
        }
        if (containsCongruent(low, high, trough, 2 * Math.PI)) {
            least = -1.0;
        }
        return new Interval(maxOf(UNIT.lo, outward(BigDecimal.valueOf(least), false)),
                            minOf(UNIT.hi, outward(BigDecimal.valueOf(most), true)));
    }

    /** Does {@code [low, high]} contain some {@code base + k·period}? The exact test, in two divisions. */
    private static boolean containsCongruent(double low, double high, double base, double period) {
        return Math.ceil((low - base) / period) <= Math.floor((high - base) / period);
    }

    // --- outward rounding --------------------------------------------------------------------------------

    /**
     * Widen {@code v} away from the truth by the transcendental margin — down for a {@code lo}, up for a
     * {@code hi}. The only place this module rounds on purpose.
     */
    private static BigDecimal outward(BigDecimal v, boolean up) {
        BigDecimal margin = v.abs().multiply(MARGIN_RELATIVE).add(MARGIN_ABSOLUTE);
        return up ? v.add(margin) : v.subtract(margin);
    }

    private static BigDecimal minOf(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static BigDecimal maxOf(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
