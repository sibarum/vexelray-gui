package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.draw.Sketch;
import sibarum.tactroller.api.Key;

import java.util.function.DoubleConsumer;

/**
 * A horizontal slider: a track with a thumb positioned by a 0..1 value. Dragging the track (anywhere on it) sets
 * the value from the pointer's place along the track, with pointer capture so the drag continues off the track
 * — built entirely on {@code Gui.onDrag}. The thumb is placed between two grow-weighted spacers ({@code grow(at)}
 * and {@code grow(1-at)}) that split the free space (track minus thumb), so the thumb lands exactly at the right
 * edge at 100% with no pixel math and no overflow.
 *
 * <h2>Where it may rest is the slider's to know</h2>
 *
 * A value that is only allowed on a {@link Lattice} used to be snapped by whoever listened, after the fact: the
 * thumb followed the pointer exactly, the number it named jumped somewhere else, and the thumb caught up on the
 * next refresh. Nothing on the track said the steps existed until the thumb leapt to one. So the lattice is
 * handed to the slider, and three things follow from it having it:
 * <ul>
 *   <li><b>The points are drawn</b>, always — not on hover — as dots along the track. Where they would crowd
 *       closer than three quarters of a thumb, only every 2nd, 5th, 10th… is drawn, the way a ruler thins its
 *       ticks.</li>
 *   <li><b>The thumb clings</b>. While dragging it is placed at {@link #detent} of the pointer rather than at the
 *       pointer: held still for the first and last fifth of each gap, crossing the middle three fifths a little
 *       faster than the pointer does. It never jumps, and the steps can be felt through the pointer. The hold is a
 *       ratio of the gap, not a distance, so it scales itself: points a third of the track apart hold firmly, and
 *       points a pixel apart hold for a fifth of a pixel — which is to say not at all, as a fine lattice should.</li>
 *   <li><b>It settles</b>. Released between points, the thumb travels to the one the value names, over the
 *       {@link Ramp} if one was given ({@link #transition}).</li>
 * </ul>
 * None of these is a mode. {@link Lattice#none()} has no points, so nothing is drawn, nothing holds, and
 * nothing settles: that is the continuous slider, and it is what you get by default.
 *
 * <p>{@link #value()} is always a point of the lattice, and {@link #onChange} fires only when it changes to a
 * different one — dragging across a coarse lattice reports once per point crossed, not once per pointer move.
 * Arrow keys move one point (a twentieth of the travel when there are none); Home and End go to the ends.
 *
 * <p>Reads flow out through {@link #onChange}; the handler runs on a worker thread (the drag dispatch thread), so
 * it must not touch the retained tree except through {@link Node} handles (which are thread-safe).
 */
public final class Slider {

    /**
     * The track's height <em>and</em> the thumb's width — one length on purpose. The thumb's centre travels the
     * track less one thumb, and with the two equal that travel is {@code width - height} of the track's own box,
     * which a drag event carries. So the pointer can be put under the thumb's centre, and a pixel turned into a
     * fraction, without the handler measuring the thumb.
     */
    private static final Length THICKNESS = Length.rem(1.1f);
    /** How much of each gap, at either end, its point holds the thumb for. */
    static final float HOLD = 0.2f;
    /** Ticks closer than this fraction of the thumb's width are thinned. */
    private static final float TICK_GAP = 0.75f;
    /** A tick's radius, as a fraction of the track's height. */
    private static final float TICK_RADIUS = 0.11f;
    /** What an arrow key moves on a lattice with no points. */
    private static final float NUDGE = 1f / 20f;

    private final Gui gui;
    private final Node track;
    private final Node leftSpacer;
    private final Node rightSpacer;
    private volatile Lattice lattice = Lattice.none();
    private volatile Ramp ramp;
    private volatile float value;
    private volatile DoubleConsumer onChange = v -> { };
    /** Where the thumb is drawn, 0..1 — which is not {@link #value} mid-drag or mid-settle. Guarded by this. */
    private float at;
    /** Which movement the thumb is obeying; a settle that has been overtaken drops its writes. Guarded by this. */
    private long generation;

    /** Build a continuous slider on {@code gui} with the given initial value (0..1). */
    public Slider(Gui gui, float initial) {
        this.gui = gui;
        this.value = clamp01(initial);
        this.at = this.value;
        // The two spacers grow in proportion at : (1-at), splitting the space left over by the thumb, so the
        // thumb's centre tracks the value and never pushes past the track edge.
        this.leftSpacer = gui.box().width(Length.grow(this.at)).height(Length.percent(100));
        this.rightSpacer = gui.box().width(Length.grow(1f - this.at)).height(Length.percent(100));
        // The thumb is the grabbable thing, so it gets the physicality: a lit fill and a small drop shadow lift
        // it off the sunken track. Both are SDF transfer functions — no extra geometry, still one draw.
        Theme theme = gui.theme();
        Node thumb = gui.box().width(THICKNESS).height(Length.percent(100))
                .background(theme.color(Role.ACCENT)).corner(Length.rem(0.55f))
                .lit(theme.lit()).elevation(theme.elevation(Relief.CONTROL));
        this.track = gui.row().role("slider").height(THICKNESS)
                .background(theme.color(Role.TRACK)).corner(Length.rem(0.55f))
                .alignItems(AlignItems.CENTER).scroll(false, false) // a slider never scrolls
                .children(leftSpacer, thumb, rightSpacer);
        gui.onDrag(track, this::pointer);
        gui.onKey(track, e -> {
            if (e.key() == Key.LEFT || e.key() == Key.DOWN) {
                step(-1);
            } else if (e.key() == Key.RIGHT || e.key() == Key.UP) {
                step(1);
            } else if (e.key() == Key.HOME) {
                set(0f);
            } else if (e.key() == Key.END) {
                set(1f);
            }
        });
        // A picture resolves no units of its own, so the ticks are re-authored against the box the layout
        // settled on — which is also what carries them through a zoom or a DPI change.
        gui.onResize(track, computed -> paintTicks(computed.rect()));
        // The one thing the framework cannot infer here: a drag handler alone does not mean "grabbable" -- a text
        // field registers one too, for click-to-caret, and wants the I-beam. So the slider says so.
        gui.cursor(track, CursorShape.GRAB);
    }

    /** The node to place in a layout (the track). */
    public Node node() {
        return track;
    }

    /** Current value, 0..1 — always a point of the {@link #lattice}. */
    public float value() {
        return value;
    }

    /** Set the value programmatically (0..1), rested on the lattice; also notifies {@link #onChange}. */
    public Slider value(float v) {
        set(v);
        return this;
    }

    /**
     * Show a value the slider did not choose, <b>without</b> notifying {@link #onChange}.
     *
     * <p>A sync is not an edit. Where a panel re-reads its model and writes what it finds back into its rows,
     * {@link #value(float)} tells the application the user just dragged this — so it writes to the model, the
     * model republishes the panel, and the panel syncs again, unbounded and regardless of the value. See
     * {@link Toggle#show}.
     *
     * <p>Showing the value the slider already holds leaves the thumb where it is. A panel refreshing mid-drag is
     * reporting back what the drag just set; taking that as an order would pull the thumb off the detent the
     * pointer has it in and onto the point — the very jump the lattice is here to remove.
     */
    public Slider show(float v) {
        move(v, false);
        return this;
    }

    /**
     * Where the value may rest; {@link Lattice#none()} (the default) for anywhere. The value moves to the nearest
     * new point without notifying — the lattice changed, nobody dragged anything.
     */
    public Slider lattice(Lattice lattice) {
        this.lattice = lattice == null ? Lattice.none() : lattice;
        move(value, false);
        Rect r = track.layout().rect();
        if (r.w() > 0f) {
            paintTicks(r);
        }
        return this;
    }

    /** The lattice the value rests on. */
    public Lattice lattice() {
        return lattice;
    }

    /**
     * Give the thumb its time, so it settles onto a point rather than jumping there. Passing null, or never
     * calling this, places it in one step — which is the whole of the reduced-motion path.
     */
    public Slider transition(Ramp ramp) {
        this.ramp = ramp;
        return this;
    }

    /** React to value changes (fired on drag, keys and {@link #value(float)}). Runs on a worker thread. */
    public Slider onChange(DoubleConsumer handler) {
        this.onChange = handler == null ? v -> { } : handler;
        return this;
    }

    /**
     * Where the thumb is drawn for a pointer at {@code f}: each point of {@code lattice} holds it for {@link #HOLD}
     * of the gap either side, and the rest of the gap is crossed in a straight line. Continuous and non-decreasing
     * in {@code f}, so the thumb never jumps; equal to {@code f} when there are no points; and symmetric about each gap's midpoint, which is where {@link Lattice#nearest}
     * changes its answer — so the value turns over exactly when the thumb is halfway across.
     */
    static float detent(Lattice lattice, float f) {
        int n = lattice.size();
        if (n == 0) {
            return f;
        }
        int i = lattice.below(f);
        if (i < 0) {
            return lattice.point(0);
        }
        if (i == n - 1) {
            return lattice.point(i);
        }
        float a = lattice.point(i);
        float g = lattice.point(i + 1) - a;
        if (g <= 0f) {
            return a;
        }
        float t = (f - a) / g;
        if (t <= HOLD) {
            return a;
        }
        if (t >= 1f - HOLD) {
            return a + g;
        }
        return a + g * (t - HOLD) / (1f - 2f * HOLD);
    }

    // --- input ----------------------------------------------------------------------------------------------

    private void pointer(DragEvent e) {
        // The thumb's centre travels the track less one thumb, and the thumb is as wide as the track is high.
        float travel = e.nodeW() - e.nodeH();
        float f = travel <= 0f ? 0f : clamp01((e.x() - e.nodeX() - e.nodeH() / 2f) / travel);
        Lattice l = lattice;
        float rest = l.nearest(f);
        boolean changed;
        synchronized (this) {
            changed = rest != value;
            value = rest;
            long mine = ++generation;
            place(mine, detent(l, f));
            if (e.phase() == DragEvent.Phase.END) {
                glide(mine, rest);
            }
        }
        if (changed) {
            onChange.accept(rest);
        }
    }

    private void step(int direction) {
        Lattice l = lattice;
        float v = value;
        if (l.size() == 0) {
            set(v + direction * NUDGE);
            return;
        }
        int i = l.below(v);
        int to = direction > 0 ? i + 1 : (i >= 0 && l.point(i) == v ? i - 1 : i);
        set(l.point(Math.clamp(to, 0, l.size() - 1)));
    }

    // --- movement -------------------------------------------------------------------------------------------

    /** Move as an edit, then tell the application — outside the lock, because the handler is not ours. */
    private void set(float raw) {
        onChange.accept(move(raw, true));
    }

    /**
     * Rest the value on the lattice and send the thumb there, telling nobody; answer where it rested. An edit
     * ({@code insist}) always brings the thumb onto the point. A sync moves it only if the value changed — see
     * {@link #show} for why one that did not must leave the thumb in its detent.
     */
    private synchronized float move(float raw, boolean insist) {
        float rest = lattice.nearest(clamp01(raw));
        boolean changed = rest != value;
        value = rest;
        if (changed || insist && at != rest) {
            glide(++generation, rest);
        }
        return rest;
    }

    /**
     * Move the thumb to {@code to}, over the ramp if there is one, as movement {@code mine}. From where the thumb
     * is rather than from where the value was, and superseded by whatever moves it next: the reasons are
     * {@link Toggle}'s, and so is the generation that drops a lost settle's last write.
     */
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
        this.at = t;
        leftSpacer.width(Length.grow(t));
        rightSpacer.width(Length.grow(1f - t));
    }

    // --- ticks ----------------------------------------------------------------------------------------------

    private void paintTicks(Rect box) {
        Lattice l = lattice;
        int n = l.size();
        float h = box.h();
        float travel = box.w() - h;
        if (n == 0 || travel <= 0f) {
            track.picture(null);
            return;
        }
        float minGap = TICK_GAP * h;
        int stride = n < 2 ? 1 : stride(minGap / (travel / (n - 1)));
        float last = h / 2f + l.point(n - 1) * travel;
        Sketch s = new Sketch();
        Color ink = gui.theme().color(Role.FAINT);
        for (int i = 0; i < n; i += stride) {
            float x = h / 2f + l.point(i) * travel;
            // The far end is always drawn, so a regular tick that would crowd it gives way.
            if (i == n - 1 || last - x >= minGap) {
                s.circle(x, h / 2f, TICK_RADIUS * h, ink);
            }
        }
        if ((n - 1) % stride != 0) {
            s.circle(last, h / 2f, TICK_RADIUS * h, ink);
        }
        track.picture(s.picture());
    }

    /** The smallest of 1, 2, 5, 10, 20, 50… that is at least {@code ratio}. */
    static int stride(float ratio) {
        int decade = 1;
        while (true) {
            for (int m : new int[]{1, 2, 5}) {
                if (m * decade >= ratio) {
                    return m * decade;
                }
            }
            decade *= 10;
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
