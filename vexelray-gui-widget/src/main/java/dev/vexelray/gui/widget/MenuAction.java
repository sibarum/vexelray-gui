package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.Shortcut;

import java.util.Objects;

/**
 * One of the application's commands on the menu a widget gives each of its items: a tree's rows
 * ({@link TreeView#action}), a list's ({@link ListView#action}), a tab bar's tabs ({@link Tabs#action}). An id, a mark
 * and a label, and nothing that runs. Choosing it publishes the widget's own {@code Chosen} ({@link TreeView.Chosen}
 * and the like) on its topic, with the id and the item, and the application does the work where its logic runs
 * (vexelray-framework's threading.md, T7.2 and T7.5).
 *
 * <p>A menu that belongs to no item, on a node the view holds (a text field, a panel), is declared whole with
 * {@code Gui.menu} and a {@code Menu}; this is the form for a widget whose items the view does not hold the nodes of,
 * because the widget builds them.
 *
 * @param id    what {@link TreeView.Chosen#action()} carries: unique in the widget, and none of the widget's own lines
 * @param icon  the glyph drawn beside the label, or null
 * @param mark  the icon from the set drawn beside the label, or null
 * @param label the text shown
 * @param shortcut the chord that does the same, said at the line's right ({@link MenuItem#shortcut(Shortcut)}),
 *                 or null
 * @param command  the command it does, by the name it is bound by ({@link MenuItem#command(String)}), whose chord
 *                 as it is now the line says; or null
 * @param under    the submenu it is a line of, by that submenu's label ({@link #under(String)}), or null for a line
 *                 of the item's menu itself
 */
public record MenuAction(String id, String icon, IconSource mark, String label, Shortcut shortcut, String command,
                         String under) {

    /** A command with no chord said beside it. */
    public MenuAction(String id, String icon, IconSource mark, String label) {
        this(id, icon, mark, label, null, null, null);
    }

    /** A command on the item's menu itself, in no submenu. */
    public MenuAction(String id, String icon, IconSource mark, String label, Shortcut shortcut, String command) {
        this(id, icon, mark, label, shortcut, command, null);
    }

    public MenuAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (id.isBlank()) {
            throw new IllegalArgumentException("an action's id is what Chosen names it by, so it has one");
        }
        if (under != null && under.isBlank()) {
            throw new IllegalArgumentException("a submenu is found by its label, so '" + id + "' names one or none");
        }
    }

    /** A command with no mark. */
    public static MenuAction of(String id, String label) {
        return new MenuAction(id, null, null, label);
    }

    /** A command marked with {@code icon}, a glyph, or null for none. */
    public static MenuAction of(String id, String icon, String label) {
        return new MenuAction(id, icon, null, label);
    }

    /** A command marked with {@code mark}, an icon from the set. */
    public static MenuAction of(String id, IconSource mark, String label) {
        return new MenuAction(id, null, mark, label);
    }

    /**
     * This command with {@code chord} said at its line's right: the chord that does the same, which is declared where
     * chords are. It binds nothing.
     */
    public MenuAction shortcut(Shortcut chord) {
        return new MenuAction(id, icon, mark, label, chord, command, under);
    }

    /**
     * This command as doing {@code bound}, the command it is bound by: its line says that command's chord as it is now
     * ({@link MenuItem#command(String)}), following a rebind. It binds nothing.
     */
    public MenuAction command(String bound) {
        return new MenuAction(id, icon, mark, label, shortcut, bound, under);
    }

    /**
     * This command as a line of the submenu labelled {@code submenu}: New ▸ File, Open in ▸ Vexplore. Every action
     * declared under the same label is a line of the one submenu, in the order declared, and the submenu stands where
     * the first of them was declared. It is left off an item whose entry hides every line of it, and a line greyed on
     * the entry is greyed in it. Null puts it back on the item's menu itself.
     */
    public MenuAction under(String submenu) {
        return new MenuAction(id, icon, mark, label, shortcut, command, submenu);
    }
}
