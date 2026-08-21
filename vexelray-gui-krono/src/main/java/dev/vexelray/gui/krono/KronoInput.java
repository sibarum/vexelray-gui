package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.input.InputTopics;
import sibarum.atchung.Atchung;
import sibarum.atchung.Backpressure;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Signal;
import sibarum.kronometer.Trigger;
import sibarum.kronometer.anim.Smooth;
import sibarum.kronometer.atchung.KronBridge;
import sibarum.tactroller.api.InputEvent;

import java.util.Objects;

/**
 * Input as a scheduling primitive.
 *
 * <p>The three repos already agree on one thing: tactroller publishes device events onto an Atchung
 * {@code Topic<InputEvent>}, and the GUI subscribes to that same topic to dispatch them. Nothing here
 * intercepts any of that. It adds a second, independent subscriber so the <em>timeline</em> can see the
 * same events — which turns input from a callback into something a shred can wait for.
 *
 * <pre>{@code
 * KronoInput input = KronoInput.on(krono, bus);
 * input.drainOn(krono.frames());
 *
 * krono.spork("gesture", () -> {
 *     while (true) {
 *         Time.await(input.pressed());          // "wait for a key" — a yield point, not a handler
 *         Moment down = Time.now();
 *         Time.await(input.released());
 *         log(Time.now().since(down));          // and the duration is a number, not bookkeeping
 *     }
 * });
 * }</pre>
 *
 * <h2>What this is not</h2>
 *
 * It is not a replacement for {@code gui.onClick}. Hit-testing, focus, claim scopes and the widget
 * semantics all live in {@code gui-core}'s dispatcher, and reimplementing them here would be a second
 * source of truth for which node was clicked. This is the layer <em>below</em> that: raw device events,
 * timestamped on the timeline, for the things dispatch cannot express — a hold, a double-tap window, a
 * gesture with a timeout, a recorded input script.
 *
 * <h2>Why the cell is volatile, and what that costs</h2>
 *
 * {@link #latest()} is declared {@link Cell#live() live}, so its horizon is {@code now} and nothing
 * derived from it will be precomputed. That is the honest classification — nobody can foresee the next
 * mouse move — and it is worth knowing that it propagates: a signal that mixes input with a curve is
 * unpredictable, so keep the animated part of a value separate from the input-driven part if you want
 * the animation precomputed. {@link Smooth#chase} is the usual way to bridge the two.
 */
public final class KronoInput implements AutoCloseable {

    private final KronBridge bridge;
    private final Cell<InputEvent> latest;
    private final Trigger any;

    private KronoInput(KronoGui krono, Atchung bus) {
        this.bridge = new KronBridge(krono.kron(), bus);
        // COALESCE_LATEST for the cell: a queue of stale pointer positions is worse than none, because
        // only the newest is the truth. The trigger keeps a real queue, because a dropped keystroke is
        // not the same as a superseded one.
        this.latest = bridge.cell(InputTopics.INPUT, null, 1, Backpressure.COALESCE_LATEST);
        this.any = bridge.trigger(InputTopics.INPUT, 256, Backpressure.DROP_OLDEST);
    }

    /** Watch the input topic on {@code bus} — the same bus the GUI was constructed with. */
    public static KronoInput on(KronoGui krono, Atchung bus) {
        Objects.requireNonNull(krono, "krono");
        Objects.requireNonNull(bus, "bus");
        return new KronoInput(krono, bus);
    }

    /**
     * Deliver queued input on {@code domain}'s step.
     *
     * <p>Register this <b>before</b> any effect that reads input, because handlers on a domain run in
     * registration order — that is what makes an effect see this frame's input rather than the last
     * frame's.
     */
    public void drainOn(Rate domain) {
        bridge.drainOn(domain);
    }

    /** Fired whenever any device event arrives. The general-purpose yield point. */
    public Trigger any() {
        return any;
    }

    /**
     * The most recent event, as a volatile signal. {@code null} until the first one arrives.
     *
     * <p>Read it inside an effect on the draining domain and you get this frame's input; read it
     * anywhere else and you get whatever the last drain left.
     */
    public Cell<InputEvent> latest() {
        return latest;
    }

    /** How many events have crossed onto the timeline. */
    public long delivered() {
        return bridge.delivered();
    }

    /**
     * A smoothed follower of a value derived from input, integrated on a fixed grid.
     *
     * <p>Convenience for the common shape, and it carries the constraint with it: an integrated smoother
     * needs a constant {@code dt}, so this takes a <em>fixed</em> domain and Kronometer refuses a dynamic
     * one at construction. Chasing raw input on the frame domain would make the feel depend on the
     * refresh rate, which is the bug that constraint exists to prevent.
     */
    public Signal<Double> smoothed(
            KronoGui krono, Rate fixedDomain, Signal<Double> target, Dur timeConstant) {
        return Smooth.chase(krono.kron(), fixedDomain, target, timeConstant);
    }

    @Override
    public void close() {
        bridge.close();
    }

    @Override
    public String toString() {
        return "KronoInput(" + bridge.delivered() + " events)";
    }
}
