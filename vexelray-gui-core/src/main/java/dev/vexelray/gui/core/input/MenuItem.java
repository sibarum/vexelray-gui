package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.IconSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

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
 * <p><b>A submenu is an item with {@link #children()}</b>: a line that opens another list beside it rather than
 * running anything ({@link #submenu(String, boolean, List)}). It is a line like any other, so it can be disabled,
 * marked and placed among the rest; it is enabled while it has a child that can be chosen, since a submenu of greyed
 * lines is one more step to a dead end.
 *
 * @param icon     a glyph from the text atlas drawn beside the label, or {@code null} for none
 * @param label    the text shown, or {@code null} for a separator
 * @param action   what choosing it runs, or {@code null} if there is nothing to run
 * @param enabled  whether it can be chosen
 * @param mark     an icon from the set drawn beside the label, or {@code null} for none
 * <p><b>A heading names the group under it</b> ({@link #heading(String)}): a line of small capitals that runs nothing,
 * as {@code CAPTURE} heads the screenshot and the recording in a window's app menu. <b>A current line</b>
 * ({@link #current(boolean)}) is a command about something going on now, Stop recording while one runs, and is drawn
 * edged in the accent, as a chosen option is, so it is found before it is read.
 *
 * @param children the lines of the submenu it opens, in order; empty for an item that runs something
 * @param heading  whether this line is a group's heading rather than a command
 * @param current  whether this line is about something going on now, and is drawn so
 * @param shortcut the chord that does the same from the keyboard, drawn dim at the line's right
 *                 ({@link #shortcut(Shortcut)}), or {@code null} for none. Said, not bound: the chord is declared
 *                 where chords are, and a line only tells the person it exists (IDE framework-notes FN-6)
 */
public record MenuItem(String icon, String label, Runnable action, boolean enabled, IconSource mark,
                       List<MenuItem> children, boolean heading, boolean current, Shortcut shortcut) {

    /** A line with no shortcut said beside it. */
    public MenuItem(String icon, String label, Runnable action, boolean enabled, IconSource mark,
                    List<MenuItem> children, boolean heading, boolean current) {
        this(icon, label, action, enabled, mark, children, heading, current, null);
    }

    /** A horizontal rule between groups. */
    public static final MenuItem SEPARATOR = new MenuItem(null, null, null, false, null);

    /** A line with no heading or current mark: every line there was before there were either. */
    public MenuItem(String icon, String label, Runnable action, boolean enabled, IconSource mark,
                    List<MenuItem> children) {
        this(icon, label, action, enabled, mark, children, false, false);
    }

    /** A heading over the lines that follow it: {@code label}, drawn as small capitals, choosing nothing. */
    public static MenuItem heading(String label) {
        return new MenuItem(null, java.util.Objects.requireNonNull(label, "label"), null, false, null, List.of(),
                true, false);
    }

    /** This line, drawn as about something going on now when {@code current}, everything else as it is. */
    public MenuItem current(boolean current) {
        return new MenuItem(icon, label, action, enabled, mark, children, heading, current, shortcut);
    }

    /** An item marked by a glyph, or by nothing. */
    public MenuItem(String icon, String label, Runnable action, boolean enabled) {
        this(icon, label, action, enabled, null);
    }

    /** An item that runs something, or does nothing: no submenu. */
    public MenuItem(String icon, String label, Runnable action, boolean enabled, IconSource mark) {
        this(icon, label, action, enabled, mark, List.of());
    }

    /**
     * A line that opens {@code items} beside it. A separator at either end of them, or next to another, is dropped,
     * as the menu's own sink drops one.
     */
    public static MenuItem submenu(String label, boolean enabled, List<MenuItem> items) {
        List<MenuItem> kept = new ArrayList<>(items.size());
        for (MenuItem item : items) {
            if (!item.separator() || (!kept.isEmpty() && !kept.getLast().separator())) {
                kept.add(item);
            }
        }
        while (!kept.isEmpty() && kept.getLast().separator()) {
            kept.removeLast();
        }
        return new MenuItem(null, label, null, enabled, null, kept);
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
        children = children == null ? List.of() : List.copyOf(children);
        // An item with nothing to run cannot be enabled, whatever the caller passed: the two would disagree on
        // screen (a live-looking line that does nothing) and the presenter would have to guess which is true. A
        // submenu runs nothing itself; what it has to offer is a child that can be chosen.
        enabled = enabled && (children.isEmpty() ? action != null : children.stream().anyMatch(MenuItem::enabled));
    }

    /** Whether this line opens a submenu rather than running something. */
    public boolean submenu() {
        return !children.isEmpty();
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
        return new MenuItem(icon, label, action, enabled, mark, children, heading, current, shortcut);
    }

    /**
     * This line, and every line of its submenu at any depth, with each action passed through {@code wrap}: how a
     * sink that adds something to every command (a tree's one-at-a-time rule) reaches the commands a submenu holds.
     */
    public MenuItem wrapActions(UnaryOperator<Runnable> wrap) {
        List<MenuItem> wrapped = children.stream().map(c -> c.wrapActions(wrap)).toList();
        return new MenuItem(icon, label, action == null ? null : wrap.apply(action), enabled, mark, wrapped, heading,
                current, shortcut);
    }

    /**
     * This line with {@code chord} said at its right, everything else as it is: the chord a person could press instead
     * of opening the menu. It binds nothing; the chord is the application's to declare as it declares every chord.
     */
    public MenuItem shortcut(Shortcut chord) {
        return new MenuItem(icon, label, action, enabled, mark, children, heading, current, chord);
    }
}
