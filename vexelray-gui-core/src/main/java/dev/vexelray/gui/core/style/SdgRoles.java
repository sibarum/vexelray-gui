package dev.vexelray.gui.core.style;

import java.util.Map;

/**
 * The names an SDG drawing's paints may give a {@link Role} (vexelray-fields {@code docs/sdg.md} §3.2, *Roles*): each
 * of the framework's own roles by its name in lower case ({@code ink}, {@code accent}, {@code danger}, ...), and three
 * that art is likelier to be written with: {@code foreground} (ink), {@code muted} (dim) and {@code background}
 * (page). A name not here is not one this theme knows, and the paint draws as authored.
 */
public final class SdgRoles {

    private static final Map<String, Role> NAMES = Map.ofEntries(
            Map.entry("foreground", Role.INK), Map.entry("muted", Role.DIM), Map.entry("background", Role.PAGE),
            Map.entry("ink", Role.INK), Map.entry("dim", Role.DIM), Map.entry("faint", Role.FAINT),
            Map.entry("accent", Role.ACCENT), Map.entry("action", Role.ACTION), Map.entry("on-action", Role.ON_ACTION),
            Map.entry("danger", Role.DANGER), Map.entry("on-danger", Role.ON_DANGER),
            Map.entry("caution", Role.CAUTION), Map.entry("success", Role.SUCCESS),
            Map.entry("highlight", Role.HIGHLIGHT), Map.entry("well", Role.WELL), Map.entry("page", Role.PAGE),
            Map.entry("chrome", Role.CHROME), Map.entry("panel", Role.PANEL), Map.entry("raised", Role.RAISED),
            Map.entry("line", Role.LINE), Map.entry("edge", Role.EDGE), Map.entry("grip", Role.GRIP));

    private SdgRoles() {
    }

    /** The role {@code name} names, or null for one this framework has none of. */
    public static Role named(String name) {
        return NAMES.get(name);
    }

    /**
     * The colour {@code name} takes in {@code theme}, as {@code 0xRRGGBBAA} with the authored colour's alpha (the theme
     * gives the colour, the art says how much of it); {@code stored} for a name this framework has no role for.
     */
    public static int rgba(Theme theme, String name, int stored) {
        Role role = NAMES.get(name);
        if (role == null) {
            return stored;
        }
        dev.vexelray.canvas.Color c = theme.color(role);
        return byte8(c.r()) << 24 | byte8(c.g()) << 16 | byte8(c.b()) << 8 | (stored & 0xFF);
    }

    private static int byte8(float v) {
        return Math.round(Math.clamp(v, 0f, 1f) * 255f);
    }
}
