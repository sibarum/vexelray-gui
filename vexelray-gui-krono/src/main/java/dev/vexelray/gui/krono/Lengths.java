package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.layout.Length;
import sibarum.kronometer.Interp;

/**
 * Interpolation for {@link Length}.
 *
 * <p>Kronometer deliberately ships no {@code Interp<Length>} — a length is this repo's type, so blending
 * one is this repo's decision. This is that decision.
 *
 * <h2>Mixed units do not interpolate, and that is not a limitation</h2>
 *
 * A {@code Length} is not a number, it is a number plus a basis it resolves against, and the bases are
 * genuinely different: {@code rem} scales with zoom, {@code dp} deliberately does not, {@code percent}
 * and {@code vw} resolve against boxes that are not known until layout runs. So there is no scalar to
 * lerp between {@code dp(8)} and {@code rem(1)} — any number you produced would resolve to something
 * that matched neither endpoint at either end.
 *
 * <p>Rather than invent one, a mixed-unit blend <b>holds the start value and switches at the end</b>. It
 * is the same choice {@link Interp#step()} makes, for the same reason: a discontinuity you can see beats
 * a smooth curve through meaningless values. If you want a smooth transition, animate within one unit —
 * which is also the advice that keeps a zooming UI coherent.
 *
 * <p>{@link Length.Auto} and {@link Length.FillT} carry no scalar at all, so they behave the same way
 * even against themselves: there is nothing in them to move.
 */
public final class Lengths {

    private Lengths() {
    }

    /**
     * Blend two lengths of the same unit; step between different ones.
     *
     * <p>Alpha is used as given — the caller has already applied any {@link sibarum.kronometer.anim.Ease}
     * and clamped, which is what keeps easing and interpolation separable.
     */
    public static final Interp<Length> LERP = (from, to, alpha) -> switch (from) {
        case Length.Em a when to instanceof Length.Em b -> new Length.Em(mix(a.v(), b.v(), alpha));
        case Length.Rem a when to instanceof Length.Rem b -> new Length.Rem(mix(a.v(), b.v(), alpha));
        case Length.Dp a when to instanceof Length.Dp b -> new Length.Dp(mix(a.v(), b.v(), alpha));
        case Length.Percent a when to instanceof Length.Percent b ->
                new Length.Percent(mix(a.v(), b.v(), alpha));
        case Length.Vw a when to instanceof Length.Vw b -> new Length.Vw(mix(a.v(), b.v(), alpha));
        case Length.Vh a when to instanceof Length.Vh b -> new Length.Vh(mix(a.v(), b.v(), alpha));
        case Length.Grow a when to instanceof Length.Grow b ->
                new Length.Grow(mix(a.factor(), b.factor(), alpha));
        // Mixed units, or a unit with no scalar. Hold, then switch — see the class note.
        default -> alpha < 1f ? from : to;
    };

    /** Whether these two lengths can be blended, or will merely step. Useful for a diagnostic. */
    public static boolean areBlendable(Length from, Length to) {
        return from.getClass() == to.getClass()
                && !(from instanceof Length.Auto)
                && !(from instanceof Length.FillT);
    }

    private static float mix(float a, float b, float alpha) {
        return a + (b - a) * alpha;
    }
}
