package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.InteractionState;

/**
 * The one place a look is decided: a {@link Palette} to resolve {@link Role}s against, a {@link Shading} for what
 * the pointer does to them, and whether this look uses the engine's depth effects. Held by {@code Gui}, read by
 * every widget and by the renderer's own chrome, and hard-coded nowhere.
 *
 * <p>Usage is always the same shape — a widget states the role and, if the node reacts to the pointer, the state:
 *
 * {@snippet :
 * Node button = gui.text("Save").background(gui.theme().color(Role.ACTION));
 * gui.onState(button, state -> button.background(gui.theme().color(Role.ACTION, state)));
 * }
 *
 * <h2>Defining one</h2>
 * A new theme is {@link #of} over a palette of nine anchors — not a table of colours to fill in. Override
 * {@link #color(Role, InteractionState)} to special-case a single role; implement the interface outright for a
 * theme that is not palette-driven at all (a high-contrast mode that ignores the ladders, say).
 *
 * <h2>When it is read</h2>
 * Roles resolve at the moment a widget paints a prop: at build time, and again in every state handler. A theme
 * swapped after the tree is built therefore reaches the renderer's chrome and everything that restyles on
 * interaction, but not the props already written — a live dark/light toggle needs a restyle pass over the built
 * nodes, which the framework does not yet publish. The long-term shape is for a node to hold the {@link Role}
 * itself and for the renderer to resolve it per frame, exactly as {@code Length} is resolved against zoom; that is
 * why {@code Role} is already the declaration and {@code Color} only ever the result.
 */
public interface Theme {

    /** The framework's default: the look every widget shipped with, re-derived from nine anchors. */
    Theme DARK = of(Palette.DARK, Shading.ON_DARK, true, true);

    /** The same relationships on a near-white page. Depth is shadow-only — an edge light on white reads as haze. */
    Theme LIGHT = of(Palette.LIGHT, Shading.ON_LIGHT, false, false);

    /** The anchors every role resolves against. */
    Palette palette();

    /** What the pointer does to a resolved colour. */
    Shading shading();

    /**
     * Whether surfaces take the engine's edge light ({@code Node.lit}). A property of the look rather than of the
     * widget: the same lit fill that gives a dark panel its glint washes a light one out.
     */
    boolean lit();

    /** Whether white-on-fill labels are letterpressed ({@code Node.textSunken}) to buy contrast. */
    boolean letterpress();

    /** {@code role} at rest. */
    default Color color(Role role) {
        return role.of(palette());
    }

    /** {@code role} as it should look while the pointer is in {@code state}. */
    default Color color(Role role, InteractionState state) {
        return shading().apply(role.of(palette()), state);
    }

    /** A palette-driven theme. */
    static Theme of(Palette palette, Shading shading, boolean lit, boolean letterpress) {
        return new PaletteTheme(palette, shading, lit, letterpress);
    }
}

/** The default implementation: everything is already on the interface, so this holds the four decisions. */
record PaletteTheme(Palette palette, Shading shading, boolean lit, boolean letterpress) implements Theme {
}
