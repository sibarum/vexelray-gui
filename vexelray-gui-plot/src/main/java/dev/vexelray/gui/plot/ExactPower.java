package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * {@code base} raised to an exact rational power, without a {@code double} anywhere in the path.
 *
 * <p>An enclosure is only as good as its endpoints, and an endpoint computed as {@code Math.pow(2.25, 0.5)} is a
 * number that is nearly 1.5 — nearly by an amount nobody has bounded. Doing the arithmetic exactly removes the
 * question: the exponent is kept as a reduced fraction, the integer power is exact, and the root is taken by
 * integer Newton descent. When the true value is a terminating decimal — {@code 4^0.5}, {@code 8^(1/3)},
 * {@code 2.25^0.5} — the answer is that decimal; when it is irrational, it is correctly rounded to
 * {@link MathContext#DECIMAL128}.
 *
 * <p>{@link Interval} still widens what comes back by the standard 1e-12 margin, and that is the point rather
 * than a waste: the endpoint's whole error is now that one known, deliberate, uniform quantity. {@code sqrt(4)}
 * encloses to 2 ± 1e-12 — tight enough for a feature to be reported as landing on the integer 2 — instead of to
 * a value whose distance from 2 depends on which library computed it.
 */
final class ExactPower {

    /** The precision claimed for an inexact result. */
    private static final MathContext CLAIMED = MathContext.DECIMAL128;

    /** Guard digits above the claimed precision, for the Newton iteration to converge inside. */
    private static final MathContext WORK = new MathContext(CLAIMED.getPrecision() + 8, RoundingMode.HALF_EVEN);

    /** The largest root index or power evaluated at all; past it we decline rather than lie. */
    private static final int LARGEST_INDEX = 1_000_000;

    /**
     * How large an integer the search for an <em>exact</em> root will look at, in bits.
     *
     * <p>The exact path is worth taking only while it is cheap, and it stops being cheap without warning. A
     * {@link BigDecimal} of scale {@code s} becomes a numerator and denominator around {@code 10^s}, and
     * {@link #integerRoot} then runs a Newton descent whose every step raises a number that size to a power.
     * Scale grows through the exact arithmetic upstream — the product of two scale-{@code s} values has scale
     * {@code 2s}, and a derivative is a tree of products — so an ordinary expression can arrive here with
     * hundreds of digits and an awkward one with thousands. At that point the descent is not slow, it is
     * effectively unbounded: <b>{@code (x²−y²)^(1÷2)} hung the surface renderer indefinitely</b>, burning CPU
     * inside {@code BigInteger.pow} with the window still up and nothing to show for it.
     *
     * <p>Past this size the exact attempt is abandoned for {@link #newtonRoot}, which is bounded, correctly
     * rounded to the claimed precision, and widened outward by {@link Interval} afterwards like every other
     * endpoint. So the enclosure stays sound and merely stops being <em>exact</em> — which is the trade this
     * class exists to make deliberately rather than to stumble into.
     *
     * <p>This bounds <em>cost</em>, and it is not what makes {@link #integerRoot} correct — that is its seed,
     * taken from the bit length and therefore right at every magnitude. The two were found together and are
     * worth keeping apart: with a sound seed the exact search terminates for any input at all, and this only
     * says when it has stopped being worth attempting.
     */
    private static final int LARGEST_EXACT_BITS = 900;

    private ExactPower() {
    }

    /**
     * The power could not be evaluated: an even root of a negative number, zero to a negative power, or an
     * exponent too large to take exactly. {@link Interval} answers all three by spilling to
     * {@link Enclosure#UNBOUNDED} — painting the column is always sound, where reporting a value would not be.
     */
    static final class Unevaluable extends ArithmeticException {

        Unevaluable(String message) {
            super(message);
        }
    }

    /** An exact rational, in lowest terms with a positive denominator. */
    record Rational(BigInteger num, BigInteger den) {

        static Rational reduce(BigInteger n, BigInteger d) {
            if (d.signum() == 0) {
                throw new Unevaluable("division by zero in an exponent");
            }
            if (d.signum() < 0) {
                n = n.negate();
                d = d.negate();
            }
            BigInteger common = n.gcd(d);
            return common.signum() == 0 ? new Rational(n, d) : new Rational(n.divide(common), d.divide(common));
        }

        /** A decimal exponent as the fraction it exactly is: {@code 0.5} is {@code 1/2}, never 0.5000…. */
        static Rational of(BigDecimal v) {
            BigInteger unscaled = v.unscaledValue();
            int scale = v.scale();
            return scale >= 0
                    ? reduce(unscaled, BigInteger.TEN.pow(scale))
                    : reduce(unscaled.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
        }
    }

    /** {@code base} to the exact rational {@code exponent}. */
    static BigDecimal pow(BigDecimal base, Rational exponent) {
        BigInteger num = exponent.num();
        BigInteger den = exponent.den();
        if (num.signum() == 0) {
            return BigDecimal.ONE;                         // x⁰ = 1, including 0⁰ by convention
        }
        int root = index(den);
        int sign = base.signum();
        if (sign == 0) {
            if (num.signum() < 0) {
                throw new Unevaluable("zero raised to a negative power");
            }
            return BigDecimal.ZERO;
        }
        BigDecimal powered = base.abs().pow(index(num.abs()));
        if (num.signum() < 0) {
            try {
                powered = BigDecimal.ONE.divide(powered);
            } catch (ArithmeticException nonTerminating) {
                powered = BigDecimal.ONE.divide(powered, CLAIMED);
            }
        }
        BigDecimal magnitude = rootOf(powered, root);
        if (sign < 0) {
            if (root % 2 == 0) {
                throw new Unevaluable("even root of a negative number is not real (base " + base
                        + ", exponent " + num + "/" + den + ")");
            }
            if (num.testBit(0)) {
                magnitude = magnitude.negate();            // an odd numerator keeps the sign
            }
        }
        return magnitude;
    }

    /** The {@code n}-th root of a non-negative {@code v}: exact when it terminates, else DECIMAL128. */
    private static BigDecimal rootOf(BigDecimal v, int n) {
        if (n == 1 || v.signum() == 0) {
            return v;
        }
        BigInteger numerator = v.unscaledValue();
        BigInteger denominator;
        int scale = v.scale();
        if (scale >= 0) {
            denominator = BigInteger.TEN.pow(scale);
        } else {
            numerator = numerator.multiply(BigInteger.TEN.pow(-scale));
            denominator = BigInteger.ONE;
        }
        Rational exact = Rational.reduce(numerator, denominator);
        if (exact.num().bitLength() > LARGEST_EXACT_BITS || exact.den().bitLength() > LARGEST_EXACT_BITS) {
            return newtonRoot(v, n);        // too big to search exactly; see LARGEST_EXACT_BITS
        }
        BigInteger[] top = integerRoot(exact.num(), n);
        BigInteger[] bottom = integerRoot(exact.den(), n);
        if (top[1].signum() != 0 && bottom[1].signum() != 0) {          // both are perfect n-th powers
            BigDecimal a = new BigDecimal(top[0]);
            BigDecimal b = new BigDecimal(bottom[0]);
            try {
                return a.divide(b);                                      // an exact terminating decimal
            } catch (ArithmeticException nonTerminating) {
                return a.divide(b, CLAIMED);
            }
        }
        return newtonRoot(v, n);                                         // irrational — correctly rounded
    }

    /** Floor integer {@code n}-th root of {@code a >= 0}, with an exactness flag in element {@code [1]}. */
    private static BigInteger[] integerRoot(BigInteger a, int n) {
        if (a.signum() == 0) {
            return new BigInteger[]{BigInteger.ZERO, BigInteger.ONE};
        }
        if (n == 1) {
            return new BigInteger[]{a, BigInteger.ONE};
        }
        // The seed must be an OVER-estimate, because the descent below only ever goes down and stops the moment
        // a step fails to decrease. Taken from the bit length, it always is: a < 2^L, so the n-th root is under
        // 2^(L/n + 1) for any a whatsoever.
        //
        // It used to be (long) Math.pow(a.doubleValue(), 1.0 / n), and that is where the surface renderer went
        // when asked for (x²−y²)^(1÷2). The cast saturates at Long.MAX_VALUE, so for a large a the seed came
        // back around 9.2e18 while the true root was some 10^135 — an UNDER-estimate. The descent then broke on
        // its first step, having nowhere down to go, and left the two ±1 correction loops below to walk the
        // remaining distance one integer at a time. Not slow: unreachable, with the window still up.
        BigInteger x = BigInteger.ONE.shiftLeft(a.bitLength() / n + 1);
        BigInteger order = BigInteger.valueOf(n);
        BigInteger below = BigInteger.valueOf(n - 1L);
        while (true) {                                                   // integer Newton descent
            BigInteger next = below.multiply(x).add(a.divide(x.pow(n - 1))).divide(order);
            if (next.compareTo(x) >= 0) {
                break;
            }
            x = next;
        }
        while (x.pow(n).compareTo(a) > 0) {
            x = x.subtract(BigInteger.ONE);
        }
        while (x.add(BigInteger.ONE).pow(n).compareTo(a) <= 0) {
            x = x.add(BigInteger.ONE);
        }
        return new BigInteger[]{x, x.pow(n).equals(a) ? BigInteger.ONE : BigInteger.ZERO};
    }

    /** The {@code n}-th root of {@code v > 0}, correctly rounded to the claimed precision. */
    private static BigDecimal newtonRoot(BigDecimal v, int n) {
        if (n == 2) {
            return v.sqrt(CLAIMED);                                      // the JDK's, correctly rounded
        }
        double seed = Math.pow(v.doubleValue(), 1.0 / n);
        BigDecimal x = (Double.isFinite(seed) && seed > 0) ? BigDecimal.valueOf(seed) : BigDecimal.ONE;
        BigDecimal order = BigDecimal.valueOf(n);
        BigDecimal below = BigDecimal.valueOf(n - 1L);
        for (int i = 0; i < 200; i++) {
            BigDecimal next = below.multiply(x, WORK)
                    .add(v.divide(x.pow(n - 1, WORK), WORK), WORK)
                    .divide(order, WORK);
            if (x.subtract(next).abs().compareTo(next.ulp().movePointRight(1)) < 0) {
                x = next;
                break;
            }
            x = next;
        }
        return x.round(CLAIMED);
    }

    /** An exponent part that must fit a plain {@code int} root index. */
    private static int index(BigInteger v) {
        if (v.bitLength() > 31 || v.compareTo(BigInteger.valueOf(LARGEST_INDEX)) > 0) {
            throw new Unevaluable("exponent numerator/denominator " + v
                    + " is too large to evaluate exactly (limit " + LARGEST_INDEX + ")");
        }
        return v.intValueExact();
    }
}
