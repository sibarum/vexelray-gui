package dev.vexelray.gui.core;

import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The simplified-graphics switch: who decides it, and the motion knobs it overrides without forgetting them. */
class SimplifiedGraphicsTest {

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    @Test
    void offByDefaultAndSetFromCode() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            assertFalse(gui.simplifiedGraphics());
            assertTrue(gui.simplifiedGraphics(true).simplifiedGraphics());
            assertFalse(gui.simplifiedGraphics(false).simplifiedGraphics());
        }
    }

    @Test
    void theUsersSavedChoiceIsReadAndAMissingOneLeavesItAlone(@TempDir Path dir) {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Settings empty = Settings.at(dir.resolve("none.properties"));
            gui.simplifiedGraphics(true).simplifiedGraphics(empty);
            assertTrue(gui.simplifiedGraphics(), "a store without the key says nothing");

            Settings saved = Settings.at(dir.resolve("s.properties")).putBoolean(Gui.SIMPLIFIED_GRAPHICS, false);
            assertFalse(gui.simplifiedGraphics(saved).simplifiedGraphics());
        }
    }

    @Test
    void theRunsPropertyWinsOverCodeAndSettings() {
        String before = System.getProperty(Gui.SIMPLIFIED_GRAPHICS_PROPERTY);
        System.setProperty(Gui.SIMPLIFIED_GRAPHICS_PROPERTY, "true");
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            assertTrue(gui.simplifiedGraphics());
            assertTrue(gui.simplifiedGraphics(false).simplifiedGraphics(), "-D is the run's, and the run wins");
        } finally {
            if (before == null) {
                System.clearProperty(Gui.SIMPLIFIED_GRAPHICS_PROPERTY);
            } else {
                System.setProperty(Gui.SIMPLIFIED_GRAPHICS_PROPERTY, before);
            }
        }
    }

    @Test
    void theFocusRingArrivesWholeInSimplifiedGraphicsAndFadesAgainWithout() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node a = gui.box().width(Length.rem(1)).height(Length.rem(1));
            Node b = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().append(a).append(b);
            gui.focusable(a, true).focusable(b, true);
            gui.focusFade(Duration.ofMillis(500)).simplifiedGraphics(true);
            gui.frame(400f, 100f, SimplifiedGraphicsTest::noText);

            gui.focus(a);
            gui.frame(400f, 100f, SimplifiedGraphicsTest::noText);
            assertEquals(1f, gui.focusState().shown(), "no fade: the ring is whole the frame focus arrives");

            gui.simplifiedGraphics(false);   // the fade the application asked for comes back
            gui.focus(b);
            gui.frame(400f, 100f, SimplifiedGraphicsTest::noText);
            assertTrue(gui.focusState().shown() < 1f, "and the next ring fades in: " + gui.focusState());
        }
    }
}
