package dev.vexelray.gui.core.style;

import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relief's claims: that a rung is a ratio rather than a number somebody chose, that the pointer response is one
 * move along the ladder rather than a size per control, and that the ladder is closed at both ends so a widget
 * cannot walk off it.
 */
class ReliefTest {

    private static final float TOLERANCE = 1e-4f;

    @Test
    void everyRungIsTheSameRatioDeeperThanTheOneBelow() {
        Relief r = Relief.STANDARD;
        assertEquals(0f, rem(r.at(Relief.FLUSH)), TOLERANCE, "flush is no shadow at all, not a small one");
        assertEquals(0.375f, rem(r.at(Relief.CONTROL)), TOLERANCE, "rung 1 is the authored base");
        for (int rung = Relief.CONTROL; rung < Relief.OVERLAY; rung++) {
            assertEquals(r.ratio(), rem(r.at(rung + 1)) / rem(r.at(rung)), TOLERANCE,
                    "rung " + (rung + 1) + " over rung " + rung);
        }
    }

    @Test
    void hoverLiftsOneRungAndPressSetsTheControlFlush() {
        Relief r = Relief.STANDARD;
        assertEquals(r.at(Relief.RAISED), r.at(Relief.CONTROL, InteractionState.HOVER), "hover is one rung up");
        assertEquals(Length.ZERO, r.at(Relief.CONTROL, InteractionState.PRESSED), "press is one rung down, to flush");
        assertEquals(r.at(Relief.CONTROL), r.at(Relief.CONTROL, InteractionState.NORMAL), "at rest, nothing moves");
    }

    /**
     * The reason depth is a ladder and not a length: the response is the same move whatever the control already is,
     * so a floating panel and an ordinary button react by one rule and still look like themselves.
     */
    @Test
    void theSameMoveAppliesAtEveryRung() {
        Relief r = Relief.STANDARD;
        assertEquals(r.at(Relief.FLOATING), r.at(Relief.RAISED, InteractionState.HOVER));
        assertEquals(r.at(Relief.RAISED), r.at(Relief.FLOATING, InteractionState.PRESSED));
    }

    @Test
    void theLadderIsClosedAtBothEnds() {
        Relief r = Relief.STANDARD;
        assertEquals(r.at(Relief.OVERLAY), r.at(Relief.OVERLAY, InteractionState.HOVER),
                "the top rung has nowhere higher to go");
        assertEquals(Length.ZERO, r.at(Relief.FLUSH, InteractionState.PRESSED),
                "and nothing is deeper into the page than flush");
        assertEquals(Length.ZERO, r.at(-3), "a rung below the ladder is flush, not a negative shadow");
        assertEquals(r.at(Relief.OVERLAY), r.at(99), "and one above it is the top rung");
    }

    /**
     * The same table rule as {@link Shading}: a state the map says nothing about leaves the control where it is, so
     * a new interaction state behaves sanely without a case being added to {@code Relief}.
     */
    @Test
    void aStateTheTableDoesNotMentionDoesNotMove() {
        Relief silent = new Relief(0.375f, 1.6f, Map.of());
        for (InteractionState state : InteractionState.values()) {
            assertEquals(silent.at(Relief.CONTROL), silent.at(Relief.CONTROL, state), state.name());
        }
    }

    @Test
    void aThemeCarriesItsOwnReliefAndAnswersForIt() {
        assertSame(Relief.STANDARD, Theme.DARK.relief());
        assertEquals(Relief.STANDARD.at(Relief.CONTROL), Theme.DARK.elevation(Relief.CONTROL));
        assertEquals(Relief.STANDARD.at(Relief.CONTROL, InteractionState.HOVER),
                Theme.DARK.elevation(Relief.CONTROL, InteractionState.HOVER));

        // And a look that wants a flatter interface says so once, rather than in every widget that has a shadow.
        Theme flat = Theme.of(Palette.DARK, Shading.ON_DARK, Relief.of(0.2f, 1.25f, 1, -1), true, true);
        assertNotEquals(Theme.DARK.elevation(Relief.OVERLAY), flat.elevation(Relief.OVERLAY));
        assertTrue(rem(flat.elevation(Relief.OVERLAY)) < rem(Theme.DARK.elevation(Relief.OVERLAY)));
    }

    /** Depth is authored in rem, so it scales with the root em and zoom like everything else a widget sizes. */
    private static float rem(Length length) {
        assertTrue(length instanceof Length.Rem || Length.ZERO.equals(length), "a rung is a rem length: " + length);
        return length instanceof Length.Rem r ? r.v() : 0f;
    }
}
