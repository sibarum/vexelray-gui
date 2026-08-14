package dev.vexelray.gui.core.input;

import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.EnumSet;
import java.util.Set;

/**
 * A keyboard shortcut: a set of held {@link Modifier}s plus a {@link Key}. Registered global or focus-scoped and
 * dispatched from a key press before text input claims the key (architecture, keyboard-focus-text.md §3). Equality
 * is order-independent over the modifier set, so {@code Ctrl+Shift+K} matches regardless of registration order.
 */
public record Shortcut(Set<Modifier> modifiers, Key key) {

    public Shortcut {
        modifiers = modifiers.isEmpty() ? Set.of() : Set.copyOf(modifiers);
    }

    /** Convenience: {@code Shortcut.of(Key.S, Modifier.CONTROL)}. */
    public static Shortcut of(Key key, Modifier... mods) {
        return new Shortcut(mods.length == 0 ? Set.of() : EnumSet.copyOf(Set.of(mods)), key);
    }
}
