package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.os.KeyLegends;
import dev.vexelray.os.NativePlatform;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * How each key of a chord is drawn on one platform: a cap with its symbol drawn on it ({@code ⇧}, {@code ↵}), or a
 * cap with the key's legend printed on it ({@code Ctrl}, {@code F5}, {@code S}, {@code [}). Every key is a cap, as
 * every key on a keyboard is. {@link ChordView} draws a chord from it.
 *
 * <p><b>Per keyboard, because platforms differ.</b> A PC keyboard prints Ctrl and Alt; a Mac's prints {@code ⌃} and
 * {@code ⌥}, and calls Super {@code ⌘}. So there is a table per {@link KeyLegends}, and the host's is the one its OS
 * module names ({@link #host()}); nothing here asks which OS it is on. Only the face changes: a chord's spelling
 * ({@link Shortcut#text()}), what a settings file stores and what a screen reader says, is one on every platform.
 *
 * <p><b>Words are written live, not baked.</b> A cap that spells its key is drawn by {@link ChordView} in the drawn
 * caps' proportions and as wide as its word, the word in the GUI's own font, so it is sharp at a menu's size and the
 * same on every build machine. A key with no symbol drawn for it is printed with its name.
 *
 * <p>A table, not a switch: the faces are data about {@link Key}, and a key added to tactroller later is printed with
 * its name until somebody draws it a symbol.
 */
public final class KeyCaps {

    /** One key's face: a cap with a drawn symbol ({@code icon}), or one with its legend printed on it ({@code word}). */
    public record Face(IconSource icon, String word) {

        /** A cap whose drawing is the key: {@code ⇧}. */
        public static Face cap(IconSource icon) {
            return new Face(icon, null);
        }

        /** A cap with {@code word} printed on it, as wide as the word: {@code Ctrl}, {@code F5}, {@code S}. */
        public static Face word(String word) {
            return new Face(null, word);
        }
    }

    /** The order modifiers are drawn in. The same on every desktop so far, Ctrl ⌃, Alt ⌥, Shift ⇧, Super ⌘. */
    private static final Modifier[] ORDER = {Modifier.CONTROL, Modifier.ALT, Modifier.SHIFT, Modifier.SUPER};

    private static final Map<KeyLegends, KeyCaps> TABLES = new EnumMap<>(KeyLegends.class);

    static {
        TABLES.put(KeyLegends.WORDS, words());
        TABLES.put(KeyLegends.SYMBOLS, symbols());
    }

    private final Map<Modifier, Face> modifiers = new EnumMap<>(Modifier.class);
    private final Map<Key, Face> keys = new EnumMap<>(Key.class);

    private KeyCaps() {
    }

    /** The faces of a keyboard printed with {@code legends}. */
    public static KeyCaps of(KeyLegends legends) {
        return TABLES.get(legends);
    }

    /**
     * The faces of the keyboard this process runs on, as its OS module says ({@link NativePlatform#keyLegends()}).
     * With no OS module on the class path (a headless test) it is the PC keyboard's, which is what most are.
     */
    public static KeyCaps host() {
        return Host.CAPS;
    }

    /** Asked once, on first use, rather than when the class loads. */
    private static final class Host {
        static final KeyCaps CAPS = of(ServiceLoader.load(NativePlatform.class).findFirst()
                .map(NativePlatform::keyLegends).orElse(KeyLegends.WORDS));
    }

    /** {@code m}'s face. */
    public Face face(Modifier m) {
        return modifiers.get(m);
    }

    /** {@code k}'s face: its symbol, or its name printed. */
    public Face face(Key k) {
        Face f = keys.get(k);
        return f != null ? f : Face.word(Shortcut.of(k).text());
    }

    /** The faces of {@code chord}, modifiers first, in the order this keyboard's chords are written. */
    public List<Face> faces(Shortcut chord) {
        List<Face> out = new ArrayList<>(chord.modifiers().size() + 1);
        for (Modifier m : ORDER) {
            if (chord.modifiers().contains(m)) {
                out.add(face(m));
            }
        }
        out.add(face(chord.key()));
        return out;
    }

    // ------------------------------------------------------------------ the tables

    /** The keys every keyboard draws alike: the symbol caps, and the function keys as words. */
    private static KeyCaps common() {
        KeyCaps t = new KeyCaps();
        t.modifiers.put(Modifier.SHIFT, Face.cap(Icon.KEY_SHIFT));
        t.keys.put(Key.ENTER, Face.cap(Icon.KEY_ENTER));
        t.keys.put(Key.NUMPAD_ENTER, Face.cap(Icon.KEY_ENTER));
        t.keys.put(Key.ESCAPE, Face.cap(Icon.KEY_ESC));
        t.keys.put(Key.SPACE, Face.cap(Icon.KEY_SPACE));
        t.keys.put(Key.INSERT, Face.cap(Icon.KEY_INSERT));
        t.keys.put(Key.DELETE, Face.cap(Icon.KEY_DELETE));
        t.keys.put(Key.HOME, Face.cap(Icon.KEY_HOME));
        t.keys.put(Key.PAGE_UP, Face.cap(Icon.KEY_PAGEUP));
        t.keys.put(Key.PAGE_DOWN, Face.cap(Icon.KEY_PAGEDOWN));
        for (Key k : Key.values()) {
            if (k.name().matches("F\\d+")) {
                t.keys.put(k, Face.word(k.name()));
            }
        }
        return t;
    }

    /**
     * A PC keyboard, as its caps are printed: Ctrl and Alt in words. Its Super key carries a logo, which has no
     * symbol drawn, so it is printed with the chord's own word for it.
     */
    private static KeyCaps words() {
        KeyCaps t = common();
        t.modifiers.put(Modifier.CONTROL, Face.word("Ctrl"));
        t.modifiers.put(Modifier.ALT, Face.word("Alt"));
        t.modifiers.put(Modifier.SUPER, Face.word("Super"));
        return t;
    }

    /** An Apple keyboard's symbols, {@code ⌃ ⌥ ⇧}. {@code ⌘} has no cap drawn yet, so it is a word until it has. */
    private static KeyCaps symbols() {
        KeyCaps t = common();
        t.modifiers.put(Modifier.CONTROL, Face.cap(Icon.KEY_CTRL));
        t.modifiers.put(Modifier.ALT, Face.cap(Icon.KEY_ALT));
        t.modifiers.put(Modifier.SUPER, Face.word("Cmd"));
        return t;
    }
}
