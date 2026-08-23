package dev.vexelray.gui.core.input;

/**
 * One line of a context menu, as decided at the moment of the right click: a label, what choosing it does, and
 * whether it can be chosen at all.
 *
 * <p>A {@link #SEPARATOR} is an item too, rather than a second type — a menu is a <em>list of lines</em>, and one
 * of the kinds of line is a rule. That keeps the presenter's job "draw these lines in order" with nothing to
 * dispatch over, and it lets a contributor group its own items without knowing what came before it: the sink drops
 * a separator that would land at either end of the menu or next to another one, so every contributor can open with
 * one and none of them has to check.
 *
 * <p><b>Disabled rather than absent</b> is the framework's convention for an action that does not apply right now
 * — Paste with an empty clipboard, Copy with no selection. A menu whose lines come and go teaches the user
 * nothing about where things are; a greyed line says "this exists, not now". A disabled item carries no action and
 * is inert: choosing it does nothing and does not dismiss the menu.
 *
 * @param label   the text shown, or {@code null} for a separator
 * @param action  what choosing it runs, or {@code null} if there is nothing to run
 * @param enabled whether it can be chosen
 */
public record MenuItem(String label, Runnable action, boolean enabled) {

    /** A horizontal rule between groups. */
    public static final MenuItem SEPARATOR = new MenuItem(null, null, false);

    /** An item that can be chosen. */
    public static MenuItem of(String label, Runnable action) {
        return new MenuItem(label, action, true);
    }

    /** An item shown but not choosable — the action does not apply in the current state. */
    public static MenuItem disabled(String label) {
        return new MenuItem(label, null, false);
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
}
