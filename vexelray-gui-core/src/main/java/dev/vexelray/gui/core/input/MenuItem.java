package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.IconSource;

/**
 * One line of a context menu, as decided at the moment of the right click: an icon, a label, what choosing it
 * does, and whether it can be chosen at all.
 *
 * <p>A {@link #SEPARATOR} is an item too, rather than a second type — a menu is a <em>list of lines</em>, and one
 * of the kinds of line is a rule. That keeps the presenter's job "draw these lines in order" with nothing to
 * dispatch over, and it lets a contributor group its own items without knowing what came before it: the sink drops
 * a separator that would land at either end of the menu or next to another one, so every contributor can open with
 * one and none of them has to check.
 *
 * <p><b>The mark is an icon from the set</b> ({@link IconSource}, in practice {@code vexelray-gui-icons}'s
 * {@code Icon}): a distance field the presenter tints with the label's colour, so it greys with a disabled line and
 * scales with zoom and density as the text does. A {@code String} {@link #icon() glyph} from the text atlas is still
 * taken, for a mark the set does not draw; when an item has both, the icon wins. Neither means no mark, which every
 * item is free to be — a menu whose items all decline one is drawn exactly as a menu with no icons at all.
 *
 * <p><b>Disabled rather than absent</b> is the framework's convention for an action that does not apply right now
 * — Paste with an empty clipboard, Copy with no selection. A menu whose lines come and go teaches the user
 * nothing about where things are; a greyed line says "this exists, not now". A disabled item carries no action and
 * is inert: choosing it does nothing and does not dismiss the menu.
 *
 * @param icon    a glyph from the text atlas drawn beside the label, or {@code null} for none
 * @param label   the text shown, or {@code null} for a separator
 * @param action  what choosing it runs, or {@code null} if there is nothing to run
 * @param enabled whether it can be chosen
 * @param mark    an icon from the set drawn beside the label, or {@code null} for none
 */
public record MenuItem(String icon, String label, Runnable action, boolean enabled, IconSource mark) {

    /** A horizontal rule between groups. */
    public static final MenuItem SEPARATOR = new MenuItem(null, null, null, false, null);

    /** An item marked by a glyph, or by nothing. */
    public MenuItem(String icon, String label, Runnable action, boolean enabled) {
        this(icon, label, action, enabled, null);
    }

    /** An item that can be chosen. */
    public static MenuItem of(String label, Runnable action) {
        return new MenuItem(null, label, action, true, null);
    }

    /** An item that can be chosen, marked with the glyph {@code icon}. */
    public static MenuItem of(String icon, String label, Runnable action) {
        return new MenuItem(icon, label, action, true, null);
    }

    /** An item that can be chosen, marked with {@code mark}. */
    public static MenuItem of(IconSource mark, String label, Runnable action) {
        return new MenuItem(null, label, action, true, mark);
    }

    /** An item shown but not choosable — the action does not apply in the current state. */
    public static MenuItem disabled(String label) {
        return new MenuItem(null, label, null, false, null);
    }

    /** An item shown but not choosable, keeping its glyph: greyed out is still a line about the same command. */
    public static MenuItem disabled(String icon, String label) {
        return new MenuItem(icon, label, null, false, null);
    }

    /** An item shown but not choosable, keeping its mark: greyed out is still a line about the same command. */
    public static MenuItem disabled(IconSource mark, String label) {
        return new MenuItem(null, label, null, false, mark);
    }

    public MenuItem {
        // An item with nothing to run cannot be enabled, whatever the caller passed: the two would disagree on
        // screen (a live-looking line that does nothing) and the presenter would have to guess which is true.
        enabled = enabled && action != null;
    }

    /** Whether this line is a rule rather than a command. */
    public boolean separator() {
        return label == null;
    }

    /** Whether this line carries a mark of either kind, and so wants the menu's icon column. */
    public boolean marked() {
        return mark != null || icon != null;
    }

    /** This line running {@code action} instead, everything else as it is. */
    public MenuItem withAction(Runnable action) {
        return new MenuItem(icon, label, action, enabled, mark);
    }
}
