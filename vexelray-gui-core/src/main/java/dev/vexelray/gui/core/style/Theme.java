package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;

/**
 * The one place a look is decided: a {@link Palette} to resolve {@link Role}s against, a {@link Shading} for what
 * the pointer does to them, a {@link Relief} for how far things stand off their surface, and whether this look uses
 * the engine's depth effects. Held by {@code Gui}, read by every widget and by the renderer's own chrome, and
 * hard-coded nowhere.
 *
 * <p>Usage is always the same shape — a widget states the role and, if the node reacts to the pointer, the state, and
 * never the colour either comes to:
 *
 * {@snippet :
 * Node button = gui.text("Save").background(Role.ACTION).lit(Finish.LIT);
 * Seams.of(gui).onState(button, state -> button.background(Role.ACTION.at(state)));
 * }
 *
 * <h2>Defining one</h2>
 * A new theme is {@link #of} over a palette of nine anchors — not a table of colours to fill in. A theme that puts a
 * few roles somewhere else is {@link #map(Role, Role)} over one of those, which keeps every other decision; implement
 * the interface outright only for a theme that is not palette-driven at all (a high-contrast mode that ignores the
 * ladders, say).
 *
 * <h2>When it is read</h2>
 * Every frame, by the renderer. A node holds a {@link Paint} — a role, a role in a state, a role thinned or mixed —
 * and a {@link Finish} for lit and letterpress, and both resolve against the tree's theme as it is drawn, exactly as
 * {@code Length} resolves against zoom. So {@code Gui.theme(t)} works at any time: the whole tree is drawn in the new
 * look from the next frame, nothing is rewritten, and a tree with a {@code PaintMotion} fades there. A colour asked
 * of the theme and written down as a colour is the one thing a swap cannot reach, which is why the framework's own
 * classes are forbidden to ({@code RethemeGuardTest}).
 */
public interface Theme {

    /** The framework's default: the look every widget shipped with, re-derived from nine anchors. */
    Theme DARK = of(Palette.DARK, Shading.ON_DARK, Relief.STANDARD, true, true);

    /** The same relationships on a near-white page. Depth is shadow-only — an edge light on white reads as haze. */
    Theme LIGHT = of(Palette.LIGHT, Shading.ON_LIGHT, Relief.STANDARD, false, false);

    /** The anchors every role resolves against. */
    Palette palette();

    /** What the pointer does to a resolved colour. */
    Shading shading();

    /** The depth ladder, and what the pointer does to a rung of it. */
    Relief relief();

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

    /** {@code rung} of the depth ladder, at rest. */
    default Length elevation(int rung) {
        return relief().at(rung);
    }

    /**
     * {@code rung} as it should stand while the pointer is in {@code state}. The counterpart of
     * {@link #color(Role, InteractionState)}, and used the same way — a widget states the rung it rests on, and the
     * theme decides what hovering and pressing do to it:
     *
     * {@snippet :
     * Seams.of(gui).onState(button, state -> button
     *         .background(Role.ACTION.at(state))
     *         .elevation(gui.theme().elevation(Relief.CONTROL, state)));
     * }
     */
    default Length elevation(int rung, InteractionState state) {
        return relief().at(rung, state);
    }

    /**
     * How the node holding keyboard focus shows it. The renderer draws this one ring around whichever node has
     * focus, so no widget draws its own; a node can override it with {@code Node.focusRing}.
     *
     * <p>A default rather than abstract, so a theme implemented outright before the ring existed still compiles
     * and gets the framework's ring.
     */
    default FocusRing focusRing() {
        return FocusRing.STANDARD;
    }

    /** This theme with {@code ring} as its focus ring and every other decision unchanged. */
    default Theme focusRing(FocusRing ring) {
        return new DerivedTheme(this, ring == null ? FocusRing.STANDARD : ring, null, null);
    }

    /**
     * What this theme does to an icon that is the whole of a control: thickening it under the pointer, a halo when the
     * control is on. A default, like {@link #focusRing()}, so a theme written before it still compiles.
     */
    default IconEffects icons() {
        return IconEffects.STANDARD;
    }

    /** This theme with {@code effects} for its icons and every other decision unchanged. */
    default Theme icons(IconEffects effects) {
        return new DerivedTheme(this, null, effects == null ? IconEffects.STANDARD : effects, null);
    }

    /**
     * This theme with {@code role} drawn as {@code as} is, at rest and in every pointer state, and every other decision
     * unchanged. For a design that puts a role somewhere the framework does not: every widget that names {@code role}
     * gets {@code as}, and nothing else needs writing.
     *
     * {@snippet :
     * Theme look = Theme.of(palette, Shading.ON_DARK, Relief.STANDARD, false, false)
     *         .map(Role.SELECTION, SELECTED)
     *         .map(Role.GRIP, THUMB);
     * }
     *
     * <p>Roles are matched by identity, as widgets name them: {@code Role.SELECTION} itself, not another role that
     * happens to resolve to the same colour. A later {@code map} of the same role wins.
     */
    default Theme map(Role role, Role as) {
        java.util.Objects.requireNonNull(role, "role");
        java.util.Objects.requireNonNull(as, "as");
        return map(r -> r == role ? as : r);
    }

    /**
     * This theme with every role passed through {@code roles} before it is resolved, and every other decision
     * unchanged: {@link #map(Role, Role)} for a mapping that is a rule rather than a list. Called whenever a colour is
     * asked for, which is every frame, so it must not allocate.
     */
    default Theme map(java.util.function.UnaryOperator<Role> roles) {
        return new DerivedTheme(this, null, null, java.util.Objects.requireNonNull(roles, "roles"));
    }

    /** A palette-driven theme, with the framework's focus ring. */
    static Theme of(Palette palette, Shading shading, Relief relief, boolean lit, boolean letterpress) {
        return of(palette, shading, relief, lit, letterpress, FocusRing.STANDARD);
    }

    /** A palette-driven theme with its own focus ring. */
    static Theme of(Palette palette, Shading shading, Relief relief, boolean lit, boolean letterpress,
                    FocusRing focusRing) {
        return new PaletteTheme(palette, shading, relief, lit, letterpress,
                focusRing == null ? FocusRing.STANDARD : focusRing);
    }
}

/** The default implementation: everything is already on the interface, so this holds the six decisions. */
record PaletteTheme(Palette palette, Shading shading, Relief relief, boolean lit, boolean letterpress,
                    FocusRing focusRing) implements Theme {

    @Override
    public Theme focusRing(FocusRing ring) {
        return Theme.of(palette, shading, relief, lit, letterpress, ring);
    }
}

/**
 * A theme that is another with some of its decisions replaced: a focus ring, icon effects, or roles mapped. Every
 * derivation is this one class, so a decision added to {@link Theme} is forwarded once, here, and no derived theme can
 * forget it. A null field is the base's.
 */
final class DerivedTheme implements Theme {

    private final Theme base;
    private final FocusRing ring;
    private final IconEffects icons;
    private final java.util.function.UnaryOperator<Role> roles;

    DerivedTheme(Theme base, FocusRing ring, IconEffects icons, java.util.function.UnaryOperator<Role> roles) {
        this.base = base;
        this.ring = ring;
        this.icons = icons;
        this.roles = roles;
    }

    private Role mapped(Role role) {
        return roles == null ? role : roles.apply(role);
    }

    @Override
    public Palette palette() {
        return base.palette();
    }

    @Override
    public Shading shading() {
        return base.shading();
    }

    @Override
    public Relief relief() {
        return base.relief();
    }

    @Override
    public boolean lit() {
        return base.lit();
    }

    @Override
    public boolean letterpress() {
        return base.letterpress();
    }

    @Override
    public Color color(Role role) {
        return base.color(mapped(role));
    }

    @Override
    public Color color(Role role, InteractionState state) {
        return base.color(mapped(role), state);
    }

    @Override
    public Length elevation(int rung) {
        return base.elevation(rung);
    }

    @Override
    public Length elevation(int rung, InteractionState state) {
        return base.elevation(rung, state);
    }

    @Override
    public FocusRing focusRing() {
        return ring == null ? base.focusRing() : ring;
    }

    @Override
    public IconEffects icons() {
        return icons == null ? base.icons() : icons;
    }

    @Override
    public Theme focusRing(FocusRing chosen) {
        return new DerivedTheme(base, chosen == null ? FocusRing.STANDARD : chosen, icons, roles);
    }

    @Override
    public Theme icons(IconEffects chosen) {
        return new DerivedTheme(base, ring, chosen == null ? IconEffects.STANDARD : chosen, roles);
    }

    @Override
    public Theme map(java.util.function.UnaryOperator<Role> more) {
        java.util.Objects.requireNonNull(more, "roles");
        // The newer mapping is asked first, so a later map of the same role wins.
        java.util.function.UnaryOperator<Role> before = roles;
        return new DerivedTheme(base, ring, icons, before == null ? more : r -> before.apply(more.apply(r)));
    }
}
