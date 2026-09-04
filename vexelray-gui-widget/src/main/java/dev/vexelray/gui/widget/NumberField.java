package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.function.DoubleConsumer;

/**
 * A number, typed or stepped: a {@link TextField} that only ever hands out a number, and arrow keys that walk it
 * by a step.
 *
 * <h2>What is on screen and what has been agreed are two different things</h2>
 *
 * Half of {@code -1.5} is {@code -1.}, and {@code -} alone is half of every negative number there is. A field that
 * validated on every keystroke would refuse both and make the number unreachable by typing it, so the text is left
 * alone while it is being edited and read only when the edit <b>commits</b> — Enter, or focus leaving. Until then
 * {@link #value()} answers the last agreed number, not what the characters currently say. That is also why an
 * unparseable commit does not clear the field: it puts the last agreed number back, so the field always shows a
 * number and the user always sees which one they are now holding.
 *
 * <h2>The step is exact</h2>
 *
 * Stepping is {@link BigDecimal} rather than {@code double} addition, because {@code 0.1} ten times is famously not
 * {@code 1.0} and a field the user has stepped up and down ten times should read what it read before. The value
 * handed out is a {@code double} because that is what the callers of this are plotting with; the arithmetic
 * getting there is not obliged to lose the same digits.
 */
public final class NumberField {

    private static final MathContext MC = new MathContext(17, RoundingMode.HALF_EVEN);

    private final TextField field;
    private final BigDecimal step;
    private final double min;
    private final double max;
    private final Subscription focusSub;
    private volatile BigDecimal value;
    private volatile DoubleConsumer onChange = v -> { };

    /** Build an unbounded field stepping by {@code step}. */
    public NumberField(Gui gui, double initial, double step) {
        this(gui, initial, step, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /** Build a field stepping by {@code step} and clamped to {@code [min, max]}. */
    public NumberField(Gui gui, double initial, double step, double min, double max) {
        this.step = BigDecimal.valueOf(step);
        this.min = min;
        this.max = max;
        this.value = clamp(BigDecimal.valueOf(initial));
        this.field = new TextField(gui, plain(this.value));
        field.node().width(Length.rem(4f)).align(
                dev.vexelray.text.TextLayout.HAlign.RIGHT, dev.vexelray.text.TextLayout.VAlign.MIDDLE);
        field.onSubmit(s -> commit());
        // Arrow keys step. They are claimed on the field rather than globally: an up-arrow anywhere else in the
        // window is still whatever that place already meant.
        gui.onKey(field.node(), e -> {
            if (e.key() == Key.UP) {
                nudge(1);
            } else if (e.key() == Key.DOWN) {
                nudge(-1);
            }
        });
        long id = field.node().id();
        this.focusSub = gui.bus().subscribe(gui.focusEvents(), e -> {
            if (!e.gained() && e.nodeId() == id) {
                commit();
            }
        });
    }

    /** Release the focus subscription. The underlying {@link TextField} is the caller's to close. */
    public void close() {
        focusSub.close();
    }

    /** The node to place in a layout. */
    public Node node() {
        return field.node();
    }

    /** The field underneath, for spans, context menus and the rest of what a text field can do. */
    public TextField field() {
        return field;
    }

    /** The last agreed number — not what the characters currently say. See the class note. */
    public double value() {
        return value.doubleValue();
    }

    /** Set the number programmatically; also notifies {@link #onChange}. */
    public NumberField value(double v) {
        put(clamp(BigDecimal.valueOf(v)));
        return this;
    }

    /** React to agreed numbers only — on commit and on {@link #value(double)}. Runs on a worker thread. */
    public NumberField onChange(DoubleConsumer handler) {
        this.onChange = handler == null ? v -> { } : handler;
        return this;
    }

    private void nudge(int direction) {
        put(clamp(value.add(step.multiply(BigDecimal.valueOf(direction), MC), MC)));
    }

    private void commit() {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(field.text().trim());
        } catch (NumberFormatException | ArithmeticException e) {
            // Not a number: put the last agreed one back, so the field never shows something it does not mean.
            field.text(plain(value));
            return;
        }
        put(clamp(parsed));
    }

    private void put(BigDecimal v) {
        this.value = v;
        field.text(plain(v));
        onChange.accept(v.doubleValue());
    }

    private BigDecimal clamp(BigDecimal v) {
        if (Double.isFinite(min) && v.doubleValue() < min) {
            return BigDecimal.valueOf(min);
        }
        if (Double.isFinite(max) && v.doubleValue() > max) {
            return BigDecimal.valueOf(max);
        }
        return v;
    }

    /** Trailing zeros carry no information here, and {@code 1E+2} is not what anyone typed. */
    private static String plain(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }
}
