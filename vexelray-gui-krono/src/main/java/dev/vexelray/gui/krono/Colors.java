package dev.vexelray.gui.krono;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import sibarum.kronometer.Interp;

/**
 * Interpolation for {@link Color}, perceptually.
 *
 * <p>The other half of what Kronometer deliberately left here: a colour space is a decision about a
 * domain, not about time.
 *
 * <h2>Why not sRGB</h2>
 *
 * The failure that matters is <b>hue</b>, not lightness. Blending sRGB components takes blue to yellow
 * straight through a desaturated grey, because the complementary pair cancels on the way — and the same
 * happens to any near-complementary transition. It reads as the colour draining out and coming back.
 *
 * <p>{@link #OKLAB} converts to Oklab — a perceptually uniform space designed for exactly this — blends
 * there, and converts back. Equal steps in alpha then look like equal steps of colour, which is what
 * makes an eased colour animation read correctly: otherwise the ease you chose is fighting the
 * distortion of the space.
 *
 * <p>Two pieces of folklore worth contradicting, both pinned down by this module's tests:
 *
 * <ul>
 *   <li><b>"sRGB blending makes greys too dark."</b> Not really — sRGB's transfer curve is already
 *       roughly perceptual, so a greyscale ramp blended naively lands near the eye's midpoint more or
 *       less by accident. That is precisely why the bug goes unnoticed until something saturated is
 *       involved.</li>
 *   <li><b>"Blend in linear light instead."</b> Right for compositing, wrong for interpolation. Linear
 *       light is how light adds, not how lightness is perceived: the linear midpoint of black and white
 *       encodes to 0.735, which reads as nearly white. {@link #LINEAR_RGB} is here for the compositing
 *       case and as an honest middle ground — not as the answer.</li>
 * </ul>
 *
 * {@link #SRGB} is provided only for matching an existing look that was authored against a naive blend.
 *
 * <p>Alpha is always blended linearly, in every mode. Opacity is a coverage fraction rather than a
 * colour, so there is nothing perceptual to correct.
 */
public final class Colors {

    private Colors() {
    }

    /** Perceptually uniform. The one to use unless you have a specific reason not to. */
    public static final Interp<Color> OKLAB = (from, to, alpha) -> Oklab.of(from)
            .mix(Oklab.of(to), alpha)
            .toColor((float) mix(from.a(), to.a(), alpha));

    /** Blends in linear light: right for compositing, too light for a perceptual ramp. See the class note. */
    public static final Interp<Color> LINEAR_RGB = (from, to, alpha) -> new Color(
            (float) linearToSrgb(mix(srgbToLinear(from.r()), srgbToLinear(to.r()), alpha)),
            (float) linearToSrgb(mix(srgbToLinear(from.g()), srgbToLinear(to.g()), alpha)),
            (float) linearToSrgb(mix(srgbToLinear(from.b()), srgbToLinear(to.b()), alpha)),
            (float) mix(from.a(), to.a(), alpha));

    /** Naive component blending. Here for matching a look authored against it, not for new work. */
    public static final Interp<Color> SRGB = (from, to, alpha) -> new Color(
            (float) mix(from.r(), to.r(), alpha),
            (float) mix(from.g(), to.g(), alpha),
            (float) mix(from.b(), to.b(), alpha),
            (float) mix(from.a(), to.a(), alpha));

    // -------------------------------------------------------------- internals
    // The colour space itself lives in gui-core (dev.vexelray.gui.core.style.Oklab), because the theme derives a
    // whole palette in it and a framework cannot have two answers for what a colour is. This module owns the
    // *interpolation* — which space to blend in, which is a decision about a domain, not about the space.

    private static double mix(double a, double b, double alpha) {
        return a + (b - a) * alpha;
    }

    private static double srgbToLinear(double c) {
        return Oklab.srgbToLinear(c);
    }

    private static double linearToSrgb(double c) {
        return Oklab.linearToSrgb(c);
    }
}
