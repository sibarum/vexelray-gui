package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.os.KeyLegends;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A chord's keys as the person's own keyboard prints them. */
class KeyCapsTest {

    private static final KeyCaps PC = KeyCaps.of(KeyLegends.WORDS);
    private static final KeyCaps MAC = KeyCaps.of(KeyLegends.SYMBOLS);

    @Test
    void aPcKeyboardPrintsCtrlAltAndTheLetterOnCaps() {
        assertEquals(List.of(KeyCaps.Face.word("Ctrl"), KeyCaps.Face.word("Alt"), KeyCaps.Face.word("K")),
                PC.faces(Shortcut.of(Key.K, Modifier.ALT, Modifier.CONTROL)));
    }

    @Test
    void aMacKeyboardDrawsItsSymbols() {
        assertEquals(KeyCaps.Face.cap(Icon.KEY_CTRL), MAC.face(Modifier.CONTROL));
        assertEquals(KeyCaps.Face.cap(Icon.KEY_ALT), MAC.face(Modifier.ALT));
    }

    @Test
    void theSymbolKeysAreTheSameCapOnEveryKeyboard() {
        for (KeyCaps caps : List.of(PC, MAC)) {
            assertEquals(List.of(KeyCaps.Face.cap(Icon.KEY_SHIFT), KeyCaps.Face.cap(Icon.KEY_ENTER)),
                    caps.faces(Shortcut.of(Key.ENTER, Modifier.SHIFT)));
            assertEquals(KeyCaps.Face.cap(Icon.KEY_ENTER), caps.face(Key.NUMPAD_ENTER));
            assertEquals(KeyCaps.Face.word("F12"), caps.face(Key.F12));
        }
    }

    @Test
    void modifiersAreDrawnInTheOrderChordsAreWrittenWhateverOrderTheyWereGiven() {
        assertEquals(List.of(KeyCaps.Face.word("Ctrl"), KeyCaps.Face.cap(Icon.KEY_SHIFT), KeyCaps.Face.word("Super"),
                        KeyCaps.Face.cap(Icon.KEY_ESC)),
                PC.faces(Shortcut.of(Key.ESCAPE, Modifier.SUPER, Modifier.SHIFT, Modifier.CONTROL)));
    }

    /** Every key has a face on every keyboard: one tactroller adds later is its name until it is given a cap. */
    @Test
    void everyKeyAndModifierHasAFace() {
        for (KeyLegends l : KeyLegends.values()) {
            KeyCaps caps = KeyCaps.of(l);
            for (Modifier m : Modifier.values()) {
                assertNotNull(caps.face(m), l + " " + m);
            }
            for (Key k : Key.values()) {
                KeyCaps.Face f = caps.face(k);
                assertNotNull(f.icon() != null ? f.icon() : f.word(), l + " " + k);
            }
        }
        assertEquals(KeyCaps.Face.word("["), PC.face(Key.LEFT_BRACKET));
    }
}
