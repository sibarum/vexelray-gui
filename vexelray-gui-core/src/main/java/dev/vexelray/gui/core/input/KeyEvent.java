package dev.vexelray.gui.core.input;

import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.Set;

/**
 * A key press delivered to the focused node (after shortcuts and Tab traversal had first refusal). Carries the
 * {@link Key} and the modifiers held at the time. This is the <em>command</em> channel (caret motion, backspace,
 * selection) — actual typed text arrives separately as characters, never conflated with key presses.
 */
public record KeyEvent(Key key, Set<Modifier> modifiers) {

    public boolean has(Modifier m) {
        return modifiers.contains(m);
    }
}
