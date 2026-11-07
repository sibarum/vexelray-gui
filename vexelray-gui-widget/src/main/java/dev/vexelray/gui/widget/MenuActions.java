package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.MenuSink;
import sibarum.atchung.Topic;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link MenuAction}s a widget's items carry, kept for it: declared once, ids checked as they come, and offered
 * on an item's menu as lines that publish the widget's own {@code Chosen} ({@link TreeView.Chosen}, {@link
 * ListView.Chosen}, {@link Tabs.Chosen}). One copy of what a tree, a list and a tab bar would
 * otherwise each write.
 */
final class MenuActions {

    /** How a widget makes the message a choice publishes: its own {@code Chosen}'s constructor. */
    @FunctionalInterface
    interface Choice<T> {
        T of(String at, String action, Object item);
    }

    private final Set<String> reserved;
    /** In the order declared. Read on every right click, written at build time. */
    private final List<MenuAction> actions = new CopyOnWriteArrayList<>();

    /** Actions for a widget whose own lines have the ids {@code reserved}. */
    MenuActions(String... reserved) {
        this.reserved = Set.of(reserved);
    }

    /**
     * Declare {@code action}.
     *
     * @throws IllegalArgumentException when the id is one of the widget's own, or another action already has it
     */
    void add(MenuAction action) {
        java.util.Objects.requireNonNull(action, "action");
        if (reserved.contains(action.id())) {
            throw new IllegalArgumentException("'" + action.id() + "' is the widget's own line; give this action an "
                    + "id of its own");
        }
        for (MenuAction had : actions) {
            if (had.id().equals(action.id())) {
                throw new IllegalArgumentException("two actions with the id '" + action.id() + "' could not be told "
                        + "apart in what the widget's Chosen carries");
            }
        }
        actions.add(action);
    }

    boolean isEmpty() {
        return actions.isEmpty();
    }

    /**
     * Put every action on {@code menu} for {@code item}, but those {@code hidden}, greying those {@code greyed}.
     * Choosing one runs {@code first} (a tree's walk giving way) and then publishes on {@code topic} what
     * {@code choice} makes of it.
     */
    <T> void offer(MenuSink menu, Gui gui, Topic<T> topic, Choice<T> choice, String at, Object item, Set<String> hidden,
                   Set<String> greyed, Runnable first) {
        for (MenuAction action : actions) {
            if (!hidden.contains(action.id())) {
                T chosen = choice.of(at, action.id(), item);
                menu.item(new MenuItem(action.icon(), action.label(), () -> {
                    first.run();
                    gui.bus().publish(topic, chosen);
                }, !greyed.contains(action.id()), action.mark()).shortcut(action.shortcut()).command(action.command()));
            }
        }
    }
}
