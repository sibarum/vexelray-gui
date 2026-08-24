package dev.vexelray.gui.plot;

import java.math.BigDecimal;

/**
 * {@code (enclosure, frame) → span}, written as an {@link Enclosure.Sink} rather than as a chain of
 * {@code instanceof} tests. The three answers arrive as three calls; each one writes the span it means; the
 * result is read off afterwards.
 *
 * <p>Not public: the classification is reached through {@link Span#of}, which is the name that says what it is.
 * This class is only where the inversion is performed, and one instance is used for exactly one enclosure.
 */
final class Classification implements Enclosure.Sink {

    private Span span = Span.BLANK;
    /** The visible extent the enclosure is clipped to: a frame's y range, or a volume's z range. */
    private final double visibleLo;
    private final double visibleHi;

    private Classification(double visibleLo, double visibleHi) {
        this.visibleLo = visibleLo;
        this.visibleHi = visibleHi;
    }

    static Span of(Enclosure enclosure, double visibleLo, double visibleHi) {
        Classification c = new Classification(visibleLo, visibleHi);
        enclosure.emitTo(c);
        return c.span;
    }

    /**
     * Clip {@code [lo, hi]} to the visible extent. Wholly above or wholly below is blank — the curve exists, but
     * not here — and anything overlapping keeps the overlapping part.
     *
     * <p>The comparison is done in {@code double} even though the endpoints are exact. That is sound: the extent
     * is itself a pair of {@code double}s, so the question being asked is already a {@code double} question, and
     * {@code BigDecimal.doubleValue} of an out-of-range endpoint saturates to an infinity, which compares the
     * right way round.
     */
    @Override
    public void bounded(BigDecimal lo, BigDecimal hi) {
        double low = lo.doubleValue();
        double high = hi.doubleValue();
        if (high < visibleLo || low > visibleHi) {
            span = Span.BLANK;
            return;
        }
        double top = fractionOf(Math.min(high, visibleHi));
        double bottom = fractionOf(Math.max(low, visibleLo));
        // Clamp rather than trust the arithmetic: the two mins above make the fractions lie in [0, 1]
        // mathematically, and rounding at the edge of a very tall frame is the one way they would not.
        span = new Span.Curve(clamp01(top), clamp01(bottom));
    }

    @Override
    public void unbounded() {
        span = Span.FILL;
    }

    @Override
    public void undefined() {
        span = Span.BLANK;
    }

    /** Where {@code v} falls down the extent: 0 at the top, 1 at the bottom — {@link Frame#fractionOf}'s law. */
    private double fractionOf(double v) {
        return (visibleHi - v) / (visibleHi - visibleLo);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
