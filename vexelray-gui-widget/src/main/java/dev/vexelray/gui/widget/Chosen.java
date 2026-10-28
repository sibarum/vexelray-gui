package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Addressed;

import java.util.Objects;

/**
 * The user chose the {@link MenuAction} {@code action} from the menu of {@code item} in the widget at {@code at}.
 * Published on the widget's own topic ({@link TreeView#CHOSEN}, {@link ListView#CHOSEN}, {@link Tabs#CHOSEN}) and
 * addressed to it. The widget does nothing else for it: the work is the application's.
 *
 * @param at     the widget's address
 * @param action the action's id
 * @param item   what the menu was about: a tree's or a list's item, or a tab's index as it was when the menu opened
 */
public record Chosen(String at, String action, Object item) implements Addressed {

    public Chosen {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(item, "item");
    }

    @Override
    public Object address() {
        return at;
    }
}
