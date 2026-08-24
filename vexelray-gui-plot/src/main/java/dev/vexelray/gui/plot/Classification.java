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
    private final Frame frame;

    private Classification(Frame frame) {
        this.frame = frame;
    }

    static Span of(Enclosure enclosure, Frame frame) {
        Classification c = new Classification(frame);
        enclosure.emitTo(c);
        return c.span;
    }

    /**
     * Clip {@code [lo, hi]} to the frame. Wholly above or wholly below is blank — the curve exists, but not
     * here — and anything overlapping keeps the overlapping part.
     *
     * <p>The comparison is done in {@code double} even though the endpoints are exact. That is sound: the frame
     * is itself a {@code double} rectangle, so the question being asked is already a {@code double} question,
     * and {@code BigDecimal.doubleValue} of an out-of-range endpoint saturates to an infinity, which compares
     * the right way round.
     */
    @Override
    public void bounded(BigDecimal lo, BigDecimal hi) {
        double low = lo.doubleValue();
        double high = hi.doubleValue();
        if (high < frame.yLo() || low > frame.yHi()) {
            span = Span.BLANK;
            return;
        }
        double top = frame.fractionOf(Math.min(high, frame.yHi()));
        double bottom = frame.fractionOf(Math.max(low, frame.yLo()));
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

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
