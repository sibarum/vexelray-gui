package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.InteractionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Theme#map}: a role put somewhere else, at rest and under the pointer, with every other decision the theme made
 * kept, and combined with the other derivations in either order (IDE FN-3: four colours used to take a thirteen-method
 * forwarding theme, and one forgotten forward dropped a decision silently).
 */
class ThemeMapTest {

    private static final Role QUIET = p -> p.surface(5);
    private static final Role LOUD = p -> p.surface(9);

    @Test
    void aMappedRoleIsDrawnAsItsTargetAtRestAndInEveryState() {
        Theme mapped = Theme.DARK.map(Role.GRIP, QUIET);
        assertEquals(Theme.DARK.color(QUIET), mapped.color(Role.GRIP));
        for (InteractionState state : InteractionState.values()) {
            assertEquals(Theme.DARK.color(QUIET, state), mapped.color(Role.GRIP, state), state.name());
        }
        assertEquals(Theme.DARK.color(Role.ACCENT), mapped.color(Role.ACCENT), "a role not mapped is unchanged");
    }

    @Test
    void everyOtherDecisionIsKept() {
        FocusRing ring = FocusRing.STANDARD.width(dev.vexelray.gui.core.layout.Length.rem(0.3f));
        Theme base = Theme.LIGHT.focusRing(ring).icons(IconEffects.STANDARD);
        Theme mapped = base.map(Role.LINE, QUIET);
        assertSame(base.palette(), mapped.palette());
        assertSame(base.shading(), mapped.shading());
        assertSame(base.relief(), mapped.relief());
        assertEquals(base.lit(), mapped.lit());
        assertEquals(base.letterpress(), mapped.letterpress());
        assertSame(ring, mapped.focusRing());
        assertSame(base.icons(), mapped.icons());
        assertEquals(base.elevation(2), mapped.elevation(2));
        assertEquals(base.elevation(2, InteractionState.HOVER), mapped.elevation(2, InteractionState.HOVER));
    }

    @Test
    void aRingOrIconsChosenAfterAMapKeepTheMap() {
        FocusRing ring = FocusRing.STANDARD.width(dev.vexelray.gui.core.layout.Length.rem(0.3f));
        Theme t = Theme.DARK.map(Role.GRIP, QUIET).focusRing(ring).icons(IconEffects.STANDARD);
        assertEquals(Theme.DARK.color(QUIET), t.color(Role.GRIP));
        assertSame(ring, t.focusRing());
    }

    @Test
    void mapsCombineAndTheLaterOfTwoForOneRoleWins() {
        Theme t = Theme.DARK.map(Role.GRIP, QUIET).map(Role.LINE, LOUD).map(Role.GRIP, LOUD);
        assertEquals(Theme.DARK.color(LOUD), t.color(Role.GRIP));
        assertEquals(Theme.DARK.color(LOUD), t.color(Role.LINE));
    }

    @Test
    void aRuleMapsLikeAList() {
        Theme t = Theme.DARK.map(r -> r == Role.WELL ? Role.PAGE : r);
        assertEquals(Theme.DARK.color(Role.PAGE), t.color(Role.WELL));
    }

    @Test
    void twoColoursAtNoAlphaLookTheSame() {
        Color faded = Color.withAlpha(Theme.DARK.color(Role.PANEL), 0f);
        assertTrue(PaintMotion.same(faded, Color.TRANSPARENT), "a colour faded out is Role.NONE");
        assertFalse(PaintMotion.same(Theme.DARK.color(Role.PANEL), Color.TRANSPARENT));
        assertFalse(PaintMotion.same(Color.withAlpha(Theme.DARK.color(Role.PANEL), 0.01f), Color.TRANSPARENT));
    }
}
