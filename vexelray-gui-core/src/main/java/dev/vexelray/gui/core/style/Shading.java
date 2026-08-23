package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.InteractionState;

import java.util.EnumMap;
import java.util.Map;

/**
 * What the pointer does to a colour. Hover and press are not colours a designer picks — they are the same
 * lightness step applied to whatever the control already is, which is why the palette this replaced contained
 * {@code PANEL_HOVER}, {@code ACCENT_HOVER}, {@code BTN_HOVER} and {@code BTN_BLUE_HOVER} all shifted by
 * almost exactly the same amount. One step, stated once.
 *
 * <p>The steps are a <b>table</b> rather than a branch: a state this map has nothing to say about leaves the colour
 * alone. That keeps the decision on the theme's side, where it belongs — and it means a new interaction state
 * never needs a case added here to behave sanely.
 *
 * @param steps signed lightness offset per state, in Oklab; states absent from the map do not shift
 */
public record Shading(Map<InteractionState, Double> steps) {

    /** The framework's shading: hover lifts, press pushes further than hover lifted. */
    public static final Shading ON_DARK = of(0.040, -0.050);

    /** For a light palette, where a hovered surface darkens instead — the same sizes, mirrored. */
    public static final Shading ON_LIGHT = of(-0.030, -0.060);

    public Shading {
        steps = Map.copyOf(steps);
    }

    /** Signed lightness offsets for hover and press. */
    public static Shading of(double hover, double pressed) {
        Map<InteractionState, Double> map = new EnumMap<>(InteractionState.class);
        map.put(InteractionState.HOVER, hover);
        map.put(InteractionState.PRESSED, pressed);
        return new Shading(map);
    }

    /** {@code base} as it should look in {@code state}. Alpha is carried through untouched. */
    public Color apply(Color base, InteractionState state) {
        double step = steps.getOrDefault(state, 0.0);
        if (step == 0.0 || base.a() == 0f) {
            return base;   // nothing to shift, or nothing painted to shift
        }
        Oklab c = Oklab.of(base);
        return c.atLightness(c.l() + step).toColor(base.a());
    }
}
