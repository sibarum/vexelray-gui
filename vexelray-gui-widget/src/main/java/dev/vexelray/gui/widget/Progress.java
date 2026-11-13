package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;

/**
 * How far a long operation has got: a track, and a fill that covers the part that is done. Determinate when the
 * operation knows its size (a file converted, bytes installed), indeterminate when it does not yet (a folder still
 * being counted, a step with no measure). Deliberately not a {@link Cue}: a cue is one-shot, and this has a value that
 * keeps changing for as long as the work runs.
 *
 * <h2>Shown, never chosen</h2>
 *
 * Nobody drags a progress bar, so it publishes nothing, has no address and records no undo. The work is the
 * application's: a job reports how far it has got, as a message, and the view that hears it calls {@link #show}. A
 * Cancel is the application's too, a {@link Button} beside the bar whose press the job hears, because what cancelling
 * means (stop at once, finish the file, roll back) is the job's to say.
 *
 * <p>For the same reason the bar has no disabled look: there is nothing on it to disable. What it does have is the
 * {@link State} of the work it shows, which the job says as it says the value.
 *
 * <p>Every method is safe from any thread, as every widget's is, so a test, or a host that has no view between the job
 * and the bar, may call it from where the work is done.
 *
 * <h2>A report costs a frame only when it moves the fill</h2>
 *
 * The fill is the full width of the track and is <em>translated</em> left by the part not yet done, inside a track that
 * clips to its own rounded corners, so the fill's leading end is always the pill's own end and a new value lays nothing
 * out ({@link Slider}'s reason). And a value within half a pixel of the one the fill was last sent to is held, not
 * drawn: a job that reports every block of a large file, thousands of times a second, still draws at most one frame
 * per half-pixel the fill crosses, which is about two frames per pixel of track for the whole job. The ends are never
 * held, so 0 and 1 are always drawn exactly.
 *
 * <h2>Indeterminate, and the glint, are the asked half</h2>
 *
 * Unknown progress is a short segment crossing the track and coming round again, and a {@link State#RUNNING running}
 * bar's glint crosses the fill now and then; both move every frame they move, so they are the widget's asked half
 * ({@code docs/reference/seams.md}, <i>A widget's asked half</i>): offsets set through {@link Drawn}, no allocation,
 * nothing laid out. They are paced at about sixty frames a second rather than the display's rate, since a job that runs
 * for minutes makes this the whole of the work some windows do while they wait, and the glint rests between crossings
 * for longer than it crosses. Nothing asks for a frame while the bar is out of the tree or scrolled out of sight.
 *
 * <p><b>Under reduced motion nothing crosses.</b> An indeterminate bar washes the whole track with the fill's colour,
 * faint and still: busy, amount unknown, and visibly not a full bar. A running bar has no glint.
 *
 * <p><b>Motion of the determinate fill is opt-in</b>, as everywhere in this library: with no {@link #transition} each
 * value is drawn where it lands; with one, the fill glides from where it is drawn to the new value, and a value that
 * arrives mid-glide takes over from where the fill then is.
 */
public final class Progress {

    /**
     * What the work the bar shows is doing, which is a different thing from how far it has got: a bar at 40% may be
     * under way, waiting on the user, or dead. Each state carries its own look, so there is no table of them to keep.
     *
     * <p>A job that was cancelled is none of these: cancelling is the user's own act and nothing to warn about, so put
     * the bar back to {@link #IDLE} at the value it reached, or take it away.
     */
    public enum State {
        /** A value, and nothing said about the work: the plain fill, still. The state a bar starts in. */
        IDLE(Role.ACCENT, true, false),
        /**
         * The work is under way. A soft glint crosses the fill every few seconds, so a bar that has not moved for a
         * while (a large file, a slow disk) still shows that nothing has hung.
         */
        RUNNING(Role.ACCENT, true, true),
        /** Stopped for now and expected to go on (paused by the user, waiting on an answer): amber, and still. */
        PAUSED(Role.CAUTION, false, false),
        /** Stopped by a fault, at the value it reached: danger red, and still. */
        FAILED(Role.DANGER, false, false);

        private final Role ink;
        private final boolean sweeps;
        private final boolean glints;

        State(Role ink, boolean sweeps, boolean glints) {
            this.ink = ink;
            this.sweeps = sweeps;
            this.glints = glints;
        }
    }

    /** The track's height; the fill is translated in em, measured from this. */
    private static final float THICKNESS_REM = 0.5f;
    private static final Length THICKNESS = Length.rem(THICKNESS_REM);
    private static final Length ROUND = Length.rem(THICKNESS_REM / 2f);
    /** The indeterminate segment, as a fraction of the track. */
    static final float SWEEP = 0.3f;
    /** One crossing of the indeterminate segment. */
    static final long PERIOD_NANOS = 1_600_000_000L;
    /** How soon a moving bar asks again: about sixty frames a second, whatever the display runs at. */
    static final long PACE_NANOS = 16_666_667L;
    /** How strongly a still wash covers the track. */
    static final float WASH = 0.4f;
    /** The least movement of the fill that is drawn, in px. */
    static final float HOLD_PX = 0.5f;
    /** The glint, as a fraction of the track; one crossing of the part done; and the rest before the next. */
    static final float GLINT = 0.2f;
    static final long GLINT_CROSS_NANOS = 900_000_000L;
    static final long GLINT_REST_NANOS = 2_100_000_000L;
    /** Each of the glint's three nested bands; they stack to a soft centre, there being no gradient paint. */
    private static final Paint GLINT_BAND = Role.ON_ACTION.alpha(0.12f);

    private final Gui gui;
    private final Node track;
    private final Node fill;
    private final Node glint;
    private volatile Ramp ramp;

    /** The last fraction shown, 0..1; NaN while indeterminate. */
    private volatile double value;
    /** Whether the sweep runs: written under this, read by the asked half with no lock. */
    private volatile boolean sweeping;
    /** Bumped each time the sweep starts, so the asked half starts its crossing from the left. */
    private volatile int sweeps;
    /** What the work is doing: written under this, read by the asked half with no lock. */
    private volatile State state = State.IDLE;
    /** Bumped each time the bar starts running, so the first glint crosses at once. */
    private volatile int runs;
    /** Where the fill is drawn, 0..1 — not {@link #value} mid-glide. Written under this; the glint reads it. */
    private volatile float at;

    /** Where the fill was last sent, which is what a new value is held against. Guarded by this. */
    private float aimed;
    /** Which movement the fill obeys; a glide overtaken drops its writes. Guarded by this. */
    private long generation;
    /** The track's laid-out width and the em, in px; 0 until the track is laid out. Guarded by this. */
    private float widthPx;
    private float emPx;

    // The asked half's own, touched on the GUI thread only.
    private int sweepSeen = -1;
    private long sweepFrom;
    private int runSeen = -1;
    private long glintFrom;
    private boolean displaced;

    /** A determinate bar at 0, {@link State#IDLE}, on {@code gui}. */
    public Progress(Gui gui) {
        this.gui = gui;
        this.glint = band(gui).width(Length.percent(GLINT * 100f)).visible(false)
                .children(band(gui).width(Length.percent(60f))
                        .children(band(gui).width(Length.percent(50f))));
        // Hidden until the track has been measured: an unmeasured fill would be drawn untranslated, which is full.
        // Clipped to its own end, so the glint stops where the work done does.
        this.fill = gui.row().width(Length.percent(100)).height(Length.percent(100))
                .background(State.IDLE.ink).corner(ROUND).scroll(false, false).clip(true)
                .opacity(0f).hitInert(true)
                .children(glint);
        this.track = gui.row().role("progressbar").height(THICKNESS)
                .background(Role.TRACK).corner(ROUND)
                .scroll(false, false).clip(true)
                .children(fill);
        Seams.of(gui).onResize(track, computed -> measure(computed.rect()));
        Seams.of(gui).ask(this::draw);
    }

    private static Node band(Gui gui) {
        return gui.row().height(Length.percent(100)).corner(ROUND).background(GLINT_BAND)
                .justify(Justify.CENTER).scroll(false, false).hitInert(true);
    }

    /** The node to place in a layout: the track. Give it a width, or let its parent stretch it. */
    public Node node() {
        return track;
    }

    /**
     * Show {@code fraction} done, 0 to 1, clamped; NaN is {@link #indeterminate()}. Ends the sweep if it runs. A value
     * within half a pixel of the one last drawn is held (see the class). Leaves the {@link State} as it is.
     */
    public Progress show(double fraction) {
        if (Double.isNaN(fraction)) {
            return indeterminate();
        }
        float f = (float) Math.max(0.0, Math.min(1.0, fraction));
        synchronized (this) {
            value = f;
            if (sweeping) {
                // From the sweep straight to the value: there is no drawn fill to glide from.
                sweeping = false;
                fill.width(Length.percent(100));
                aimed = f;
                place(++generation, f);
                return this;
            }
            if (f != 0f && f != 1f && widthPx > 0f && Math.abs(f - aimed) * widthPx < HOLD_PX) {
                return this;
            }
            aimed = f;
            glide(++generation, f);
        }
        return this;
    }

    /**
     * Show {@code done} of {@code total}: bytes, files, steps. A {@code total} of zero or less is not known yet, which is
     * {@link #indeterminate()}.
     */
    public Progress show(long done, long total) {
        return total <= 0L ? indeterminate() : show((double) done / (double) total);
    }

    /** Show work of unknown size: the sweep, or a still wash when reduced motion or the {@link State} says so. */
    public Progress indeterminate() {
        synchronized (this) {
            value = Double.NaN;
            if (sweeping) {
                return this;
            }
            sweeping = true;
            sweeps++;
            ++generation;             // a glide in flight drops its writes
            fill.translate(0f, 0f);
            fill.width(Length.percent(SWEEP * 100f));
        }
        gui.requestFrame();
        return this;
    }

    /**
     * Say what the work is doing. The value stays where it is: a job that fails at 40% shows 40% in red. Setting the
     * state it already has does nothing, so a job may say it with every report.
     */
    public Progress state(State next) {
        State s = next == null ? State.IDLE : next;
        synchronized (this) {
            State was = state;
            if (was == s) {
                return this;
            }
            state = s;
            if (s.glints && !was.glints) {
                runs++;
            }
            fill.background(s.ink);
            glint.visible(s.glints);
        }
        gui.requestFrame();
        return this;
    }

    /** What the work is doing, as last said. */
    public State state() {
        return state;
    }

    /** The fraction last shown, 0 to 1, or NaN while indeterminate. */
    public double fraction() {
        return value;
    }

    /** Whether the bar shows work of unknown size. */
    public boolean isIndeterminate() {
        return sweeping;
    }

    /**
     * Give the determinate fill its time, so it glides to each value rather than landing there. Null, or never calling
     * this, draws each value where it lands, which is also the whole of the reduced-motion path.
     */
    public Progress transition(Timing timing) {
        this.ramp = Ramp.of(gui, timing);
        return this;
    }

    // --- the determinate fill -------------------------------------------------------------------------------------

    /** Move the fill to {@code to} as movement {@code mine}, over the ramp if there is one: the slider's glide. */
    private void glide(long mine, float to) {
        Ramp r = ramp;
        float from = at;
        if (r == null || from == to) {
            place(mine, to);
        } else {
            r.run(p -> place(mine, from + (to - from) * (float) p), () -> place(mine, to));
        }
    }

    private synchronized void place(long movement, float t) {
        if (movement != generation) {
            return;
        }
        at = t;
        if (!sweeping && emPx > 0f) {
            fill.translate(-(1f - t) * widthPx / emPx, 0f);
        }
    }

    /** Take the track's width and em from its laid-out {@code box}, and put the fill back where it is drawn. */
    private void measure(Rect box) {
        boolean moving;
        synchronized (this) {
            widthPx = Math.max(0f, box.w());
            emPx = box.h() <= 0f ? 0f : box.h() / THICKNESS_REM;
            moving = sweeping || state.glints;
            if (!sweeping && emPx > 0f) {
                fill.translate(-(1f - at) * widthPx / emPx, 0f);
            }
            if (emPx > 0f) {
                fill.opacity(1f);
            }
        }
        if (moving) {
            // The asked half reads the track's layout, which is a frame stale; look again now that it is real.
            gui.requestFrame();
        }
    }

    // --- the asked half ------------------------------------------------------------------------------------------

    /**
     * The sweep and the glint, for {@code frame}'s moment. While the bar is out of the tree or out of sight, and when
     * nothing moves, it asks for nothing. GUI thread; no lock, no allocation.
     */
    long draw(Frame frame, Drawn out) {
        State s = state;
        boolean sweep = sweeping;
        if (!sweep && displaced) {
            out.offset(fill, 0f, 0f).scale(fill, 1f).opacity(fill, 1f);
            displaced = false;
        }
        if (!sweep && !s.glints) {
            return Frame.NEVER;
        }
        NodeLayout l = track.layout();
        if (!l.present() || l.clippedAway()) {
            // Put back in the tree or scrolled back into sight, the track is laid out again, and that is a frame.
            return Frame.NEVER;
        }
        float w = l.rect().w();
        long now = frame.presentNanos();
        boolean still = frame.reducedMotion();
        if (sweep) {
            out.offset(glint, w, 0f);                 // the glint is for a fill with an end; off past it
            float sw = w * SWEEP;
            displaced = true;
            if (still || !s.sweeps) {
                out.offset(fill, (w - sw) / 2f, 0f).scale(fill, 1f / SWEEP).opacity(fill, WASH);
                return Frame.NEVER;
            }
            int n = sweeps;
            if (n != sweepSeen) {
                sweepSeen = n;
                sweepFrom = now;
            }
            out.offset(fill, sweepAt(now - sweepFrom, w), 0f).scale(fill, 1f).opacity(fill, 1f);
            return now + PACE_NANOS;
        }
        // Running, determinate: the glint.
        if (still) {
            out.offset(glint, w, 0f);
            return Frame.NEVER;
        }
        int r = runs;
        if (r != runSeen) {
            runSeen = r;
            glintFrom = now;
        }
        long cycle = GLINT_CROSS_NANOS + GLINT_REST_NANOS;
        long into = Math.floorMod(now - glintFrom, cycle);
        if (into >= GLINT_CROSS_NANOS) {
            out.offset(glint, w, 0f);
            return now + (cycle - into);
        }
        out.offset(glint, glintAt(into, w, at), 0f);
        return now + PACE_NANOS;
    }

    /**
     * Where the segment's left edge is, {@code elapsed} into the sweep, on a track {@code w} wide: from just off the
     * left, where it starts unseen, to just off the right, eased in and out, then round again.
     */
    static float sweepAt(long elapsed, float w) {
        double e = easeInOut(Math.floorMod(elapsed, PERIOD_NANOS) / (double) PERIOD_NANOS);
        float sw = w * SWEEP;
        return (float) (-sw + e * (w + sw));
    }

    /**
     * Where the glint's left edge is, {@code elapsed} into a crossing, in the fill's own coordinates on a track {@code w}
     * wide with {@code done} of it done: the fill is the track's width slid left, so the part that shows starts at
     * {@code (1 - done) w}. From just before that to just past the fill's end.
     */
    static float glintAt(long elapsed, float w, float done) {
        double e = easeInOut(Math.min(1.0, elapsed / (double) GLINT_CROSS_NANOS));
        float gw = w * GLINT;
        float from = (1f - done) * w - gw;
        return (float) (from + e * (w - from));
    }

    private static double easeInOut(double p) {
        return p < 0.5 ? 4.0 * p * p * p : 1.0 - Math.pow(-2.0 * p + 2.0, 3.0) / 2.0;
    }
}
