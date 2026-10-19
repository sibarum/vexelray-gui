package dev.vexelray.gui.keymap;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.Shortcut;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Changing a chord by pressing it, from the keyboard alone. */
class KeymapEditorTest {

    private static final Shortcut CTRL_S = Shortcut.of(Key.S, Modifier.CONTROL);
    private static final Shortcut CTRL_O = Shortcut.of(Key.O, Modifier.CONTROL);
    private static final Shortcut CTRL_K = Shortcut.of(Key.K, Modifier.CONTROL);

    private record Fixture(Headless h, Keymap keys, Action save, Action open, KeymapEditor editor,
                           List<String> log) {
    }

    private static Fixture fixture(Headless h) {
        List<String> log = new ArrayList<>();
        Keymap keys = new Keymap(h.gui);
        Action save = keys.action("file.save", "Save", CTRL_S).on(() -> log.add("save"));
        Action open = keys.action("file.open", "Open", CTRL_O).on(() -> log.add("open"));
        KeymapEditor editor = new KeymapEditor(h.gui, keys);
        h.gui.root().children(editor.node());
        h.frame();
        return new Fixture(h, keys, save, open, editor, log);
    }

    /** Focus {@code chip} and press Enter on it, arming the recorder. */
    private static void arm(Headless h, Node chip) {
        h.gui.focus(chip);
        h.frame().chord(Key.ENTER);
    }

    @Test
    void pressingAChordWhileRecordingRebindsAndIsNotPerformed() {
        try (Headless h = new Headless()) {
            Fixture f = fixture(h);

            arm(h, f.editor().chip(f.save(), 0));
            h.chord(Key.K, Key.LEFT_CONTROL);
            h.chord(Key.K, Key.LEFT_CONTROL);

            assertEquals(List.of(CTRL_K), f.save().chords());
            assertEquals(List.of("save"), f.log(), "the recorded press saved nothing; the next one saved");
        }
    }

    /** Regression: the commit's own rebuild used to take the refocus, and the next rebuild removed that chip. */
    @Test
    void theKeyboardComesBackToTheChipThatWasRecorded() {
        try (Headless h = new Headless()) {
            Fixture f = fixture(h);

            arm(h, f.editor().chip(f.save(), -1));
            h.chord(Key.K, Key.LEFT_CONTROL);

            assertEquals(List.of(CTRL_S, CTRL_K), f.save().chords());
            assertEquals(f.editor().chip(f.save(), 1).id(), h.gui.focusState().nodeId(),
                    "focus is on the new Ctrl+K chip, ready for the next key");
        }
    }

    @Test
    void aChordTheApplicationHoldsIsRecordedRatherThanRun() {
        try (Headless h = new Headless()) {
            Fixture f = fixture(h);

            arm(h, f.editor().chip(f.save(), -1));
            h.chord(Key.O, Key.LEFT_CONTROL);

            assertEquals(List.of(), f.log(), "Ctrl+O was recorded, not opened");
            assertEquals(List.of(CTRL_S, CTRL_O), f.save().chords());
            assertEquals(List.of(), f.open().chords());
            assertTrue(h.text(f.editor().status()).contains("Taken from Open"), h.text(f.editor().status()));
        }
    }

    @Test
    void escapeCancels() {
        try (Headless h = new Headless()) {
            Fixture f = fixture(h);

            arm(h, f.editor().chip(f.save(), 0));
            h.chord(Key.ESCAPE);
            h.chord(Key.S, Key.LEFT_CONTROL);

            assertEquals(List.of(CTRL_S), f.save().chords());
            assertEquals(List.of("save"), f.log(), "after a cancel the chords are live again");
        }
    }

    @Test
    void focusLeavingCancels() {
        try (Headless h = new Headless()) {
            Fixture f = fixture(h);

            arm(h, f.editor().chip(f.save(), 0));
            h.gui.focus(f.editor().chip(f.open(), 0));
            h.frame().chord(Key.S, Key.LEFT_CONTROL);

            assertEquals(List.of(CTRL_S), f.save().chords());
            assertEquals(List.of("save"), f.log());
        }
    }

    @Test
    void deleteOnAChipRemovesItsChord() {
        try (Headless h = new Headless()) {
            Fixture f = fixture(h);

            h.gui.focus(f.editor().chip(f.open(), 0));
            h.frame().chord(Key.DELETE);

            assertEquals(List.of(), f.open().chords());
            assertTrue(f.open().customized());
        }
    }

    @Test
    void recordingEnterIsPossibleBecauseTheRecorderIsNotTheChip() {
        try (Headless h = new Headless()) {
            Fixture f = fixture(h);

            arm(h, f.editor().chip(f.save(), 0));
            h.chord(Key.ENTER, Key.LEFT_CONTROL);

            assertEquals(List.of(Shortcut.of(Key.ENTER, Modifier.CONTROL)), f.save().chords());
        }
    }

    @Test
    void theChordsLineUpHoweverLongTheNames() {
        try (Headless h = new Headless()) {
            Keymap keys = new Keymap(h.gui);
            Action shortName = keys.action("a", "Save", CTRL_S);
            Action longName = keys.action("b", "Close the menu, leave full screen, or close the viewer", CTRL_O);
            KeymapEditor editor = new KeymapEditor(h.gui, keys);
            h.gui.root().children(editor.node());
            h.frame();

            float a = editor.chip(shortName, 0).layout().rect().x();
            float b = editor.chip(longName, 0).layout().rect().x();
            assertEquals(a, b, 0.5f, "the long name is cut at its column, not pushing its chords right");
        }
    }

    @Test
    void cancelPutsBackWhatTheBindingsWereAtTheCheckpoint() {
        try (Headless h = new Headless()) {
            Fixture f = fixture(h);
            f.keys().bind(f.open(), List.of(CTRL_K));
            f.editor().checkpoint();

            arm(h, f.editor().chip(f.save(), 0));
            h.chord(Key.K, Key.LEFT_CONTROL);
            assertEquals(List.of(), f.open().chords(), "Save took Ctrl+K from Open");
            f.editor().cancel();

            assertEquals(List.of(CTRL_S), f.save().chords(), "the recording is undone");
            assertEquals(List.of(CTRL_K), f.open().chords(), "back to the checkpoint, not to the defaults");
        }
    }
}
