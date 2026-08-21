package dev.vexelray.gui.krono;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Clock;
import sibarum.kronometer.Curve;
import sibarum.kronometer.Driven;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Effect;
import sibarum.kronometer.Interp;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Metro;
import sibarum.kronometer.Rate;

import sibarum.kronometer.Signal;
import sibarum.kronometer.Tempo;
import sibarum.kronometer.Time;
import sibarum.kronometer.anim.Animator;
import sibarum.kronometer.anim.Ease;
import sibarum.kronometer.anim.Tween;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Kronometer, attached to a VexelRay GUI.
 *
 * <p>Everything in this module hangs off one idea: the GUI already has a frame loop and a bus, so the
 * timing framework should slot into them rather than replace them.
 *
 * <pre>{@code
 * Atchung bus = Atchung.create();
 * Gui gui = new Gui(bus);
 * try (GuiApp app = new GuiApp("demo", 1280, 800);
 *      KronoGui krono = KronoGui.attach(gui)) {
 *
 *     krono.bind(card, Node::elevation, krono.lift());       // a signal drives a property
 *     gui.onClick(button, () -> krono.animate(               // callable from a worker thread
 *             card, Node::elevation, dp(2), dp(8), ms(200), Ease.OUT_CUBIC));
 *
 *     app.run(gui, 0, krono::tick);                          // one tick per presented frame
 * }
 * }</pre>
 *
 * <h2>Where it attaches, and why there</h2>
 *
 * {@code GuiApp.run}'s {@code beforeFrame} hook is documented as the app-edge place to pump input onto
 * the bus <em>before</em> {@link Gui#frame} drains and dispatches. That is exactly the right moment for
 * {@link #tick()}: a {@linkplain Driven.Mode#INLINE driven} clock returns with its batch complete, so
 * every mutation an animation posts this frame is already on the bus when the reconciler runs. Nothing
 * is drawn out of phase with what was computed, and the frame that presents it is the frame that
 * computed it.
 *
 * <h2>Off the timeline, which is where your handlers are</h2>
 *
 * A click handler runs on the GUI's worker executor, not on the timeline — so a bare
 * {@code Time.spork} from inside one would be refused, and starting an animation from a click is the
 * single most common thing anyone wants to do. Every scheduling method here therefore routes through
 * {@link #onTimeline}: it runs the work immediately if you are already on the timeline, and posts it to
 * the next observed moment if you are not. You can call {@link #animate} from anywhere and it does the
 * right thing.
 */
public final class KronoGui implements AutoCloseable {

    private final Gui gui;
    private final Kron kron;
    private final Rate frames;
    private final Animator animator;

    private long epochNanos = Long.MIN_VALUE;
    private long ticks;

    private KronoGui(Gui gui, Kron kron) {
        this.gui = gui;
        this.kron = kron;
        this.frames = kron.dynamic("frames");
        this.animator = new Animator(kron);
        // A dynamic domain steps once per tick, after everything else scheduled in that window — the
        // right order for a pass that reads what the rest of the frame just produced.
        this.frames.each(step -> { });
    }

    /** Attach a driven Kronometer to {@code gui}. Tick it from {@code GuiApp.run}'s {@code beforeFrame}. */
    public static KronoGui attach(Gui gui) {
        return new KronoGui(Objects.requireNonNull(gui, "gui"), Kron.driven());
    }

    /**
     * Attach with a supplied runtime — for a headless run.
     *
     * <p>Use {@link Kron#driven()} for that, not {@link Clock#virtual()}: because the harness supplies
     * every tick value itself, a driven run is <em>already</em> fully deterministic. The virtual clock
     * exists to invent moments when nobody else will, and a test that ticks by hand is the one inventing
     * them. This is also the shape a Tactroller scenario harness wants.
     */
    public static KronoGui attach(Gui gui, Kron kron) {
        return new KronoGui(Objects.requireNonNull(gui, "gui"), Objects.requireNonNull(kron, "kron"));
    }

    // ------------------------------------------------------------ the clock

    /**
     * Advance logical time to now and run everything due. Hand this to
     * {@code GuiApp.run(gui, maxFrames, beforeFrame)}.
     *
     * <p>Returns with the batch complete, so the mutations this frame's animations produce are on the bus
     * before {@link Gui#frame} reconciles.
     */
    public void tick() {
        if (epochNanos == Long.MIN_VALUE) {
            epochNanos = System.nanoTime();
        }
        ticks++;
        kron.tick(System.nanoTime() - epochNanos);
    }

    /** Advance to an explicit logical moment — for a scripted or headless run. */
    public void tick(Dur elapsed) {
        ticks++;
        kron.tick(elapsed.nanos());
    }

    public Gui gui() {
        return gui;
    }

    public Kron kron() {
        return kron;
    }

    /** The root time context. Nest a child to run a region of the UI at a different rate. */
    public Tempo tempo() {
        return kron.tempo();
    }

    /** The per-frame domain: steps once per {@link #tick()}, with the real frame interval as its {@code dt}. */
    public Rate frames() {
        return frames;
    }

    public Animator animator() {
        return animator;
    }

    /** How many frames have been ticked. */
    public long ticks() {
        return ticks;
    }

    // ------------------------------------------------------------ scheduling

    /**
     * Run {@code work} on the timeline: now if you are already there, otherwise at the next moment the
     * kernel observes.
     *
     * <p>The reason every other method here goes through it — GUI handlers run off the timeline, and
     * making that the caller's problem would mean every click handler needed to know about the baton.
     */
    public void onTimeline(Runnable work) {
        Objects.requireNonNull(work, "work");
        if (kron.isOnTimeline()) {
            work.run();
        } else {
            kron.post(work);
        }
    }

    /** A shred: ordinary code that can advance time. Safe to call from a handler. */
    public Scheduled spork(String name, Runnable body) {
        Objects.requireNonNull(body, "body");
        Scheduled handle = new Scheduled();
        onTimeline(() -> handle.bind(Time.spork(name, body)));
        return handle;
    }

    /** Run {@code body} once, {@code delay} from now. */
    public Scheduled after(Dur delay, Runnable body) {
        Objects.requireNonNull(body, "body");
        return spork("after", () -> {
            Time.advance(delay);
            body.run();
        });
    }

    /**
     * Run {@code body} every {@code period}, forever, drift-free.
     *
     * <p>Uses a {@link Metro}, so the n-th run is at exactly {@code n · period} from the start rather
     * than accumulating a rounding error per tick. Cancel it with the returned handle.
     */
    public Scheduled every(Dur period, Runnable body) {
        Objects.requireNonNull(body, "body");
        return spork("every", () -> {
            Metro metro = Metro.of(period);
            while (true) {
                metro.tick();
                body.run();
            }
        });
    }

    // ------------------------------------------------------------ automation

    /**
     * Drive a node property from a signal, once per frame.
     *
     * <p>The signal is the source of truth and the node is just where it lands, which is the shape that
     * makes the value precomputable: if the signal descends only from curves, the whole visible future of
     * this property can be evaluated ahead, off the frame thread.
     */
    public <T> Effect bind(Node node, BiConsumer<Node, T> setter, Signal<T> signal) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(setter, "setter");
        Objects.requireNonNull(signal, "signal");
        return kron.effect(frames, () -> setter.accept(node, signal.get()));
    }

    /** A cell whose value lands on a node property every frame. The usual way to start. */
    public <T> Cell<T> bound(String name, T initial, Node node, BiConsumer<Node, T> setter) {
        Cell<T> cell = kron.cell(name, initial);
        bind(node, setter, cell);
        return cell;
    }

    /**
     * Animate {@code cell} to {@code target}, continuing from wherever it currently is.
     *
     * <p>Interruption-safe: retargeting mid-flight reverses from the current value rather than snapping,
     * and the result is still a curve, so it stays precomputable. Safe from a handler thread.
     */
    public <T> void retarget(Cell<T> cell, T target, Dur over, Ease ease, Interp<T> interp) {
        onTimeline(() -> animator.retarget(cell, target, over, ease, interp));
    }

    public void retarget(Cell<Length> cell, Length target, Dur over, Ease ease) {
        retarget(cell, target, over, ease, Lengths.LERP);
    }

    public void retarget(Cell<Color> cell, Color target, Dur over, Ease ease) {
        retarget(cell, target, over, ease, Colors.OKLAB);
    }

    /**
     * A one-shot animation of a node property, from an explicit start to an explicit end.
     *
     * <p>The blunt instrument, for a property with no cell behind it. {@link #bound} plus
     * {@link #retarget} is better whenever the property has a resting value worth naming, because it
     * survives interruption; this restarts from {@code from} every time.
     */
    public <T> void animate(
            Node node, BiConsumer<Node, T> setter, T from, T to, Dur over, Ease ease, Interp<T> interp) {

        Curve<T> curve = Tween.curve(from, to, over, ease, interp);
        onTimeline(() -> {
            Cell<T> cell = kron.cell("animate", from);
            Effect effect = bind(node, setter, cell);
            Time.spork("animate", () -> {
                cell.drive(curve);
                Time.advance(over);
                effect.cancel();          // the property has arrived; stop paying for it every frame
            });
        });
    }

    public void animate(Node node, BiConsumer<Node, Length> setter,
                        Length from, Length to, Dur over, Ease ease) {
        animate(node, setter, from, to, over, ease, Lengths.LERP);
    }

    public void animate(Node node, BiConsumer<Node, Color> setter,
                        Color from, Color to, Dur over, Ease ease) {
        animate(node, setter, from, to, over, ease, Colors.OKLAB);
    }

    /** Read a GUI value into the graph as a volatile source — something the app writes, not a curve. */
    public <T> Cell<T> live(String name, Supplier<T> initial) {
        Cell<T> cell = kron.cell(name, initial.get());
        onTimeline(cell::live);
        return cell;
    }

    @Override
    public void close() {
        kron.close();
    }

    @Override
    public String toString() {
        return "KronoGui(" + ticks + " frames, " + kron.clock() + ")";
    }
}
