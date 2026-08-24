package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.util.function.BiFunction;

/**
 * The column spills to ±∞ somewhere inside it. A pole ({@code 1/x} over a column containing zero), or detail
 * finer than the column can resolve ({@code tan(1/x)} near the origin) — the two are the same answer here, and
 * telling them apart is a job for subdivision, not for the arithmetic.
 *
 * <p>Nothing bounded can bound this again, so almost every operation returns it unchanged. The exceptions are
 * the two that are bounded regardless of their argument: {@link #sine()} and {@link #cosine()} lie in
 * {@code [-1, 1]} whatever goes in, and saying so is not a loss of soundness but the tightest true answer.
 */
public record Unbounded() implements Enclosure {

    @Override
    public Enclosure combine(Enclosure right, BiFunction<Interval, Interval, Enclosure> whenBothBounded) {
        return right.spill();
    }

    @Override
    public Enclosure againstBounded(Interval left, BiFunction<Interval, Interval, Enclosure> whenBothBounded) {
        return this;
    }

    @Override
    public Enclosure spill() {
        return this;
    }

    @Override
    public void emitTo(Sink sink) {
        sink.unbounded();
    }

    @Override
    public Enclosure raisedTo(BigDecimal exponent) {
        return this;
    }

    @Override
    public Enclosure sine() {
        return Interval.UNIT;
    }

    @Override
    public Enclosure cosine() {
        return Interval.UNIT;
    }

    @Override
    public Enclosure tangent() {
        return this;
    }

    @Override
    public Enclosure exponential() {
        return this;
    }

    @Override
    public Enclosure logarithm() {
        return this;
    }
}
