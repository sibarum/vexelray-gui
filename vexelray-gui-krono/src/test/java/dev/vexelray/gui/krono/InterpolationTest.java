package dev.vexelray.gui.krono;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two interpolations Kronometer deliberately left to this repo, because they are decisions about
 * this repo's types.
 */
class InterpolationTest {

    // -------------------------------------------------------------- lengths

    @Test
    @DisplayName("same-unit lengths blend")
    void sameUnitsBlend() {
        assertEquals(new Length.Dp(6), Lengths.LERP.between(new Length.Dp(2), new Length.Dp(10), 0.5f));
        assertEquals(new Length.Rem(1.5f),
                Lengths.LERP.between(new Length.Rem(1), new Length.Rem(2), 0.5f));
        assertEquals(new Length.Percent(75),
                Lengths.LERP.between(new Length.Percent(50), new Length.Percent(100), 0.5f));
        assertEquals(new Length.Grow(2), Lengths.LERP.between(new Length.Grow(1), new Length.Grow(3), 0.5f));
    }

    @Test
    @DisplayName("endpoints are the inputs exactly")
    void lengthEndpointsAreExact() {
        Length from = new Length.Dp(2);
        Length to = new Length.Dp(10);
        assertEquals(from, Lengths.LERP.between(from, to, 0f));
        assertEquals(to, Lengths.LERP.between(from, to, 1f));
    }

    @Test
    @DisplayName("mixed units step rather than inventing a number")
    void mixedUnitsStep() {
        Length dp = new Length.Dp(8);
        Length rem = new Length.Rem(1);
        // There is no scalar between these: rem scales with zoom and dp deliberately does not, so any
        // blended value would resolve to something matching neither endpoint at either end. Holding and
        // switching is a discontinuity you can see, which beats a smooth curve through nonsense.
        assertEquals(dp, Lengths.LERP.between(dp, rem, 0f));
        assertEquals(dp, Lengths.LERP.between(dp, rem, 0.5f));
        assertEquals(dp, Lengths.LERP.between(dp, rem, 0.999f));
        assertEquals(rem, Lengths.LERP.between(dp, rem, 1f));

        assertTrue(Lengths.areBlendable(dp, new Length.Dp(2)));
        assertFalse(Lengths.areBlendable(dp, rem));
    }

    @Test
    @DisplayName("scalarless units have nothing to move, even against themselves")
    void scalarlessUnitsStep() {
        assertFalse(Lengths.areBlendable(new Length.Auto(), new Length.Auto()));
        assertEquals(new Length.Auto(),
                Lengths.LERP.between(new Length.Auto(), new Length.Auto(), 0.5f));
        assertEquals(new Length.FillT(),
                Lengths.LERP.between(new Length.Auto(), new Length.FillT(), 1f));
    }

    // --------------------------------------------------------------- colours

    @Test
    @DisplayName("colour endpoints round-trip through Oklab")
    void colourEndpointsRoundTrip() {
        Color from = Color.rgb(0.2f, 0.4f, 0.8f);
        Color to = Color.rgb(0.9f, 0.1f, 0.3f);

        assertColourEquals(from, Colors.OKLAB.between(from, to, 0f), 1e-3f);
        assertColourEquals(to, Colors.OKLAB.between(from, to, 1f), 1e-3f);
    }

    @Test
    @DisplayName("on greys the three modes disagree, and linear light is the outlier")
    void greyExposesTheRealTrap() {
        Color oklab = Colors.OKLAB.between(Color.BLACK, Color.WHITE, 0.5f);
        Color linear = Colors.LINEAR_RGB.between(Color.BLACK, Color.WHITE, 0.5f);
        Color srgb = Colors.SRGB.between(Color.BLACK, Color.WHITE, 0.5f);

        // Worth being precise about, because the folklore here is misleading in both directions.
        //
        // sRGB's transfer curve is *already* roughly perceptual, so naive component blending lands near
        // the eye's midpoint by accident — which is why nobody notices it is wrong until they blend
        // something saturated. Blending in *linear light* is the advice usually given instead, and for a
        // greyscale ramp it is the one that goes visibly wrong: linear 0.5 encodes to 0.735, a midpoint
        // that reads as almost white. Oklab is a little darker than sRGB and is the principled answer.
        assertEquals(0.5f, srgb.r(), 1e-6f);
        assertEquals(0.735f, linear.r(), 1e-3f);
        assertEquals(0.389f, oklab.r(), 1e-3f);

        assertTrue(linear.r() > srgb.r(), "linear light is the lightest, and looks it");
        assertTrue(oklab.r() < srgb.r(), "Oklab puts middle grey below the encoded midpoint");
    }

    @Test
    @DisplayName("blue to yellow does not pass through grey")
    void hueSweepStaysSaturated() {
        Color blue = Color.rgb(0f, 0f, 1f);
        Color yellow = Color.rgb(1f, 1f, 0f);

        Color oklabMid = Colors.OKLAB.between(blue, yellow, 0.5f);
        Color srgbMid = Colors.SRGB.between(blue, yellow, 0.5f);

        // sRGB's midpoint is (0.5, 0.5, 0.5) — literally grey, the complementary pair having cancelled.
        assertTrue(saturation(srgbMid) < 0.01f, "sRGB midpoint should be grey, got " + srgbMid);
        assertTrue(saturation(oklabMid) > 0.15f,
                "Oklab should keep some chroma through the sweep, got " + oklabMid);
    }

    @Test
    @DisplayName("alpha blends linearly in every mode, because coverage is not a colour")
    void alphaIsAlwaysLinear() {
        Color from = Color.rgba(1f, 0f, 0f, 0f);
        Color to = Color.rgba(0f, 0f, 1f, 1f);

        assertEquals(0.25f, Colors.OKLAB.between(from, to, 0.25f).a(), 1e-6f);
        assertEquals(0.25f, Colors.LINEAR_RGB.between(from, to, 0.25f).a(), 1e-6f);
        assertEquals(0.25f, Colors.SRGB.between(from, to, 0.25f).a(), 1e-6f);
    }

    @Test
    @DisplayName("every mode stays inside the gamut")
    void resultsAreClamped() {
        Color from = Color.rgb(0f, 0f, 0f);
        Color to = Color.rgb(1f, 1f, 1f);
        for (int i = 0; i <= 20; i++) {
            float alpha = i / 20f;
            for (var interp : java.util.List.of(Colors.OKLAB, Colors.LINEAR_RGB, Colors.SRGB)) {
                Color c = interp.between(from, to, alpha);
                assertTrue(c.r() >= 0 && c.r() <= 1, "r out of gamut at " + alpha + ": " + c);
                assertTrue(c.g() >= 0 && c.g() <= 1, "g out of gamut at " + alpha + ": " + c);
                assertTrue(c.b() >= 0 && c.b() <= 1, "b out of gamut at " + alpha + ": " + c);
            }
        }
    }

    private static float saturation(Color c) {
        float max = Math.max(c.r(), Math.max(c.g(), c.b()));
        float min = Math.min(c.r(), Math.min(c.g(), c.b()));
        return max - min;
    }

    private static void assertColourEquals(Color expected, Color actual, float tolerance) {
        assertEquals(expected.r(), actual.r(), tolerance, "red");
        assertEquals(expected.g(), actual.g(), tolerance, "green");
        assertEquals(expected.b(), actual.b(), tolerance, "blue");
        assertEquals(expected.a(), actual.a(), tolerance, "alpha");
    }
}
