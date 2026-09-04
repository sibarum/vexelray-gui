package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

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
 * <p>Reads flow out through {@link #onChange}; the handler runs on a worker thread, so it must not touch the
 * retained tree except through {@link Node} handles.
 */
public final class Toggle {

    private final Gui gui;
    private final Node track;
    private final Node knob;
    private final Node before;
    private final Node after;
    private volatile boolean on;
    private volatile Consumer<Boolean> onChange = v -> { };
    private volatile Ramp ramp;

    /** Build a switch on {@code gui} in the given initial state. */
    public Toggle(Gui gui, boolean initial) {
        this.gui = gui;
        this.on = initial;
        Theme theme = gui.theme();
        float w = initial ? 1f : 0f;
        this.before = gui.box().width(Length.grow(w)).height(Length.percent(100));
        this.after = gui.box().width(Length.grow(1f - w)).height(Length.percent(100));
        this.knob = gui.box().width(Length.rem(0.7f)).height(Length.rem(0.7f))
                .corner(Length.rem(0.35f))
                .background(theme.color(Role.INK))
                .lit(theme.lit()).elevation(theme.elevation(Relief.CONTROL));
        this.track = gui.row().role("switch")
                .width(Length.rem(1.6f)).height(Length.rem(0.9f))
                .corner(Length.rem(0.45f))
                .padding(Length.rem(0.1f))
                .alignItems(AlignItems.CENTER)
                .scroll(false, false)
                .children(before, knob, after);
        paint(false);
        gui.focusable(track, true);
        gui.cursor(track, CursorShape.POINTER);
        gui.onClick(track, this::flip);
        gui.onState(track, s -> paint(s == dev.vexelray.gui.core.input.InteractionState.HOVER));
    }

    /** The node to place in a layout (the track). */
    public Node node() {
        return track;
    }

    /** Whether the switch is on. */
    public boolean on() {
        return on;
    }

    /** Set the state programmatically; also notifies {@link #onChange}. */
    public Toggle on(boolean value) {
        set(value);
        return this;
    }

    /** React to state changes (fired on click and on {@link #on(boolean)}). Runs on a worker thread. */
    public Toggle onChange(Consumer<Boolean> handler) {
        this.onChange = handler == null ? v -> { } : handler;
        return this;
    }

    /**
     * Give the knob its time, so it travels rather than jumps. With no ramp installed the knob moves in one step
     * — see the class note.
     */
    public Toggle transition(Ramp ramp) {
        this.ramp = ramp;
        return this;
    }

    private void flip() {
        set(!on);
    }

    private void set(boolean value) {
        boolean was = this.on;
        this.on = value;
        paint(false);
        Ramp r = this.ramp;
        if (r == null || was == value) {
            place(value ? 1f : 0f);
        } else {
            float from = was ? 1f : 0f;
            float to = value ? 1f : 0f;
            r.run(p -> place(from + (to - from) * (float) p), () -> place(to));
        }
        onChange.accept(value);
    }

    private void place(float t) {
        before.width(Length.grow(t));
        after.width(Length.grow(1f - t));
    }

    private void paint(boolean hover) {
        Theme theme = gui.theme();
        // On is the accent because the switch is the one control here whose whole reading is a colour; off is the
        // track every other control sinks into, so a row of switches has one shape and two colours rather than two
        // shapes.
        track.background(on ? theme.color(Role.ACCENT) : theme.color(Role.TRACK));
        track.border(Length.dp(1), theme.color(hover ? Role.GRIP : Role.EDGE));
        knob.background(on ? theme.color(Role.ON_ACTION) : theme.color(Role.DIM));
    }
}
