package dev.vexelray.gui.core;

import dev.vexelray.gui.core.spi.Clock;
import dev.vexelray.gui.core.spi.Seams;
import sibarum.atchung.Topic;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A view's own motion, for what it draws itself: a position between rest (0) and arrived (1) that eases towards
 * whatever it is sent to, and announces where it is on {@link #VALUE} at each step. The view says where to go with
 * {@link #toward} and hears the value in its mailbox, addressed to {@link #address()}, where it redraws.
 *
 * {@snippet :
 * Tween lift = Tween.of(gui, "lens.lift", Timing.millis(160, Timing.Curve.OUT_CUBIC));
 * gui.mailbox(Tween.VALUE, lift.address(), t -> paint(t.value()));
 * gui.hear(well, NodeEvent.STATE, s -> lift.toward(s == InteractionState.NORMAL ? 0f : 1f));
 * }
 *
 * <p><b>Why a value that announces, and not a ramp</b> (vexelray-framework's {@code docs/threading.md}, T7.2): a ramp
 * is a function the view hands the clock, which runs it on the GUI thread in the frame. Here the code the clock runs
 * is this class's, and the view's runs in its own subscription. A widget is handed a {@link Timing} for the same reason;
 * this is that, for a view that draws.
 *
 * <p><b>From wherever it is.</b> Sent somewhere new mid-way, it turns there, without a jump, and takes the part of the
 * timing the distance left is: a hover undone halfway eases back in half the time. The ramp it overtook stops
 * announcing. With no clock installed, or with motion reduced, it lands at once.
 */
public final class Tween {

    /** Where a tween is: a state topic keyed by its address, so a view hears the last step of a frame. */
    public static final Topic<Tweened> VALUE = Topic.state(Tweened.class, Tweened::tween);

    /** The tween at {@code tween} is at {@code value}, in [0,1]; {@code settled} once it has arrived. */
    public record Tweened(String tween, float value, boolean settled) implements Addressed {
        @Override
        public Object address() {
            return tween;
        }
    }

    private final Gui gui;
    private final String address;
    private final Timing timing;

    private volatile float value;
    /** Which run may still announce: each {@link #toward} takes the next, and an overtaken ramp's steps are dropped. */
    private final AtomicInteger run = new AtomicInteger();

    private Tween(Gui gui, String address, Timing timing) {
        this.gui = gui;
        this.address = address;
        this.timing = timing;
    }

    /**
     * A tween at rest in {@code gui}'s tree, at {@link Gui#address gui.address(name)}, crossing the whole way in
     * {@code timing}.
     */
    public static Tween of(Gui gui, String name, Timing timing) {
        Objects.requireNonNull(gui, "gui");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(timing, "timing");
        return new Tween(gui, gui.address(name), timing);
    }

    /** Where its values are addressed: what a view's mailbox answers to. */
    public String address() {
        return address;
    }

    /**
     * Ease towards {@code target}, clamped to [0,1], from wherever it is now. Any thread. Announces each step, and the
     * arrival as settled; sent where it already is, it announces that it is settled there.
     */
    public void toward(float target) {
        float to = Math.max(0f, Math.min(1f, target));
        float from = value;
        int mine = run.incrementAndGet();
        Clock clock = Seams.of(gui).clock();
        if (from == to || clock == null) {
            value = to;
            announce(to, true);
            return;
        }
        long nanos = Math.max(Duration.ofMillis(1).toNanos(), Math.round(timing.duration().toNanos() * Math.abs(to - from)));
        clock.run(new Timing(Duration.ofNanos(nanos), timing.curve()), p -> {
            if (run.get() == mine) {
                value = from + (to - from) * (float) p;
                announce(value, false);
            }
        }, () -> {
            if (run.get() == mine) {
                value = to;
                announce(to, true);
            }
        });
    }

    private void announce(float v, boolean settled) {
        gui.bus().publish(VALUE, new Tweened(address, v, settled));
    }

    @Override
    public String toString() {
        return "Tween[" + address + " at " + value + "]";
    }
}
