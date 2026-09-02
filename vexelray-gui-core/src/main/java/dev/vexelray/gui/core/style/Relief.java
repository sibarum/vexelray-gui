package dev.vexelray.gui.core.style;

import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;

import java.util.EnumMap;
import java.util.Map;

/**
 * How far off its surface a thing stands, and what the pointer does to that. The depth half of what {@link Shading}
 * is for colour: hover and press are not shadow sizes a widget picks — they are one step along a ladder, applied to
 * whatever rung the control already sits on.
 *
 * <p>Depth is a <b>ladder of rungs</b> rather than a length, for the same reason a {@link Palette} is a ladder of
 * surfaces: the response has to be expressible as a move rather than as arithmetic. Hover is one rung up and press
 * is one rung down whatever the control's resting rung is, so a dialog and a flush chip respond by the same rule and
 * still look like themselves. It also means nothing multiplies or adds {@link Length}s — the quantisation is the
 * ladder, the ladder is geometric, and a rung is therefore a ratio rather than a number somebody chose.
 *
 * <p>The steps are a <b>table</b>, exactly as in {@link Shading}: a state this map says nothing about does not move.
 * A new interaction state behaves sanely without a case being added here.
 *
 * <p>The ladder is in {@code rem}, so depth scales with the root em and with zoom like everything else a widget
 * sizes. A shadow is a property of the light, not of the display grid, so there is no {@code dp} option here.
 *
 * @param baseRem the depth of rung 1 — the resting depth of an ordinary control — in rem
 * @param ratio   how much deeper each rung is than the one below it
 * @param steps   signed rung offsets per state; states absent from the map do not move
 */
public record Relief(float baseRem, float ratio, Map<InteractionState, Integer> steps) {

    /** Flush with its surface: no shadow at all. */
    public static final int FLUSH = 0;

    /** An ordinary control at rest: a button, a toggle, a slider knob, a selected tab. */
    public static final int CONTROL = 1;

    /** Standing clear of the content: a strip over a document, a popover attached to a control. */
    public static final int RAISED = 2;

    /** Floating over the content rather than sitting in it: a menu, a tooltip, a drag ghost. */
    public static final int FLOATING = 3;

    /** The top of the ladder — standing entirely off the page: a card, a dialog. */
    public static final int OVERLAY = 4;

    /** The framework's relief: hover lifts one rung, press sets the control flush. */
    public static final Relief STANDARD = of(0.375f, 1.6f, 1, -1);

    public Relief {
        steps = Map.copyOf(steps);
    }

    /** A ladder with the usual two responses: {@code hover} and {@code pressed} rung offsets. */
    public static Relief of(float baseRem, float ratio, int hover, int pressed) {
        Map<InteractionState, Integer> map = new EnumMap<>(InteractionState.class);
        map.put(InteractionState.HOVER, hover);
        map.put(InteractionState.PRESSED, pressed);
        return new Relief(baseRem, ratio, map);
    }

    /** The depth of {@code rung}, clamped to the ladder. Rung 0 is flush; each rung above is {@link #ratio} deeper. */
    public Length at(int rung) {
        int r = Math.clamp(rung, FLUSH, OVERLAY);
        return r == FLUSH ? Length.ZERO : Length.rem(baseRem * (float) Math.pow(ratio, r - 1));
    }

    /** The depth of {@code rung} while the pointer is in {@code state} — the rung moved, then resolved. */
    public Length at(int rung, InteractionState state) {
        return at(rung + steps.getOrDefault(state, 0));
    }
}
