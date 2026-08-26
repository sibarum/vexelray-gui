package dev.vexelray.gui.plot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactPowerTest {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    /**
     * The regression. {@code (x²−y²)^(1÷2)} used to hang the surface renderer outright — not slowly, but with
     * no finish available: a root was being sought by walking to it one integer at a time across a distance of
     * about 10^135, because the Newton seed had saturated to {@code Long.MAX_VALUE} and left the descent with
     * nowhere down to go. It burned CPU inside {@code BigInteger.pow} with the window still up.
     *
     * <p>The value below is what such an endpoint looks like by the time a derivative's nested products have
     * had their way with it: an ordinary magnitude carried at an extraordinary scale.
     */
    @Test
    @DisplayName("a root of a large-scale endpoint finishes, and finishes quickly")
    void largeScaleRootTerminates() {
        BigDecimal awkward = new BigDecimal("2.25").pow(64);           // scale 128, and a perfect square
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            Enclosure e = new Interval(awkward, awkward).raisedTo(HALF);
            assertTrue(e instanceof Interval, "a positive base has a real root: " + e);
            Interval i = (Interval) e;
            // 2.25^64 square-rooted is exactly 2.25^32, and the enclosure must contain it.
            BigDecimal truth = new BigDecimal("2.25").pow(32);
            assertTrue(i.lo().compareTo(truth) <= 0 && i.hi().compareTo(truth) >= 0,
                    "enclosure " + i + " does not contain " + truth);
        });
    }

    @Test
    @DisplayName("roots stay correct at every magnitude the seed used to get wrong")
    void rootsAreCorrectAcrossMagnitudes() {
        // The old seed was fine for small values and silently wrong for large ones, so the sweep is the point:
        // it walks straight through the magnitude where a double stops being able to hold the answer.
        for (int power = 1; power <= 220; power += 3) {
            BigDecimal value = BigDecimal.TEN.pow(power);              // 10^power, exactly
            Enclosure e = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> new Interval(value, value).raisedTo(HALF), "10^" + power);
            Interval i = (Interval) e;
            // Checked by squaring back rather than against a computed truth: the enclosure claims to contain
            // the root, so squaring its endpoints must produce a range containing what was rooted.
            assertTrue(i.lo().multiply(i.lo()).compareTo(value) <= 0
                            && i.hi().multiply(i.hi()).compareTo(value) >= 0,
                    "10^" + power + ": [" + i.lo() + ", " + i.hi() + "] squares to a range missing it");
        }
    }

    @Test
    @DisplayName("an exact square root is still exact")
    void exactRootsStayExact() {
        // The bound that skips the exact search must not have taken the ordinary cases with it.
        Interval four = new Interval(new BigDecimal("4"), new BigDecimal("4"));
        Interval root = (Interval) four.raisedTo(HALF);
        assertTrue(root.lo().compareTo(new BigDecimal("2")) <= 0
                        && root.hi().compareTo(new BigDecimal("2")) >= 0,
                "sqrt(4) should enclose 2, was " + root);
        // Tight, not merely correct: the class exists so that a landmark can be reported as landing on 2.
        assertTrue(root.hi().subtract(root.lo()).compareTo(new BigDecimal("1e-9")) < 0,
                "sqrt(4) should be tight, was " + root);
    }

    @Test
    @DisplayName("an even root of a wholly negative column is undefined, not a guess")
    void evenRootOfNegativeIsUndefined() {
        Enclosure e = new Interval(new BigDecimal("-9"), new BigDecimal("-4")).raisedTo(HALF);
        assertEquals(Enclosure.UNDEFINED, e);
    }
}
