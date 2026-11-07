package dev.vexelray.gui.core.input;

import java.util.List;

/**
 * The triggers a named command has right now, as whoever binds commands decides them: what a menu asks so that a
 * line naming a command ({@link MenuItem#command(String)}) says the chord that does the same, the one the person
 * set, not the one the code started with. {@code vexelray-gui-keymap}'s {@code Keymap} answers for every window it
 * is attached to.
 *
 * <p>Asked on the GUI thread when a menu opens; an answer must be quick and must not block.
 */
public interface Commands {

    /** Nothing answers: every command has no trigger to say. What a tree has until something installs one. */
    Commands NONE = new Commands() {
        @Override
        public List<Trigger> triggers(String command) {
            return List.of();
        }
    };

    /** The triggers {@code command} has now, first the one to show; empty for one unbound, or not known here. */
    List<Trigger> triggers(String command);
}
