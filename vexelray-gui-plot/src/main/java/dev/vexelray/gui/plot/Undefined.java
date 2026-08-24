package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.util.function.BiFunction;

/**
 * The expression has no real value <b>anywhere</b> on the column — {@code log(x)} over {@code [-4, -1]}. A true
 * gap, and the one answer a renderer should draw as nothing at all.
 *
 * <p>It absorbs: an undefined operand makes the whole expression undefined, and no later operation recovers it.
 * That is why every method here returns {@code this} without looking at its argument, and why
 * {@link #spill()} — the "cannot be bounded" answer everything else gives — does <em>not</em> turn a gap into a
 * pole. A column with no values is not a column with infinite ones.
 *
 * <p><b>Partial</b> domains are deliberately not this case. {@code sqrt} over {@code [-1, 4]} encloses its
 * defined part and stays an {@link Interval}; answering Undefined there would punch a false gap into a curve
 * that genuinely exists on {@code [0, 4]}.
 */
public record Undefined() implements Enclosure {

    @Override
    public Enclosure combine(Enclosure right, BiFunction<Interval, Interval, Enclosure> whenBothBounded) {
        return this;
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
        sink.undefined();
    }

    @Override
    public Enclosure raisedTo(BigDecimal exponent) {
        return this;
    }

    @Override
    public Enclosure sine() {
        return this;
    }

    @Override
    public Enclosure cosine() {
        return this;
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
