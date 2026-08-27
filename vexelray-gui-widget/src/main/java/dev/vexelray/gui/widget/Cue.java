package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.draw.Picture;

import java.util.ArrayList;
import java.util.List;

/**
 * A one-shot visual that says something happened to a node — a sweep across the field that just took a command,
 * a wash over the one something else wrote to, a ring around the one that rejected what it was given.
 *
 * <p><b>A cue is a pure function of progress and a box.</b> It is handed how far through it is and the size of
 * the box it decorates, and it returns the {@link Picture} to paint over that box at that instant; {@link Cues}
 * calls it once a frame and clears the box afterwards. Nothing here holds state, touches a node, or knows what
 * time it is, which is what makes a cue three things at once: definable by an application in a lambda, testable
 * with no GUI and no clock at all, and safe to play on a node the framework does not own.
 *
 * <p><b>Why it returns a picture rather than moving the node.</b> The overlay slot ({@code Node.overlay}) is
 * purely additive: its identity value is null, so whatever a cue paints can be taken back off without anyone
 * knowing what was there before. A cue that reached for {@code opacity} or {@code translate} instead would have
 * to restore them, and their resting values belong to the application — a node with a standing offset would come
 * back from an error flash in the wrong place. So the surface is the one that can be cleared rather than
 * remembered, and motion of the node itself stays outside it, where the application already owns it.
 *
 * <p>The stock cues below are compositions, not a closed set. An application's own is a lambda:
 *
 * <pre>{@code
 * Cue underscore = (t, box) -> Picture.of(new Picture.Fill(
 *         0, box.h() - 2, box.w() * (float) t, 2, 0, 0, accent));
 * }</pre>
 *
 * @see Cues for playing one, and for what happens when the same node is cued again mid-cue
 */
@FunctionalInterface
public interface Cue {

    /**
     * The picture to paint over the box at progress {@code t} (0 at the start, 1 at the end).
     *
     * <p>Return {@link Picture#EMPTY} for "nothing right now" — a cue is not obliged to paint at every instant,
     * and the two endpoints are the ones worth getting right: a cue that paints something at 1 is a cue that
     * ends by disappearing rather than by fading.
     */
    Picture at(double t, Box box);

    /**
     * The box a cue paints in: its size and its corners, in pixels, with {@code (0, 0)} at its top-left.
     *
     * <p><b>Deliberately not {@link NodeLayout}.</b> That carries the box's position in <em>root</em> space, and
     * a cue paints in the box's own frame — an author who reached for {@code rect().x()} would be wrong by
     * wherever the node happened to land on screen, silently and only when the layout moved. What a cue may know
     * is the shape it is decorating, so that is what it is given.
     *
     * @param w            border-box width in px
     * @param h            border-box height in px
     * @param cornerTopPx  corner radius above the centre line — a cue that hugs the box matches it
     * @param cornerBottomPx corner radius below the centre line
     */
    record Box(float w, float h, float cornerTopPx, float cornerBottomPx) {

        /** This node's box, or {@link #NONE} if it has no layout yet or no area to paint in. */
        public static Box of(NodeLayout layout) {
            if (layout == null || !layout.present()
                    || layout.rect().w() <= 0f || layout.rect().h() <= 0f) {
                return NONE;
            }
            return new Box(layout.rect().w(), layout.rect().h(),
                    layout.cornerTopPx(), layout.cornerBottomPx());
        }

        /** No box: nothing has been laid out, so there is nothing to decorate. */
        public static final Box NONE = new Box(0f, 0f, 0f, 0f);

        /** Whether there is anywhere to paint. */
        public boolean isEmpty() {
            return w <= 0f || h <= 0f;
        }

        /** The smaller side — what a measure proportional to "how big this box is" should scale by. */
        public float minSide() {
            return Math.min(w, h);
        }
    }

    /** A cue that paints nothing: the reduced-motion collapse of any cue, and a harmless default. */
    Cue NONE = (t, box) -> Picture.EMPTY;

    /**
     * This cue run backwards — {@code t} mirrored. A scanline that sweeps up rather than down, and the reason
     * direction is not a parameter on every factory here.
     */
    default Cue reversed() {
        return (t, box) -> at(1d - t, box);
    }

    /**
     * This cue and {@code other} painted together, this one underneath. Composition rather than a factory taking
     * every combination: an error is a {@link #ring} and a {@link #wash}, said as {@code ring.with(wash)}.
     */
    default Cue with(Cue other) {
        return (t, box) -> {
            Picture a = at(t, box);
            Picture b = other.at(t, box);
            if (a.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return a;
            }
            List<Picture.Mark> marks = new ArrayList<>(a.marks().size() + b.marks().size());
            marks.addAll(a.marks());
            marks.addAll(b.marks());
            return new Picture(marks);
        };
    }

    // --- stock cues -----------------------------------------------------------------------------------------

    /** How many strips approximate the scanline's trailing glow. The alphabet has no gradient; this is one. */
    int TRAIL_BANDS = 8;

    /**
     * A bright line sweeping down the box once, with a soft glow trailing behind it — the acknowledgement for a
     * field that just took something.
     *
     * <p><b>The line starts on the top edge, not above it.</b> Letting the band fly in from off the box is the
     * tidier-looking arithmetic and it is wrong: the trail is a large fraction of the box, so the lead-in costs a
     * third of the duration with nothing on screen, and a 260ms cue then has 150ms of visible sweep. That is
     * under the threshold at which something registers as having happened at all — which is exactly the failure
     * this cue exists to avoid, arrived at by being tasteful about the entrance. It begins at the moment of the
     * event because that is what it is reporting. The trail still hangs off the top edge and is clipped there,
     * which costs nothing and is what keeps the leading edge from reading as a bar.
     *
     * <p>Give this a <b>linear</b> ramp. The band is travelling and has no place to arrive at — easing it makes
     * the sweep finish in the first third and stall, which reads as a jump followed by a delay.
     *
     * @param color the line's colour, at the alpha it should have at its brightest; the glow derives from it
     */
    static Cue scanline(Color color) {
        return scanline(color, 0.6f, 0.08f);
    }

    /**
     * A scanline with its proportions named: {@code trail} and {@code core} are fractions of the box height, so
     * the same cue reads the same on a one-line field and on a panel, and it survives a zoom without being
     * rebuilt — the box it is handed is already in the pixels the layout just resolved.
     */
    static Cue scanline(Color color, float trail, float core) {
        return (t, box) -> {
            if (box.isEmpty()) {
                return Picture.EMPTY;
            }
            float trailH = Math.max(6f, box.h() * trail);
            float coreH = Math.max(2f, box.h() * core);
            // On the top edge at 0 and wholly past the bottom at 1: every frame of the duration is a frame with
            // the bright line somewhere in the box. The trail hangs off the top and the clip deals with it.
            float head = (float) t * (box.h() + coreH);
            float band = trailH / TRAIL_BANDS;
            List<Picture.Mark> marks = new ArrayList<>(TRAIL_BANDS + 1);
            for (int i = 0; i < TRAIL_BANDS; i++) {
                // Quadratic falloff away from the core, so the glow reads as light rather than as steps. The
                // half-pixel overlap is what keeps the seams between strips from showing as scan lines of
                // their own.
                float k = (i + 1f) / TRAIL_BANDS;
                marks.add(new Picture.Fill(0f, head - trailH + band * i, box.w(), band + 0.5f, 0f, 0f,
                        Color.withAlpha(color, color.a() * k * k * 0.55f), "cue-scanline-trail"));
            }
            marks.add(new Picture.Fill(0f, head, box.w(), coreH, 0f, 0f, color, "cue-scanline"));
            return new Picture(marks);
        };
    }

    /**
     * The whole box tinted, up fast and down slow — the acknowledgement for a value that changed without the
     * user doing it, where the point is that the eye is drawn to <em>where</em> rather than told to watch
     * something travel.
     *
     * <p>The attack is short and the release is most of the duration, which is the asymmetry that makes a flash
     * read as a flash: a symmetric fade in and out reads as something appearing, which invites a look at what it
     * <em>is</em>, and there is nothing there to look at.
     */
    static Cue wash(Color color) {
        return wash(color, 0.15f);
    }

    /** A wash with its attack named, as a fraction of the duration. */
    static Cue wash(Color color, float attack) {
        return (t, box) -> {
            if (box.isEmpty()) {
                return Picture.EMPTY;
            }
            float a = envelope((float) t, attack);
            return a <= 0f ? Picture.EMPTY : Picture.of(new Picture.Fill(
                    0f, 0f, box.w(), box.h(), box.cornerTopPx(), box.cornerBottomPx(),
                    Color.withAlpha(color, color.a() * a), "cue-wash"));
        };
    }

    /**
     * A ring hugging the box, pulsed twice — the acknowledgement for something refused. Two pulses rather than
     * one because a single flash is the same shape as every other cue here and so says only "something
     * happened"; a repeat is what the eye reads as insistence, and refusal is the case that has to be
     * distinguishable at a glance from acceptance.
     *
     * <p>It hugs the border box, matching the corners it was handed, and it draws over the node's real border —
     * so a field keeps its outline and briefly wears another, rather than appearing to change shape.
     */
    static Cue ring(Color color) {
        return ring(color, 2);
    }

    /** A ring with its pulse count named. Every pulse starts and ends at nothing, so the cue ends invisible. */
    static Cue ring(Color color, int pulses) {
        int n = Math.max(1, pulses);
        return (t, box) -> {
            if (box.isEmpty()) {
                return Picture.EMPTY;
            }
            // sin over each pulse's own [0,1): zero at both ends of every one, so there is no step between them.
            float a = (float) Math.sin(Math.PI * ((t * n) % 1d));
            if (t >= 1d || a <= 0f) {
                return Picture.EMPTY;
            }
            float width = Math.max(1.5f, box.minSide() * 0.06f);
            return Picture.of(new Picture.Outline(
                    0f, 0f, box.w(), box.h(), box.cornerTopPx(), box.cornerBottomPx(), width,
                    Color.withAlpha(color, color.a() * a), "cue-ring"));
        };
    }

    /** Fast up over {@code attack}, slow down over the rest; 0 at both ends. */
    private static float envelope(float t, float attack) {
        float a = Math.min(0.99f, Math.max(0.01f, attack));
        if (t <= 0f || t >= 1f) {
            return 0f;
        }
        return t < a ? t / a : 1f - (t - a) / (1f - a);
    }
}
