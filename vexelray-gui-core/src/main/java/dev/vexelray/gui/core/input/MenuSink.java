package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;

/**
 * What a context-menu contributor writes into. One is handed to every source registered on the node that won the
 * right click ({@code Gui.onContextMenu}), in registration order, and what they leave behind is the menu.
 *
 * <p><b>Why a sink and not a returned list.</b> A menu's contents depend on the state of the application at the
 * instant it opens — what is selected, what is on the clipboard, which row is under the pointer — so the framework
 * cannot hold a menu and show it later; it has to ask. Asking through a sink is what lets several contributors
 * share one menu: a widget states its own defaults (a field's Copy/Cut/Paste), the application adds what only it
 * knows (Open, Copy path, Properties), and neither has to be aware of the other or agree on an order.
 *
 * <p>{@link #event()} is the context the sink carries: which node was hit and where, so a source can decide by
 * position. A widget that knows <em>more</em> than that — which row, which tab — passes it to its own callers as a
 * second argument rather than putting it here, because the framework has no way to name it.
 */
public interface MenuSink {

    /** The right click that opened this menu: the node hit, and where. */
    ClickEvent event();

    /**
     * The history this menu's {@link #edit} items record into: the one bound nearest above the node that was
     * right-clicked — the window's, in a framework application — or null where the tree binds none. A sink that
     * wraps another must pass this through, or its edits stop being undoable.
     */
    default History history() {
        return null;
    }

    /** Add {@code item} as it is: the form every other one delegates to. */
    MenuSink item(MenuItem item);

    /**
     * Add an item whose command is an <b>edit</b>: choosing it asks {@code edit} for the {@link Change} it makes —
     * built then, against what is selected at that moment — and performs it through {@link #history()}, so Ctrl+Z
     * takes it back (docs/plans/undo.md, step 7). A null answer does nothing. The form for every item that changes
     * the user's data; {@link #item(String, Runnable)} stays for what is not an edit, because a {@link Runnable}
     * cannot be inverted and a command written as one cannot be undone.
     */
    default MenuSink edit(String label, java.util.function.Supplier<? extends Change> edit) {
        return edit(label, true, edit);
    }

    /** As {@link #edit(String, java.util.function.Supplier)}, choosable only when {@code enabled}. */
    default MenuSink edit(String label, boolean enabled, java.util.function.Supplier<? extends Change> edit) {
        return item(label, enabled, () -> {
            Change change = edit.get();
            if (change == null) {
                return;
            }
            History h = history();
            if (h != null) {
                h.perform(change);
            } else {
                change.apply();   // a tree that binds no history: still done, only not undoable
            }
        });
    }

    /** Add a choosable item. */
    default MenuSink item(String label, Runnable action) {
        return item(MenuItem.of(label, action));
    }

    /**
     * Add an item that is only choosable when {@code enabled} — the form to reach for when the action exists but
     * does not apply yet (see {@link MenuItem}: shown and greyed beats absent).
     */
    default MenuSink item(String label, boolean enabled, Runnable action) {
        return item(new MenuItem(null, label, action, enabled, null));
    }

    /** Add a choosable item marked with {@code icon}, an icon from the set (see {@link MenuItem}). */
    default MenuSink item(IconSource icon, String label, Runnable action) {
        return item(new MenuItem(null, label, action, true, icon));
    }

    /**
     * A mark, a label, whether the command applies right now, and what choosing it does. {@code icon} may be null —
     * an item with no mark — and a disabled item keeps the mark it would have had, because greyed out is still a line
     * about the same command.
     */
    default MenuSink item(IconSource icon, String label, boolean enabled, Runnable action) {
        return item(new MenuItem(null, label, action, enabled, icon));
    }

    /** Add a choosable item marked with the glyph {@code icon}, a character from the text atlas. */
    default MenuSink item(String icon, String label, Runnable action) {
        return item(new MenuItem(icon, label, action, true, null));
    }

    /** As {@link #item(IconSource, String, boolean, Runnable)}, marked with a glyph from the text atlas. */
    default MenuSink item(String icon, String label, boolean enabled, Runnable action) {
        return item(new MenuItem(icon, label, action, enabled, null));
    }

    /**
     * Open a group with a rule. Free to call unconditionally: a separator that would come out at either end of the
     * finished menu, or next to another one, is dropped.
     */
    MenuSink separator();
}
