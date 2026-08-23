package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.input.InteractionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The theme's claims, in the order they matter: that nine authored numbers reproduce the fifteen literals they
 * replaced, that a level means the same amount of contrast whichever way the palette runs, and that resolution
 * never needs to enumerate the roles.
 */
class ThemeTest {

    /** Lightness tolerance when comparing a derived colour to the hand-picked one it stands in for. */
    private static final double L_TOLERANCE = 0.012;

    @Test
    void theDerivedLadderReproducesTheHandPickedPalette() {
        // Left: what the widgets used to hard-code. Right: what the ladders now derive. The point of the test is
        // that the old palette was a formula — if a future edit to the anchors breaks that, this says so.
        assertLightness(0x11141b, Theme.DARK.color(Role.PAGE), "the page");
        assertLightness(0x161b28, Theme.DARK.color(Role.CHROME), "the tab bar");
        assertLightness(0x1b2130, Theme.DARK.color(Role.PANEL), "a panel");
        assertLightness(0x232a3d, Theme.DARK.color(Role.RAISED), "a tooltip bubble");
        assertLightness(0x2b3346, Theme.DARK.color(Role.LINE), "a border");
        assertLightness(0x3a445c, Theme.DARK.color(Role.EDGE), "a tooltip edge");
        assertLightness(0x5a6685, Theme.DARK.color(Role.GRIP), "a scrollbar thumb");
        assertLightness(0xeef2f8, Theme.DARK.color(Role.INK), "primary text");
        assertLightness(0x93a0b4, Theme.DARK.color(Role.DIM), "secondary text");
        assertLightness(0x3aa0ff, Theme.DARK.color(Role.ACCENT), "the accent");
        assertLightness(0x2668b3, Theme.DARK.color(Role.ACTION), "a filled control");
    }

    @Test
    void everyGreyInTheLadderShareOneHue() {
        // The family resemblance the old palette had by hand: one hue, chroma rising with lightness. A ladder that
        // drifted in hue would read as a different grey at each level, which is what makes hand-picked greys muddy.
        double hue = Oklab.of(Theme.DARK.color(Role.PAGE)).hueDegrees();
        for (int level = -1; level <= 10; level++) {
            Oklab step = Oklab.of(Theme.DARK.palette().surface(level));
            assertEquals(hue, step.hueDegrees(), 1.0, "level " + level + " left the family's hue");
        }
    }

    @Test
    void aLevelIsTheSameContrastWhicheverWayThePaletteRuns() {
        // The invariant that lets one set of roles serve both looks: a border is a border's worth of contrast away
        // from the page in each, and neither theme states a direction (Oklab.stepToward derives it from the ink).
        double dark = contrast(Theme.DARK, Role.PAGE, Role.LINE);
        double light = contrast(Theme.LIGHT, Role.PAGE, Role.LINE);
        assertEquals(dark, light, 0.02, "a border's contrast should not depend on the polarity of the theme");
        assertTrue(dark > 0.1, "and it has to be visible at all: " + dark);

        // ... running in opposite directions, which is the whole trick.
        assertTrue(lightness(Theme.DARK.color(Role.LINE)) > lightness(Theme.DARK.color(Role.PAGE)),
                "a dark theme's levels climb");
        assertTrue(lightness(Theme.LIGHT.color(Role.LINE)) < lightness(Theme.LIGHT.color(Role.PAGE)),
                "a light theme's levels descend");
    }

    @Test
    void aWellSinksAwayFromTheInkInBothThemes() {
        // Negative levels run the other way, so a sunken input is darker than a dark page and whiter than a white
        // one -- one declaration, opposite pixels.
        assertTrue(lightness(Theme.DARK.color(Role.WELL)) < lightness(Theme.DARK.color(Role.PAGE)));
        assertTrue(lightness(Theme.LIGHT.color(Role.WELL)) > lightness(Theme.LIGHT.color(Role.PAGE)));
    }

    @Test
    void textStaysLegibleAgainstItsPage() {
        for (Theme theme : List.of(Theme.DARK, Theme.LIGHT)) {
            assertTrue(contrast(theme, Role.PAGE, Role.INK) > 0.5,
                    "primary text must be far from the page: " + contrast(theme, Role.PAGE, Role.INK));
            assertTrue(contrast(theme, Role.PAGE, Role.DIM) > 0.25,
                    "and secondary text still readable: " + contrast(theme, Role.PAGE, Role.DIM));
            assertTrue(contrast(theme, Role.DIM, Role.INK) > 0.15,
                    "the two must not collapse into each other");
        }
    }

    @Test
    void aLabelOnAFilledControlIsLegibleInBothPolarities() {
        // The trap this role exists for: "white on the accent" migrated as Role.INK looks right in a dark theme
        // and puts near-black text on a saturated blue the moment the page turns white.
        for (Theme theme : List.of(Theme.DARK, Theme.LIGHT)) {
            assertTrue(contrast(theme, Role.ACTION, Role.ON_ACTION) > 0.4,
                    "a filled control's label must contrast with its fill, not with the page: "
                            + contrast(theme, Role.ACTION, Role.ON_ACTION));
            assertTrue(contrast(theme, Role.DANGER, Role.ON_DANGER) > 0.3);
        }
        // And it resolves to the *other* extreme in each, which is the whole point.
        assertTrue(lightness(Theme.DARK.color(Role.ON_ACTION)) > 0.9);
        assertTrue(lightness(Theme.LIGHT.color(Role.ON_ACTION)) > 0.9);
    }

    @Test
    void hoverAndPressShiftWhateverTheControlAlreadyIs() {
        // One step, applied to the resolved colour -- not a second colour per role. Both roles below shift by the
        // same amount, which is what the four hand-picked *_HOVER constants were approximating.
        for (Role role : List.of(Role.PANEL, Role.ACTION)) {
            double rest = lightness(Theme.DARK.color(role));
            assertEquals(rest + 0.040, lightness(Theme.DARK.color(role, InteractionState.HOVER)), 1e-6);
            assertEquals(rest - 0.050, lightness(Theme.DARK.color(role, InteractionState.PRESSED)), 1e-6);
            // A light palette shades the other way: on white, "brighter" is not an affordance.
            double lit = lightness(Theme.LIGHT.color(role));
            assertTrue(lightness(Theme.LIGHT.color(role, InteractionState.HOVER)) < lit);
        }
    }

    @Test
    void aStateTheTableSaysNothingAboutLeavesTheColourAlone() {
        // Shading is a table, not a branch: NORMAL is absent rather than a case, and a state added to
        // InteractionState tomorrow needs no edit here to behave.
        Shading silent = new Shading(Map.of());
        Color panel = Theme.DARK.color(Role.PANEL);
        for (InteractionState state : InteractionState.values()) {
            assertEquals(panel, silent.apply(panel, state));
        }
        assertSame(panel, Theme.DARK.shading().apply(panel, InteractionState.NORMAL));
    }

    @Test
    void nothingPaintedStaysNothingPainted() {
        // A control that is transparent at rest must not acquire a tinted rectangle on hover -- it either shows a
        // surface role or shows nothing.
        Color none = Theme.DARK.color(Role.NONE);
        assertEquals(0f, none.a());
        assertEquals(none, Theme.DARK.color(Role.NONE, InteractionState.HOVER));
    }

    @Test
    void depthIsAThemeDecisionRatherThanAWidgetOne() {
        // The same lit fill that gives a dark panel its glint washes a light one out, so the effect switches with
        // the look and not with the widget; the shadow ink follows the same way.
        assertTrue(Theme.DARK.lit());
        assertTrue(Theme.DARK.letterpress());
        assertTrue(Theme.DARK.color(Role.SHADOW).a() > Theme.LIGHT.color(Role.SHADOW).a(),
                "a light theme shows depth as a hint, not a hole");
        assertTrue(lightness(Theme.LIGHT.color(Role.SCRIM)) < 0.35,
                "a veil is dark in every palette -- it is not a surface");
    }

    @Test
    void anApplicationCanNameARoleTheFrameworkNeverHeardOf() {
        // The reason resolution is a function and not a lookup table: this needs no registration, no enum entry,
        // and no case added to a switch, and it themes with everything else.
        Role unsavedTab = p -> p.surface(3);
        assertEquals(Theme.DARK.color(Role.RAISED), unsavedTab.of(Palette.DARK));
        assertNotEquals(unsavedTab.of(Palette.DARK), unsavedTab.of(Palette.LIGHT));
    }

    @Test
    void oklabRoundTripsThroughSrgb() {
        for (int hex : new int[]{0x000000, 0xffffff, 0x11141b, 0x3aa0ff, 0xc4353b}) {
            Color original = Color.rgb(hex);
            Color back = Oklab.of(original).toColor();
            assertEquals(original.r(), back.r(), 1e-3f);
            assertEquals(original.g(), back.g(), 1e-3f);
            assertEquals(original.b(), back.b(), 1e-3f);
        }
    }

    @Test
    void aLadderThatRunsOffTheEndSaturatesInsteadOfWrapping() {
        // Forty levels away from the ink on a near-white page has nowhere left to go; it must pile up at white
        // rather than fold over into something dark. Clamping the lightness in Oklab is what stops the inversion.
        Oklab page = Palette.LIGHT.page();
        assertEquals(1.0, page.stepToward(Palette.LIGHT.ink(), -40 * Palette.LIGHT.step()).l(), 1e-9);
        assertEquals(0.0, page.stepToward(Palette.LIGHT.ink(), 40 * Palette.LIGHT.step()).l(), 1e-9);
        assertTrue(lightness(Palette.LIGHT.surface(-40)) >= lightness(Palette.LIGHT.surface(-5)),
                "the ladder must stay monotone right up to the end of the range");
    }

    @Test
    void guiCarriesTheThemeAndDefaultsToTheFrameworkLook() {
        try (Gui gui = new Gui()) {
            assertSame(Theme.DARK, gui.theme());
            gui.theme(Theme.LIGHT);
            assertSame(Theme.LIGHT, gui.theme());
            gui.theme(null);
            assertSame(Theme.DARK, gui.theme(), "clearing the theme returns to the default, never to nothing");
        }
    }

    private static void assertLightness(int authoredHex, Color derived, String what) {
        double authored = lightness(Color.rgb(authoredHex));
        assertEquals(authored, lightness(derived), L_TOLERANCE,
                what + ": derived colour is no longer the one it stands in for");
    }

    private static double contrast(Theme theme, Role a, Role b) {
        return Math.abs(lightness(theme.color(a)) - lightness(theme.color(b)));
    }

    private static double lightness(Color c) {
        return Oklab.of(c).l();
    }
}
