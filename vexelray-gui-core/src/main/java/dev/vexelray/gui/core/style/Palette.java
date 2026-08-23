package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;

/**
 * The anchors a whole look is derived from: two ladders and four inks. Everything a widget paints resolves to
 * {@link #surface(int)}, {@link #text(int)} or one of the chromatic anchors — there is no other colour anywhere in
 * the framework.
 *
 * <h2>Why ladders rather than a list of colours</h2>
 * The palette this replaced was fifteen hand-picked hex values spread over eight files, and measuring it in Oklab
 * showed it had been a formula all along: every grey in it sat on one hue (&minus;93&deg;), carried chroma in a
 * near-constant ratio to its lightness (0.10&ndash;0.12), and landed within 0.008 of an evenly spaced ladder of
 * 0.033 lightness off the page. Nine authored numbers reproduce what fifteen literals said, and — unlike the
 * literals — they can be inverted, contrast-checked, and stepped without a designer in the loop.
 *
 * <h2>The surface ladder</h2>
 * {@link #surface(int)} counts steps from the page <b>towards the ink</b> (see {@link Oklab#stepToward}), which is
 * what lets one set of roles serve a dark and a light theme: level 4 is a border that reads at the same perceptual
 * contrast either way, without the theme ever declaring which direction "up" is. Level 0 is the page; negative
 * levels go the other way, which is how a sunken input well is darker than a dark page and whiter than a white
 * one.
 *
 * <h2>The ink ladder</h2>
 * {@link #text(int)} fades towards the page by the same <em>fraction of what is left</em> each step, so secondary
 * text is a relationship rather than a colour: {@code text(1)} is "a third of the way to invisible", and it stays
 * legible when the page moves under it.
 *
 * <p>One honest difference from the palette this replaced: its secondary ink was hand-tinted <em>cooler than
 * either anchor it sat between</em> (chroma 0.033, against 0.009 for the ink and 0.022 for the page), which no fade
 * between the two can produce. The derived one keeps that value's lightness and takes the family's own chroma, so
 * it reads a touch more neutral — about 13/255 on one channel, and the only visible drift in the whole migration.
 *
 * @param page         the window background: the anchor both ladders are measured from
 * @param step         lightness per level of the surface ladder
 * @param ink          full-strength text
 * @param fade         fraction of the remaining distance to the page that each ink level gives up
 * @param accent       the bright chromatic anchor: focus, selection, links, a slider thumb
 * @param action       the fill of a filled control — deeper than {@code accent}, so a button sits into the page
 *                     rather than glowing on it (and the letterpress glint has somewhere darker to catch)
 * @param danger       destructive: a close button's hover, a delete item
 * @param depth        the ink shadows and scrims are drawn in
 * @param shadowAlpha  how opaque an elevation shadow is — the honest difference between a dark theme, where depth
 *                     is a deep shadow, and a light one, where the same depth is a hint
 */
public record Palette(Oklab page, double step, Oklab ink, double fade,
                      Oklab accent, Oklab action, Oklab danger,
                      Oklab depth, double shadowAlpha) {

    /**
     * The framework's original look, re-derived. Measured from the palette it replaces: page {@code #11141b},
     * ladder step 0.033 (which lands on the authored panel, hover, border and thumb values), ink {@code #eef2f8}
     * fading a third at a time (giving {@code #93a0b4} at level 1), accent {@code #3aa0ff}, control fill
     * {@code #2668b3}, danger {@code #c4353b}.
     */
    public static final Palette DARK = new Palette(
            Oklab.polar(0.191, 0.022, -93),
            0.033,
            Oklab.polar(0.960, 0.009, -102),
            0.338,
            Oklab.polar(0.6925, 0.168, -110),
            Oklab.polar(0.515, 0.136, -106),
            Oklab.polar(0.548, 0.180, 23),
            Oklab.polar(0.128, 0.013, -96),
            0.55);

    /**
     * The same relationships, inverted: a near-white page with the ink below it, so every surface level is a step
     * <em>down</em> in lightness and a border keeps the perceptual contrast it had in {@link #DARK}. The step is a
     * little smaller and the shadows much lighter, which is the one place the two are not mirror images — light
     * surfaces show depth through shadow, not through lightness.
     */
    public static final Palette LIGHT = new Palette(
            Oklab.polar(0.975, 0.006, -93),
            0.030,
            Oklab.polar(0.250, 0.020, -95),
            0.320,
            Oklab.polar(0.560, 0.170, -110),
            Oklab.polar(0.520, 0.150, -108),
            Oklab.polar(0.520, 0.190, 23),
            Oklab.polar(0.200, 0.010, -96),
            0.18);

    /**
     * Level {@code n} of the surface ladder: {@code n} steps from the page towards the ink. {@code 0} is the page
     * itself, negatives run the other way (a sunken well), and the levels the framework uses by name are listed on
     * {@link Role}.
     */
    public Color surface(int n) {
        return page.stepToward(ink, n * step).toColor();
    }

    /**
     * The label colour for something painted in {@code fill}: whichever of the palette's two extremes — the ink
     * or the page — lies further from it in lightness.
     *
     * <p>A filled control has to contrast with its <b>fill</b>, not with the page, and its fill sits in the middle
     * of the range in every palette. In a dark theme the far end is the ink (so the label is near-white); in a
     * light theme the ink is the <em>near</em> end and the page is the far one (so the label is near-white again).
     * Stating the rule this way is what makes "white on the accent" survive an inversion — asking for the ink
     * outright would put dark text on a saturated blue the moment the page turned white.
     */
    public Color contrastTo(Oklab fill) {
        return Math.abs(ink.l() - fill.l()) >= Math.abs(page.l() - fill.l()) ? ink.toColor() : page.toColor();
    }

    /**
     * Level {@code n} of the ink ladder: full-strength text at {@code 0}, then each step giving up {@link #fade}
     * of the distance that is left to the page.
     */
    public Color text(int n) {
        return ink.mix(page, 1 - Math.pow(1 - fade, Math.max(0, n))).toColor();
    }
}
