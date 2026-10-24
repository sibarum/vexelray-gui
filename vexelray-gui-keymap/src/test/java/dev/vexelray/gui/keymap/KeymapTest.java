package dev.vexelray.gui.keymap;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Gesture;
import dev.vexelray.gui.core.input.Shortcut;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The action mapper: handlers by name, chords the user owns, one chord per action per place. */
class KeymapTest {

    private static final Shortcut CTRL_S = Shortcut.of(Key.S, Modifier.CONTROL);
    private static final Shortcut CTRL_K = Shortcut.of(Key.K, Modifier.CONTROL);
    private static final Shortcut CTRL_O = Shortcut.of(Key.O, Modifier.CONTROL);

    @Test
    void anActionRunsOnItsDefaultChord() {
        try (Headless h = new Headless()) {
            List<String> log = new ArrayList<>();
            Keymap keys = new Keymap(h.gui);
            keys.action("file.save", "Save", CTRL_S).on(() -> log.add("save"));

            h.frame().chord(Key.S, Key.LEFT_CONTROL);

            assertEquals(List.of("save"), log);
        }
    }

    @Test
    void rebindingMovesTheClaimAndTheHandlerNeverHears() {
        try (Headless h = new Headless()) {
            List<String> log = new ArrayList<>();
            Keymap keys = new Keymap(h.gui);
            Action save = keys.action("file.save", "Save", CTRL_S).on(() -> log.add("save"));

            keys.bind(save, List.of(CTRL_K));
            h.frame().chord(Key.S, Key.LEFT_CONTROL).chord(Key.K, Key.LEFT_CONTROL);

            assertEquals(List.of("save"), log, "Ctrl+S was released and Ctrl+K claimed");
            assertEquals(List.of(CTRL_K), save.chords());
            assertTrue(save.customized());
        }
    }

    @Test
    void bindingTakesTheChordFromWhoeverHadItInThatPlaceAndSaysWho() {
        try (Headless h = new Headless()) {
            List<String> log = new ArrayList<>();
            Keymap keys = new Keymap(h.gui);
            Action save = keys.action("file.save", "Save", CTRL_S).on(() -> log.add("save"));
            Action open = keys.action("file.open", "Open", CTRL_O).on(() -> log.add("open"));

            Keymap.Bound b = keys.bind(open, List.of(CTRL_O, CTRL_S));
            h.frame().chord(Key.S, Key.LEFT_CONTROL);

            assertEquals(List.of(save), b.displaced());
            assertEquals(List.of(), save.chords());
            assertEquals(List.of("open"), log);
        }
    }

    @Test
    void bindingBackToTheDefaultsIsNoChangeAtAll() {
        try (Headless h = new Headless()) {
            Keymap keys = new Keymap(h.gui);
            Action save = keys.action("file.save", "Save", CTRL_S).on(() -> { });

            keys.bind(save, List.of(CTRL_K));
            keys.bind(save, List.of(CTRL_S));

            assertFalse(save.customized());
            assertEquals("", keys.text());
        }
    }

    @Test
    void pausedLetsGoOfEveryChordAndResumingTakesThemBackUnchanged() {
        try (Headless h = new Headless()) {
            List<String> log = new ArrayList<>();
            Keymap keys = new Keymap(h.gui);
            Action save = keys.action("file.save", "Save", CTRL_S).on(() -> log.add("save"));

            keys.paused(true);
            h.frame().chord(Key.S, Key.LEFT_CONTROL);
            keys.paused(false);
            h.frame().chord(Key.S, Key.LEFT_CONTROL);

            assertEquals(List.of("save"), log, "only the chord pressed after resuming reached the action");
            assertEquals(List.of(CTRL_S), save.chords());
            assertEquals("", keys.text(), "pausing saves nothing");
        }
    }

    @Test
    void resetTakesTheDefaultsBack() {
        try (Headless h = new Headless()) {
            Keymap keys = new Keymap(h.gui);
            Action save = keys.action("file.save", "Save", CTRL_S).on(() -> { });
            Action open = keys.action("file.open", "Open", CTRL_O).on(() -> { });
            keys.bind(open, List.of(CTRL_S));

            Keymap.Bound b = keys.reset(save);

            assertEquals(List.of(CTRL_S), save.chords());
            assertEquals(List.of(), open.chords());
            assertEquals(List.of(open), b.displaced());
        }
    }

    @Test
    void twoDefaultsSharingAChordInOnePlaceIsRefused() {
        try (Headless h = new Headless()) {
            Keymap keys = new Keymap(h.gui);
            keys.action("file.save", "Save", CTRL_S);

            assertThrows(IllegalStateException.class, () -> keys.action("file.sync", "Sync", CTRL_S));
        }
    }

    @Test
    void aNarrowerPlaceMayShareAChordAndWinsWhileItApplies() {
        try (Headless h = new Headless()) {
            List<String> log = new ArrayList<>();
            Node field = h.gui.box();
            h.gui.root().children(field);
            h.gui.focusable(field, true);
            Keymap keys = new Keymap(h.gui);
            keys.action("editor.indent", "Indent", Shortcut.of(Key.I, Modifier.CONTROL))
                    .within(h.gui, field, ClaimScope.FOCUSED).on(() -> log.add("indent"));
            keys.action("view.info", "Info", Shortcut.of(Key.I, Modifier.CONTROL)).on(() -> log.add("info"));

            h.frame().chord(Key.I, Key.LEFT_CONTROL);
            h.gui.focus(field);
            h.frame().chord(Key.I, Key.LEFT_CONTROL);

            assertEquals(List.of("info", "indent"), log);
        }
    }

    @Test
    void anActionWithNoHandlerSwallowsNothing() {
        try (Headless h = new Headless()) {
            List<String> log = new ArrayList<>();
            Keymap keys = new Keymap(h.gui);
            keys.action("file.save", "Save", CTRL_S);
            h.gui.shortcut(CTRL_S, () -> log.add("shortcut"));

            h.frame().chord(Key.S, Key.LEFT_CONTROL);

            assertEquals(List.of("shortcut"), log);
        }
    }

    /** The keymap claims on a node of its own, so releasing a chord never takes the root's claim with it. */
    @Test
    void rebindingAwayFromUndoLeavesTheApplicationsUndoAlone() {
        try (Headless h = new Headless()) {
            List<String> log = new ArrayList<>();
            History history = new History();
            history.record(new dev.vexelray.gui.core.edit.Change() {
                @Override
                public dev.vexelray.gui.core.edit.Change apply() {
                    log.add("undo");
                    return this;
                }
            });
            h.gui.history(history);
            Keymap keys = new Keymap(h.gui);
            Action panic = keys.action("panic", "Panic").on(() -> log.add("panic"));
            keys.bind(panic, List.of(Shortcut.of(Key.Z, Modifier.CONTROL, Modifier.ALT)));
            keys.unbind(panic);

            h.frame().chord(Key.Z, Key.LEFT_CONTROL);

            assertEquals(List.of("undo"), log);
        }
    }

    @Test
    void anAttachedWindowHearsTheSameChords() {
        try (Headless a = new Headless(); Headless b = new Headless()) {
            List<String> log = new ArrayList<>();
            Keymap keys = new Keymap(a.gui).attach(b.gui);
            Action save = keys.action("file.save", "Save", CTRL_S).on(() -> log.add("save"));
            keys.bind(save, List.of(CTRL_K));

            a.frame().chord(Key.K, Key.LEFT_CONTROL);
            b.frame().chord(Key.K, Key.LEFT_CONTROL);
            b.chord(Key.S, Key.LEFT_CONTROL);

            assertEquals(List.of("save", "save"), log);
        }
    }

    @Test
    void theTextFormRoundTripsAndKeepsWhatItDoesNotKnow() {
        try (Headless h = new Headless()) {
            Keymap keys = new Keymap(h.gui);
            Action save = keys.action("file.save", "Save", CTRL_S);
            Action open = keys.action("file.open", "Open", CTRL_O);
            keys.load("""
                    # a profile written by a newer build
                    file.save = Ctrl+K Ctrl+Shift+S
                    file.open =
                    plugin.frob = Alt+F
                    nonsense without an equals sign
                    file.close = Hyper+W
                    """);

            assertEquals(List.of(CTRL_K, Shortcut.of(Key.S, Modifier.CONTROL, Modifier.SHIFT)), save.chords());
            assertEquals(List.of(), open.chords(), "an empty line is unbound, not defaults");
            assertEquals("""
                    file.open =
                    file.save = Ctrl+K Ctrl+Shift+S
                    plugin.frob = Alt+F
                    """, keys.text(), "the unknown action survives; the unreadable line does not");
        }
    }

    @Test
    void persistedChordsComeBackInTheNextRun(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        try (Headless h = new Headless()) {
            Keymap keys = new Keymap(h.gui).persist(Settings.at(file));
            Action save = keys.action("file.save", "Save", CTRL_S);
            keys.bind(save, List.of(CTRL_K));
        }
        try (Headless h = new Headless()) {
            List<String> log = new ArrayList<>();
            Keymap keys = new Keymap(h.gui).persist(Settings.at(file));
            keys.action("file.save", "Save", CTRL_S).on(() -> log.add("save"));

            h.frame().chord(Key.K, Key.LEFT_CONTROL);

            assertEquals(List.of("save"), log);
        }
    }

    @Test
    void aGestureIsATriggerLikeAChordAndIsStoredByName() {
        try (Headless h = new Headless()) {
            Gesture left = Gesture.swipe(3, Gesture.Motion.SWIPE_LEFT);
            Gesture tap = Gesture.doubleTap(2);
            Keymap keys = new Keymap(h.gui);
            Action back = keys.action("view.previous", "Previous", Shortcut.of(Key.LEFT), left).on(() -> { });
            Action fit = keys.action("view.fit", "Fit").on(() -> { });

            Keymap.Bound b = keys.bind(fit, List.of(tap, left));

            assertEquals(List.of(back), b.displaced(), "a gesture is taken from its holder as a chord is");
            assertEquals(List.of(Shortcut.of(Key.LEFT)), back.chords());
            assertEquals("view.fit = TwoFingerDoubleTap ThreeFingerSwipeLeft\n"
                    + "view.previous = Left\n", keys.text());

            Keymap again = new Keymap(h.gui);
            Action fit2 = again.action("view.fit", "Fit").on(() -> { });
            again.load(keys.text());
            assertEquals(List.of(tap, left), fit2.chords(), "read back by name");
        }
    }

    @Test
    void anOrderedActionRunsOnTheGuiThreadAndTheRestOnAWorker() {
        List<Runnable> held = new ArrayList<>();
        try (Headless h = new Headless(held::add)) {
            List<String> log = new ArrayList<>();
            Keymap keys = new Keymap(h.gui);
            keys.action("file.save", "Save", CTRL_S).on(() -> log.add("save"));
            Action paste = keys.action("edit.paste", "Paste", CTRL_K).on(() -> log.add("paste")).ordered();

            h.frame().chord(Key.S, Key.LEFT_CONTROL).chord(Key.K, Key.LEFT_CONTROL);
            assertEquals(List.of("paste"), log, "run inline, in the frame that dispatched it");

            List.copyOf(held).forEach(Runnable::run);
            assertTrue(log.contains("save"), "the other waited for a worker");

            log.clear();
            held.clear();
            keys.bind(paste, List.of(CTRL_O));
            h.frame().chord(Key.O, Key.LEFT_CONTROL);
            assertEquals(List.of("paste"), log, "still ordered on its new chord");
        }
    }
}
