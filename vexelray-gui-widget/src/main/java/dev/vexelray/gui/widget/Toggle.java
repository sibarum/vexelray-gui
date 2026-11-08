package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Addressed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Finish;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

import sibarum.atchung.Topic;

import java.util.function.Consumer;

/**
 * A switch: a track with a knob that sits at one end or the other, and a click that moves it.
 *
 * <h2>The knob is placed by the same trick the {@link Slider} uses, for the same reason</h2>
 *
 * Two grow-weighted spacers split the free space (track minus knob) in the ratio {@code on : (1-on)}, so the knob
 * lands flush against the leading edge when off and the trailing edge when on, at any track width and any DPI,
 * with no pixel arithmetic to keep in step with the geometry. A switch is a slider that only holds two values, and
 * it is worth it being literally that rather than nearly that — the alternative is a hand-computed inset that is
 * correct at one rem size.
 *
 * <p><b>Motion is opt-in, like everything else here.</b> The knob crossing the track is a {@link Ramp}, installed
 * or not by the application; with none installed the knob simply appears at the other end, which is what a switch
 * did before there was motion and is also the whole of the reduced-motion path.
 *
 * <p>A flip is published on {@link #CHANGED}, carrying the switch's {@link #address() address}: the last in a frame,
 * because it is state (vexelray-framework's {@code docs/threading.md}, T3.8).
 *
 * <p><b>A flip undoes.</b> Each flip the user makes — or {@link #on(boolean)}, acting as the user — is an entry in the
 * history the tree binds above the switch; Ctrl+Z flips it back the same way, message and all, so the application's
 * model follows with nothing written for it. {@link #show} records nothing, because nobody flipped anything.
 * {@link #undoable(boolean)} turns it off for a switch that is not an edit.
 */
public final class Toggle {

    /** Whether a switch is on, whenever a flip (the user's, or {@link #on(boolean)}) changes it. The last in a frame. */
    public static final Topic<Changed> CHANGED =
            Addressed.state(Changed.class, Changed::toggle);

    /** The switch at {@code toggle} is now {@code on}, or not. */
    public record Changed(String toggle, boolean on) implements Addressed {
        @Override
        public Object address() {
            return toggle;
        }
    }

    private final Gui gui;
    private final Node track;
    private final Node knob;
    private final Node before;
    private final Node after;
    private volatile boolean on;
    private volatile Consumer<Boolean> onChange = v -> { };
    private final WidgetName name;
    private volatile Ramp ramp;
    /** Where the knob is, 0 to 1 — which is not {@link #on} while it is travelling. Guarded by {@code this}. */
    private float at;
    /** Which flip the knob is currently obeying; see {@link #move}. Guarded by {@code this}. */
    private long generation;
    /** The way back from each flip, into the tree's history. */
    private final ValueUndo<Boolean> undo;

    /** Build a switch on {@code gui} in the given initial state. */
    public Toggle(Gui gui, boolean initial) {
        this.gui = gui;
        this.on = initial;
        Theme theme = gui.theme();
        float w = initial ? 1f : 0f;
        this.at = w;
        this.before = gui.box().width(Length.grow(w)).height(Length.percent(100));
        this.after = gui.box().width(Length.grow(1f - w)).height(Length.percent(100));
        this.knob = gui.box().width(Length.rem(0.7f)).height(Length.rem(0.7f))
                .corner(Length.rem(0.35f))
                .background(Role.INK)
                .lit(Finish.LIT).elevation(theme.elevation(Relief.CONTROL));
        this.track = gui.row().role("switch")
                .width(Length.rem(1.6f)).height(Length.rem(0.9f))
                .corner(Length.rem(0.45f))
                .padding(Length.rem(0.1f))
                .alignItems(AlignItems.CENTER)
                .scroll(false, false)
                .children(before, knob, after);
        this.name = new WidgetName(gui, "toggle", track);
        paint(false);
        gui.focusable(track, true);
        gui.cursor(track, CursorShape.POINTER);
        Seams.of(gui).onClick(track, this::flip);
        Seams.of(gui).onState(track, s -> paint(s == dev.vexelray.gui.core.input.InteractionState.HOVER));
        this.undo = new ValueUndo<>(gui, track, () -> on, this::set, false);
    }

    /**
     * Whether flips are undoable: on by default. Off for a switch that is not an edit — a view's "show hidden
     * files" — whose flips should not stand between the user and what they actually changed.
     */
    public Toggle undoable(boolean undoable) {
        undo.enabled(undoable);
        return this;
    }

    /** The node to place in a layout (the track). */
    public Node node() {
        return track;
    }

    /** Whether the switch is on. */
    public boolean on() {
        return on;
    }

    /** Set the state programmatically, as the user would: publishes {@link #CHANGED}. */
    public Toggle on(boolean value) {
        boolean was = on;
        set(value);
        undo.edited(was, value);
        return this;
    }

    /**
     * Show a state the switch did not choose, <b>without</b> publishing {@link #CHANGED}.
     *
     * <p><b>A sync is not an edit, and reporting one as an edit is a loop.</b> Where a panel re-reads its model
     * and writes what it finds back into its switches, {@link #on(boolean)} tells the application the user just
     * flipped them — so the application writes to the model, the model tells the panel it changed, and the panel
     * syncs again. That is unbounded regardless of the value, because the notification does not depend on the
     * state actually moving. Use this for "the model changed under me" and {@code on(boolean)} only where
     * something really is acting as the user would.
     *
     * <p>The knob still travels, because where the switch is standing is a question about the model and not
     * about who moved it.
     */
    public Toggle show(boolean value) {
        move(value);
        return this;
    }

    /** Call this switch {@code name} in its tree ("wrap"): its {@link #address()} is the tree's name and this. */
    public Toggle name(String name) {
        this.name.name(name);
        return this;
    }

    /** Where this switch is on the bus: what its {@link Changed} carries. */
    public String address() {
        return name.address();
    }

    /**
     * The widget library's hook on a flip, for a widget built from switches: run as the flip is made, and as well as
     * {@link #CHANGED} rather than instead. Package-private, because an application hears a switch by its message.
     */
    Toggle onChange(Consumer<Boolean> handler) {
        this.onChange = handler == null ? v -> { } : handler;
        return this;
    }

    /**
     * Give the knob its time, so it travels rather than jumps. Passing null, or never calling this, moves the
     * knob in one step — see the class note. A ramp installed mid-travel takes effect on the next flip; the one
     * in flight finishes on the terms it started with.
     */
    public Toggle transition(Timing timing) {
        this.ramp = Ramp.of(gui, timing);
        return this;
    }

    private void flip() {
        boolean was = on;
        set(!was);
        undo.edited(was, !was);
    }

    /** Change state, then say so. */
    private void set(boolean value) {
        move(value);
        onChange.accept(value);
        gui.bus().publish(CHANGED, new Changed(name.address(), value));
    }

    /**
     * Move the knob to {@code value}, over the ramp if there is one.
     *
     * <p><b>From where the knob actually is, not from the other end.</b> A switch flipped again mid-travel
     * reverses from wherever it had got to; taking {@code from} to be the state it logically left would make a
     * fast second click jump the knob to a position it was never in and then walk it back.
     *
     * <p>And <b>every flip supersedes the one before it</b>. A duration is a window in which the user can act
     * again, and two ramps writing one pair of widths is a race whose loser then finishes and writes the target
     * it was aiming at — leaving the knob at the end the switch is no longer at, a wrong reading rather than a
     * wrong animation. The generation is what a superseded ramp finds when it tries: the last write of a lost
     * flip is dropped, so a transition never has to learn that it lost.
     */
    private synchronized void move(boolean value) {
        boolean was = this.on;
        this.on = value;
        paint(false);
        long mine = ++generation;
        Ramp r = this.ramp;
        if (r == null || was == value) {
            place(mine, value ? 1f : 0f);
        } else {
            float from = this.at;
            float to = value ? 1f : 0f;
            r.run(p -> place(mine, from + (to - from) * (float) p), () -> place(mine, to));
        }
    }

    private synchronized void place(long forFlip, float t) {
        if (forFlip != generation) {
            return;
        }
        this.at = t;
        before.width(Length.grow(t));
        after.width(Length.grow(1f - t));
    }

    private void paint(boolean hover) {
        // On is the accent because the switch is the one control here whose whole reading is a colour; off is the
        // track every other control sinks into, so a row of switches has one shape and two colours rather than two
        // shapes.
        track.background(on ? Role.ACCENT : Role.TRACK);
        track.border(Length.dp(1), hover ? Role.GRIP : Role.EDGE);
        knob.background(on ? Role.ON_ACTION : Role.DIM);
    }
}
