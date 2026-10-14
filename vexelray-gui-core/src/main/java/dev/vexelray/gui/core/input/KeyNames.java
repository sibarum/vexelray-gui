package dev.vexelray.gui.core.input;

import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The words a chord is written in — {@code Ctrl+Shift+K}, {@code Alt+PageDown}, {@code Ctrl+=} — both ways.
 *
 * <p>One spelling for reading and for storing, deliberately. A keymap saved as {@code CONTROL+SHIFT+K} and shown
 * as {@code Ctrl+Shift+K} is two vocabularies that someone eventually has to keep in step, and a settings file is
 * the place a person most often reads a chord they did not type. So the stored form is the shown form, and the
 * parser is the lenient side: it also takes the enum's own names ({@code PAGE_DOWN}, {@code CONTROL}) and the
 * common aliases ({@code Control}, {@code Escape}, {@code Win}, {@code Cmd}), in any case.
 *
 * <p>A table rather than a switch: the names are data about {@link Key}, every key has one, and a key appended
 * to tactroller later falls back to a name derived from its constant until somebody gives it a better one.
 */
final class KeyNames {

    /** The order modifiers are written in, which is the order every desktop writes them in. */
    static final Modifier[] ORDER = {Modifier.CONTROL, Modifier.ALT, Modifier.SHIFT, Modifier.SUPER};

    private static final Map<Modifier, String> MOD_NAMES = new EnumMap<>(Modifier.class);
    private static final Map<Key, String> KEY_NAMES = new EnumMap<>(Key.class);
    private static final Map<String, Modifier> MODS_BY_NAME = new HashMap<>();
    private static final Map<String, Key> KEYS_BY_NAME = new HashMap<>();

    static {
        MOD_NAMES.put(Modifier.CONTROL, "Ctrl");
        MOD_NAMES.put(Modifier.ALT, "Alt");
        MOD_NAMES.put(Modifier.SHIFT, "Shift");
        MOD_NAMES.put(Modifier.SUPER, "Super");
        for (Modifier m : Modifier.values()) {
            MODS_BY_NAME.put(fold(MOD_NAMES.get(m)), m);
            MODS_BY_NAME.put(fold(m.name()), m);
        }
        MODS_BY_NAME.put("control", Modifier.CONTROL);
        MODS_BY_NAME.put("win", Modifier.SUPER);
        MODS_BY_NAME.put("cmd", Modifier.SUPER);
        MODS_BY_NAME.put("meta", Modifier.SUPER);
        MODS_BY_NAME.put("option", Modifier.ALT);

        for (Key k : Key.values()) {
            KEY_NAMES.put(k, derived(k));
        }
        name(Key.ESCAPE, "Esc");
        name(Key.BACKSPACE, "Backspace");
        name(Key.PAGE_UP, "PageUp");
        name(Key.PAGE_DOWN, "PageDown");
        name(Key.CAPS_LOCK, "CapsLock");
        name(Key.NUMPAD_ADD, "NumAdd");
        name(Key.NUMPAD_SUBTRACT, "NumSubtract");
        name(Key.NUMPAD_MULTIPLY, "NumMultiply");
        name(Key.NUMPAD_DIVIDE, "NumDivide");
        name(Key.NUMPAD_DECIMAL, "NumDecimal");
        name(Key.NUMPAD_ENTER, "NumEnter");
        // Punctuation is written as itself: nobody reads "Ctrl+EQUAL" faster than "Ctrl+=". None of these is
        // '+', which is what keeps the separator unambiguous (the key that types '+' is EQUAL, shifted).
        name(Key.MINUS, "-");
        name(Key.EQUAL, "=");
        name(Key.LEFT_BRACKET, "[");
        name(Key.RIGHT_BRACKET, "]");
        name(Key.BACKSLASH, "\\");
        name(Key.SEMICOLON, ";");
        name(Key.APOSTROPHE, "'");
        name(Key.COMMA, ",");
        name(Key.PERIOD, ".");
        name(Key.SLASH, "/");
        name(Key.GRAVE_ACCENT, "`");
        for (Key k : Key.values()) {
            KEYS_BY_NAME.put(fold(KEY_NAMES.get(k)), k);
            KEYS_BY_NAME.putIfAbsent(fold(k.name()), k);
        }
        KEYS_BY_NAME.putIfAbsent("escape", Key.ESCAPE);
        KEYS_BY_NAME.putIfAbsent("return", Key.ENTER);
        KEYS_BY_NAME.putIfAbsent("del", Key.DELETE);
        KEYS_BY_NAME.putIfAbsent("ins", Key.INSERT);
        KEYS_BY_NAME.putIfAbsent("pgup", Key.PAGE_UP);
        KEYS_BY_NAME.putIfAbsent("pgdn", Key.PAGE_DOWN);
    }

    private KeyNames() {
    }

    private static void name(Key k, String name) {
        KEY_NAMES.put(k, name);
    }

    /** {@code A}, {@code 7}, {@code F5}, {@code Num7}, {@code Left}, {@code Insert}: the constant, made readable. */
    private static String derived(Key k) {
        String n = k.name();
        if (n.startsWith("DIGIT_")) {
            return n.substring("DIGIT_".length());
        }
        if (n.startsWith("NUMPAD_")) {
            return "Num" + title(n.substring("NUMPAD_".length()));
        }
        return title(n);
    }

    private static String title(String constant) {
        StringBuilder out = new StringBuilder();
        for (String part : constant.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            out.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String fold(String s) {
        return s.toLowerCase(Locale.ROOT).replace("_", "");
    }

    static String of(Modifier m) {
        return MOD_NAMES.get(m);
    }

    static String of(Key k) {
        return KEY_NAMES.get(k);
    }

    /** The modifier written {@code word}, or null if it names none. */
    static Modifier modifier(String word) {
        return MODS_BY_NAME.get(fold(word));
    }

    /**
     * The key written {@code word}, or null if it names none — including a modifier's own key, which is never a
     * chord's key: pressed alone it arms the next chord and is routed nowhere ({@link Modifier#from} says which).
     */
    static Key key(String word) {
        Key k = KEYS_BY_NAME.get(fold(word));
        return k == null || !Modifier.from(java.util.Set.of(k)).isEmpty() ? null : k;
    }
}
