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

    /**
     * The chord as a person writes it — {@code Ctrl+Shift+K}, {@code Alt+PageDown}, {@code Ctrl+=} — modifiers in
     * the order every desktop writes them (Ctrl, Alt, Shift, Super). The same text is what {@link #parse} reads,
     * so a chord shown on screen and a chord stored in a settings file are one spelling.
     */
    public String text() {
        return prefix(modifiers) + KeyNames.of(key);
    }

    /**
     * The held modifiers as the start of a chord — {@code Ctrl+Shift+}, or empty for none — in {@link #text()}'s
     * order and spelling: what a control recording a chord shows while the user is still holding the modifiers
     * and has not pressed the key.
     */
    public static String prefix(Set<Modifier> held) {
        StringBuilder out = new StringBuilder();
        for (Modifier m : KeyNames.ORDER) {
            if (held.contains(m)) {
                out.append(KeyNames.of(m)).append('+');
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return text();
    }

    /**
     * Read a chord written as {@link #text()} writes it. Lenient where it costs nothing: any case, spaces around
     * the {@code +}, the enum's own names ({@code CONTROL+PAGE_DOWN}) and the common aliases ({@code Control},
     * {@code Escape}, {@code Win}, {@code Cmd}).
     *
     * @throws IllegalArgumentException if it is not exactly one key after any number of modifiers — a modifier's
     *                                  own key ({@code Shift}) alone is not a chord, because pressed alone it
     *                                  reaches nothing
     */
    public static Shortcut parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a chord needs a key");
        }
        String[] words = text.trim().split("\\s*\\+\\s*", -1);
        EnumSet<Modifier> mods = EnumSet.noneOf(Modifier.class);
        for (int i = 0; i < words.length - 1; i++) {
            Modifier m = KeyNames.modifier(words[i]);
            if (m == null) {
                throw new IllegalArgumentException("'" + words[i] + "' in '" + text + "' is not a modifier");
            }
            mods.add(m);
        }
        String last = words[words.length - 1];
        Key key = KeyNames.key(last);
        if (key == null) {
            throw new IllegalArgumentException("'" + last + "' in '" + text + "' is not a key a chord can end in");
        }
        return new Shortcut(mods, key);
    }
}
