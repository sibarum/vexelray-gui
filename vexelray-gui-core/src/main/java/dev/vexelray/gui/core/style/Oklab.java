package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;

/**
 * A colour in Oklab — a perceptually uniform space, where equal steps of {@code l} read as equal steps of
 * lightness and a hue survives being moved. This is the space a {@link Palette} is authored and derived in: the
 * whole point of a theme is that "one step lighter" and "the same relationship, inverted" are the same size
 * everywhere, and sRGB components cannot promise that.
 *
 * <p>Two things about the geometry, both used by {@link Palette}:
 *
 * <ul>
 *   <li><b>Lightness is a coordinate, not a multiplier.</b> {@code l} runs 0 (black) to 1 (white), so a surface
 *       ladder is addition and a contrast requirement is a difference — see {@link #stepToward}.</li>
 *   <li><b>Chroma travels with lightness.</b> {@link #atLightness} scales {@code a} and {@code b} in proportion,
 *       because a grey ladder that holds its chroma constant goes muddy as it rises and neon as it falls. The
 *       ratio is the tint; the tint is what makes a family of greys look like one family.</li>
 * </ul>
 *
 * <p>Conversion is sRGB &harr; linear light &harr; LMS cone response &harr; Oklab, clamped on the way out, so a
 * derived colour that leaves the sRGB gamut lands on its edge rather than wrapping.
 *
 * @param l perceptual lightness, 0..1
 * @param a green(&minus;)/red(+) axis
 * @param b blue(&minus;)/yellow(+) axis
 */
public record Oklab(double l, double a, double b) {

    /**
     * Author a colour the way a palette is actually decided: a lightness, how much colour it carries, and which
     * colour that is. {@code hueDegrees} is the angle in the {@code a}/{@code b} plane.
     */
    public static Oklab polar(double l, double chroma, double hueDegrees) {
        double radians = Math.toRadians(hueDegrees);
        return new Oklab(l, chroma * Math.cos(radians), chroma * Math.sin(radians));
    }

    /** Measure an existing sRGB colour. Alpha is not a colour and is dropped. */
    public static Oklab of(Color c) {
        double r = srgbToLinear(c.r());
        double g = srgbToLinear(c.g());
        double b = srgbToLinear(c.b());

        double lIn = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
        double mIn = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
        double sIn = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

        double lc = Math.cbrt(lIn);
        double mc = Math.cbrt(mIn);
        double sc = Math.cbrt(sIn);

        return new Oklab(
                0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
                1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
                0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc);
    }

    /** How much colour this carries — the distance from the neutral axis. */
    public double chroma() {
        return Math.hypot(a, b);
    }

    /** Which colour that is, as an angle in degrees. Meaningless at {@link #chroma()} zero. */
    public double hueDegrees() {
        return Math.toDegrees(Math.atan2(b, a));
    }

    /**
     * The same hue at a different lightness, with chroma scaled in proportion (see the class note). Lightness is
     * clamped to 0..1, so a ladder that runs off the end saturates at black or white instead of inverting.
     */
    public Oklab atLightness(double lightness) {
        double target = Math.clamp(lightness, 0.0, 1.0);
        double scale = l <= 1e-6 ? 0.0 : target / l;
        return new Oklab(target, a * scale, b * scale);
    }

    /**
     * Move {@code distance} of lightness <b>towards {@code target}</b>, keeping this colour's own hue. A negative
     * distance moves away from it.
     *
     * <p>This is the one operation that makes a palette polarity-free. "A panel is two steps up from the page" is
     * a dark-theme fact; "a panel is two steps from the page <em>towards the ink</em>" is true of both, because
     * the ink is above the page in a dark theme and below it in a light one. A theme author states the size of a
     * step and never states a direction.
     */
    public Oklab stepToward(Oklab target, double distance) {
        return atLightness(l + Math.signum(target.l - l) * distance);
    }

    /** Blend towards {@code other}: {@code 0} is this colour, {@code 1} is {@code other}. */
    public Oklab mix(Oklab other, double t) {
        return new Oklab(l + (other.l - l) * t, a + (other.a - a) * t, b + (other.b - b) * t);
    }

    /** Back to an opaque sRGB colour. */
    public Color toColor() {
        return toColor(1f);
    }

    /** Back to an sRGB colour at {@code alpha}. Out-of-gamut components clamp rather than wrap. */
    public Color toColor(float alpha) {
        double lc = l + 0.3963377774 * a + 0.2158037573 * b;
        double mc = l - 0.1055613458 * a - 0.0638541728 * b;
        double sc = l - 0.0894841775 * a - 1.2914855480 * b;

        double lIn = lc * lc * lc;
        double mIn = mc * mc * mc;
        double sIn = sc * sc * sc;

        return new Color(
                (float) linearToSrgb(4.0767416621 * lIn - 3.3077115913 * mIn + 0.2309699292 * sIn),
                (float) linearToSrgb(-1.2684380046 * lIn + 2.6097574011 * mIn - 0.3413193965 * sIn),
                (float) linearToSrgb(-0.0041960863 * lIn - 0.7034186147 * mIn + 1.7076147010 * sIn),
                alpha);
    }

    /** sRGB transfer curve, decoded. Public because compositing and interpolation both need it by name. */
    public static double srgbToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** sRGB transfer curve, encoded, clamped to the displayable range. */
    public static double linearToSrgb(double c) {
        double v = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return Math.clamp(v, 0.0, 1.0);
    }
}
