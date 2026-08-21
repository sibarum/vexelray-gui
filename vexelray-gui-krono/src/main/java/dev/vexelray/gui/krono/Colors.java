package dev.vexelray.gui.krono;

import dev.vexelray.canvas.Color;
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
    public static final Interp<Color> OKLAB = (from, to, alpha) -> {
        double[] a = toOklab(from);
        double[] b = toOklab(to);
        return fromOklab(
                mix(a[0], b[0], alpha),
                mix(a[1], b[1], alpha),
                mix(a[2], b[2], alpha),
                (float) mix(from.a(), to.a(), alpha));
    };

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

    private static double mix(double a, double b, double alpha) {
        return a + (b - a) * alpha;
    }

    private static double srgbToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double linearToSrgb(double c) {
        double v = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return Math.clamp(v, 0.0, 1.0);
    }

    /** sRGB to Oklab, via linear light and the LMS cone response. */
    private static double[] toOklab(Color c) {
        double r = srgbToLinear(c.r());
        double g = srgbToLinear(c.g());
        double b = srgbToLinear(c.b());

        double l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
        double m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
        double s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

        double lc = Math.cbrt(l);
        double mc = Math.cbrt(m);
        double sc = Math.cbrt(s);

        return new double[] {
                0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
                1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
                0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc};
    }

    private static Color fromOklab(double bigL, double a, double b, float alpha) {
        double lc = bigL + 0.3963377774 * a + 0.2158037573 * b;
        double mc = bigL - 0.1055613458 * a - 0.0638541728 * b;
        double sc = bigL - 0.0894841775 * a - 1.2914855480 * b;

        double l = lc * lc * lc;
        double m = mc * mc * mc;
        double s = sc * sc * sc;

        return new Color(
                (float) linearToSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                (float) linearToSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                (float) linearToSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
                alpha);
    }
}
