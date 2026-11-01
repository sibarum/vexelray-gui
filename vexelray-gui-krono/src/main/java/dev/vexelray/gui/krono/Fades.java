package dev.vexelray.gui.krono;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.style.PaintMotion;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link PaintMotion} that fades every colour change, on Kronometer's clock: a hover comes up, a press goes down, a
 * selection arrives, a theme switches, each as a short blend in Oklab instead of a jump. Attach it once with
 * {@link dev.vexelray.gui.core.Gui#paintMotion} and every widget fades, its own code unchanged.
 *
 * {@snippet :
 * gui.paintMotion(Fades.on(krono, Dur.millis(120), Ease.LINEAR));
 * }
 *
 * <h2>Linear, and short</h2>
 *
 * <p>A colour has nowhere to arrive, so it wants a linear curve: an eased fade spends most of its time near one end
 * and reads as a delay followed by a jump. And it wants to be short, around a tenth of a second: a hover is feedback,
 * and feedback that lags the pointer reads as a slow application rather than a smooth one.
 *
 * <h2>Interrupted, it turns round where it is</h2>
 *
 * <p>A change that lands mid-fade starts from the colour being drawn at that moment, not from the colour the node was
 * last given, so a pointer that leaves before the hover finished coming up sees it go back down from where it got to.
 *
 * <h2>Transparent has no hue</h2>
 *
 * <p>A part that comes and goes (a ghost button's fill) is a transparent colour at rest. Blended as it stands, that is
 * black at no alpha, and the way up would pass through a darker colour than either end. So a transparent end is taken
 * to be the other end's colour at no alpha: the fade is the colour coming up, as a premultiplied mix would have it.
 *
 * <p>Reduced motion is not attaching this ({@code PaintMotion.NONE} is the default), or a zero duration, which is the
 * same thing.
 *
 * <h2>Told, then asked</h2>
 *
 * <p>A part under the frame contract (vexelray-gui's docs/plans/frame-contract.md): {@link #repainted} records a fade
 * and when it began on the tree's clock, and {@link #draw}, run once per frame, sets each fade for the frame's present
 * time. Nothing runs on the timeline, so nothing between a frame's colours and the time it is drawn for can slip.
 */
public final class Fades implements PaintMotion {

    /**
     * One colour on its way, from {@code start} on the tree's clock for {@code over}. {@code t} is where the last
     * {@link #draw} put it, which is what {@link #drawn} reads; nothing else writes it.
     */
    private static final class Fade {
        final Color from;
        final Color to;
        final long start;
        final long over;
        final Ease ease;
        volatile float t;

        Fade(Color from, Color to, long start, long over, Ease ease) {
            this.from = from;
            this.to = to;
            this.start = start;
            this.over = over;
            this.ease = ease;
        }

        /** How far along it is at {@code nanos}, eased: exactly 0 at the start and exactly 1 from the end on. */
        float at(long nanos) {
            long elapsed = nanos - start;
            return elapsed <= 0L ? 0f : elapsed >= over ? 1f : ease.at((float) ((double) elapsed / over));
        }

        /** The ends exactly, and Oklab between: a round trip through it moves a colour by a rounding error. */
        Color colour(float at) {
            return at <= 0f ? from : at >= 1f ? to : Colors.OKLAB.between(from, to, at);
        }

        Color now() {
            return colour(t);
        }
    }

    private record Key(long node, Channel channel) {
    }

    private final KronoGui krono;
    private final Dur over;
    private final Ease ease;
    private final Map<Key, Fade> fades = new ConcurrentHashMap<>();

    private Fades(KronoGui krono, Dur over, Ease ease) {
        this.krono = Objects.requireNonNull(krono, "krono");
        this.over = Objects.requireNonNull(over, "over");
        this.ease = Objects.requireNonNull(ease, "ease");
    }

    /** Every colour change faded over {@code over} on {@code ease}, timed on {@code krono}'s frame clock. */
    public static Fades on(KronoGui krono, Dur over, Ease ease) {
        return new Fades(krono, over, ease);
    }

    /**
     * How many repaints running a colour may have and still be faded. A colour written again within
     * {@link #DRIVEN_GAP_NANOS} of the last, on more than this many writes running, is being driven by a clock of its
     * own (an application ramping a wash by hand, a colour that follows a value), and a fade started on each step would
     * trail behind it: the calculator's tape wash came up in twice its own time. So from the third such write it is
     * drawn at once, as a node moved on every frame is followed rather than chased.
     */
    static final int FADED_WRITES = 2;

    /** Longer than any frame a ramp writes at, shorter than a pointer crossing a control and coming back. */
    static final long DRIVEN_GAP_NANOS = 40_000_000L;

    /** When a cell was last repainted, and on how many writes running: {@code [nanos, streak]}. GUI thread. */
    private final Map<Key, long[]> recent = new java.util.HashMap<>();

    @Override
    public void repainted(long node, Channel channel, Color from, Color to) {
        Key key = new Key(node, channel);
        if (from.equals(to)) {
            fades.remove(key);   // told it is at rest where it is: whatever was running there is let go
            return;
        }
        Dur over = krono.paced(this.over);   // none while motion is reduced: the colour is drawn the moment it is set
        if (over.nanos() <= 0L || driven(key, System.nanoTime())) {
            fades.remove(key);
            return;
        }
        Fade running = fades.get(key);
        long now = krono.nanos();
        // From where it is drawn at this moment, if it is on its way: the present time, not the last frame's sample.
        Color start = running == null ? from : running.colour(running.at(now));
        if (start.equals(to)) {
            fades.remove(key);
            return;
        }
        // A transparent end has no hue of its own: take the other end's, at no alpha.
        Color a = start.a() <= 0f ? Color.withAlpha(to, 0f) : start;
        Color b = to.a() <= 0f ? Color.withAlpha(a, 0f) : to;
        // Told now, drawn from the frame that shows it: the first draw finds it at its start colour.
        fades.put(key, new Fade(a, b, now, over.nanos(), ease));
    }

    /**
     * The asked half: every fade at the frame's present time. One that has arrived is let go, so it is drawn in the
     * colour it was given from this frame on and asks for nothing more.
     */
    @Override
    public long draw(Frame frame) {
        if (fades.isEmpty()) {
            return Frame.NEVER;
        }
        long now = frame.presentNanos();
        for (Map.Entry<Key, Fade> e : fades.entrySet()) {
            Fade fade = e.getValue();
            float at = fade.at(now);
            if (at >= 1f) {
                fades.remove(e.getKey(), fade);
            } else {
                fade.t = at;
            }
        }
        return fades.isEmpty() ? Frame.NEVER : now;
    }

    /** Whether {@code key}'s colour is being written on every frame by someone else's clock (see {@link #FADED_WRITES}). */
    private boolean driven(Key key, long now) {
        if (recent.size() > 1024) {
            recent.values().removeIf(r -> now - r[0] > DRIVEN_GAP_NANOS);   // nothing left in a run: forget it
        }
        long[] r = recent.computeIfAbsent(key, k -> new long[] {Long.MIN_VALUE / 2, 0L});
        r[1] = now - r[0] <= DRIVEN_GAP_NANOS ? r[1] + 1 : 1;
        r[0] = now;
        return r[1] > FADED_WRITES;
    }

    @Override
    public Color drawn(long node, Channel channel, Color settled) {
        Fade fade = fades.get(new Key(node, channel));
        // A fade on its way somewhere else is not this colour's: never draw a colour the node was not given on the
        // way to one it was. The transparent end was swapped for a hued one, so it is compared by alpha alone.
        if (fade == null || !arrivesAt(fade, settled)) {
            return settled;
        }
        return fade.now();
    }

    private static boolean arrivesAt(Fade fade, Color settled) {
        return settled.a() <= 0f ? fade.to.a() <= 0f : fade.to.equals(settled);
    }

    /** Whether nothing is fading: for a test, or a host asking whether the paint is at rest. */
    public boolean settled() {
        return fades.isEmpty();
    }

    @Override
    public String toString() {
        return "Fades(" + over + ", " + ease + ", " + fades.size() + " fading)";
    }
}
