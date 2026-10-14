package dev.vexelray.gui.core.input;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A chord's one spelling, for the screen and for a settings file alike. */
class ShortcutTextTest {

    @Test
    void modifiersAreWrittenInTheDesktopOrderWhateverOrderTheyWereGiven() {
        assertEquals("Ctrl+Alt+Shift+Super+K",
                Shortcut.of(Key.K, Modifier.SUPER, Modifier.SHIFT, Modifier.ALT, Modifier.CONTROL).text());
        assertEquals("Ctrl+Shift+", Shortcut.prefix(Set.of(Modifier.SHIFT, Modifier.CONTROL)));
        assertEquals("", Shortcut.prefix(Set.of()));
    }

    @Test
    void keysReadAsPeopleWriteThem() {
        assertEquals("Ctrl+=", Shortcut.of(Key.EQUAL, Modifier.CONTROL).text());
        assertEquals("Alt+PageDown", Shortcut.of(Key.PAGE_DOWN, Modifier.ALT).text());
        assertEquals("Ctrl+7", Shortcut.of(Key.DIGIT_7, Modifier.CONTROL).text());
        assertEquals("Num7", Shortcut.of(Key.NUMPAD_7).text());
        assertEquals("Esc", Shortcut.of(Key.ESCAPE).text());
        assertEquals("F12", Shortcut.of(Key.F12).text());
        assertEquals("Ctrl+,", Shortcut.of(Key.COMMA, Modifier.CONTROL).text());
    }

    /** Every key under every modifier set: whatever a recorder can produce, a settings file can give back. */
    @Test
    void everyChordRoundTrips() {
        for (Key k : Key.values()) {
            if (!Modifier.from(Set.of(k)).isEmpty()) {
                continue;   // a modifier's own key is never a chord's key
            }
            for (int bits = 0; bits < 16; bits++) {
                EnumSet<Modifier> mods = EnumSet.noneOf(Modifier.class);
                for (Modifier m : Modifier.values()) {
                    if ((bits & (1 << m.ordinal())) != 0) {
                        mods.add(m);
                    }
                }
                Shortcut s = new Shortcut(mods, k);
                assertEquals(s, Shortcut.parse(s.text()), s.text());
            }
        }
    }

    @Test
    void parsingIsLenientAboutCaseSpacesAndAliases() {
        Shortcut ctrlShiftPgDn = Shortcut.of(Key.PAGE_DOWN, Modifier.CONTROL, Modifier.SHIFT);
        assertEquals(ctrlShiftPgDn, Shortcut.parse("ctrl + shift + pagedown"));
        assertEquals(ctrlShiftPgDn, Shortcut.parse("CONTROL+SHIFT+PAGE_DOWN"));
        assertEquals(ctrlShiftPgDn, Shortcut.parse("Control+Shift+PgDn"));
        assertEquals(Shortcut.of(Key.ESCAPE), Shortcut.parse("Escape"));
        assertEquals(Shortcut.of(Key.Q, Modifier.SUPER), Shortcut.parse("Cmd+Q"));
        assertEquals(Shortcut.of(Key.MINUS, Modifier.CONTROL), Shortcut.parse("Ctrl+-"));
    }

    @Test
    void aModifierAloneOrAnUnknownWordIsNotAChord() {
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Shift"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Ctrl+LeftShift"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Hyper+K"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Ctrl+"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse(" "));
    }
}
