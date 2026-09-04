package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;

/**
 * A colour as hue, saturation and value — the geometry a colour <b>picker</b> is drawn in, sitting alongside
 * {@link Oklab}, which is the space a {@link Palette} is <b>authored</b> in. Two spaces, because they are asked
 * two different questions.
 *
 * <p>Oklab answers "is this one step lighter than that", and a theme is nothing but such comparisons, which is
 * why every ladder in {@link Palette} is built there. It is the wrong space to <em>steer</em> in: a large part of
 * the {@code (l, chroma, hue)} cube is outside sRGB, so a picker drawn on it has regions where every point clamps
 * to the same displayable colour — the pointer moves and nothing changes, and two places on screen are the same
 * answer. HSV has no such region. Every {@code (h, s, v)} with the components in range is a distinct sRGB colour
 * and every sRGB colour is one of them, so a square of it is an honest map of what can actually be chosen.
 *
 * <p><b>Hue is not normalised, and saturation zero keeps it.</b> {@code 360} is left as {@code 360} rather than
 * folded to {@code 0} — the two are the same colour, but they are opposite ends of a picker's hue strip, and a
 * marker that jumps to the far edge when the drag reaches the last pixel is a bug the colour cannot see. For the
 * same reason a caller that is tracking a selection should hold the {@code Hsv} rather than re-derive it from the
 * {@link Color} each time: grey has no hue to recover, so a slide down to black through {@link #of} loses which
 * hue the user was on and comes back red.
 *
 * @param hue        degrees, 0..360 — red at 0, green at 120, blue at 240
 * @param saturation how far from grey, 0..1
 * @param value      how far from black, 0..1
 */
public record Hsv(double hue, double saturation, double value) {

    public Hsv {
        hue = Math.clamp(hue, 0.0, 360.0);
        saturation = Math.clamp(saturation, 0.0, 1.0);
        value = Math.clamp(value, 0.0, 1.0);
    }

    /**
     * Measure an existing sRGB colour. Alpha is not a colour and is dropped; a grey answers {@code hue} 0, which
     * is why a picker holds its own {@link Hsv} rather than round-tripping through this (see the class note).
     */
    public static Hsv of(Color c) {
        double r = Math.clamp(c.r(), 0f, 1f);
        double g = Math.clamp(c.g(), 0f, 1f);
        double b = Math.clamp(c.b(), 0f, 1f);
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double span = max - min;

        double hue;
        if (span <= 0.0) {
            hue = 0.0;
        } else if (max == r) {
            hue = 60.0 * (((g - b) / span) % 6.0);
        } else if (max == g) {
            hue = 60.0 * ((b - r) / span + 2.0);
        } else {
            hue = 60.0 * ((r - g) / span + 4.0);
        }
        return new Hsv(hue < 0.0 ? hue + 360.0 : hue, max <= 0.0 ? 0.0 : span / max, max);
    }

    /** The fully saturated, fully bright colour at this hue — the pure hue, with {@code s} and {@code v} ignored. */
    public Color pureHue() {
        return new Hsv(hue, 1.0, 1.0).toColor();
    }

    /** Back to an opaque sRGB colour. */
    public Color toColor() {
        return toColor(1f);
    }

    /** Back to an sRGB colour at {@code alpha}. */
    public Color toColor(float alpha) {
        double h = (hue % 360.0) / 60.0;
        double chroma = value * saturation;
        double second = chroma * (1.0 - Math.abs((h % 2.0) - 1.0));
        double base = value - chroma;

        double r;
        double g;
        double b;
        switch ((int) h) {
            case 0 -> { r = chroma; g = second; b = 0.0; }
            case 1 -> { r = second; g = chroma; b = 0.0; }
            case 2 -> { r = 0.0; g = chroma; b = second; }
            case 3 -> { r = 0.0; g = second; b = chroma; }
            case 4 -> { r = second; g = 0.0; b = chroma; }
            default -> { r = chroma; g = 0.0; b = second; }
        }
        return new Color((float) (r + base), (float) (g + base), (float) (b + base), alpha);
    }
}
