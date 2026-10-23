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

/**
 * The two graphics choices, flat shading and reduced motion: that they are independent, that simplified graphics is
 * both, who has the last word on each, and the motion knobs reduced motion overrides without forgetting them.
 */
class GraphicsChoicesTest {

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    private static Gui gui() {
        return new Gui(Atchung.create(), Runnable::run);
    }

    @Test
    void bothOffByDefaultAndEachSetOnItsOwn() {
        try (Gui gui = gui()) {
            assertFalse(gui.flatShading());
            assertFalse(gui.reducedMotion());
            assertFalse(gui.simplifiedGraphics());

            gui.flatShading(true);
            assertTrue(gui.flatShading());
            assertFalse(gui.reducedMotion(), "flat shading says nothing about motion");
            assertFalse(gui.simplifiedGraphics(), "simplified graphics is both, and this is one");

            gui.flatShading(false).reducedMotion(true);
            assertFalse(gui.flatShading(), "reduced motion says nothing about shading");
            assertTrue(gui.reducedMotion());
        }
    }

    @Test
    void simplifiedGraphicsIsBothAtOnce() {
        try (Gui gui = gui()) {
            gui.simplifiedGraphics(true);
            assertTrue(gui.flatShading());
            assertTrue(gui.reducedMotion());
            assertTrue(gui.simplifiedGraphics());

            gui.reducedMotion(false);
            assertFalse(gui.simplifiedGraphics(), "one taken back, and it is no longer both");
            assertTrue(gui.flatShading());

            gui.simplifiedGraphics(false);
            assertFalse(gui.flatShading());
            assertFalse(gui.reducedMotion());
        }
    }

    @Test
    void theUsersSavedChoicesAreReadPresetFirstThenEachOverIt(@TempDir Path dir) {
        try (Gui gui = gui()) {
            gui.simplifiedGraphics(true).graphics(Settings.at(dir.resolve("none.properties")));
            assertTrue(gui.simplifiedGraphics(), "a store without the keys says nothing");

            gui.graphics(Settings.at(dir.resolve("off.properties")).putBoolean(Gui.SIMPLIFIED_GRAPHICS, false));
            assertFalse(gui.flatShading());
            assertFalse(gui.reducedMotion());

            gui.graphics(Settings.at(dir.resolve("motion.properties")).putBoolean(Gui.REDUCED_MOTION, true));
            assertFalse(gui.flatShading(), "a key for one choice leaves the other as it was");
            assertTrue(gui.reducedMotion());

            gui.graphics(Settings.at(dir.resolve("both.properties"))
                    .putBoolean(Gui.SIMPLIFIED_GRAPHICS, true)
                    .putBoolean(Gui.FLAT_SHADING, false));
            assertFalse(gui.flatShading(), "a choice's own key wins over the preset");
            assertTrue(gui.reducedMotion());
        }
    }

    @Test
    void theRunsPresetDecidesBothAndAChoicesOwnPropertyWinsOverIt() {
        withProperties(() -> {
            System.setProperty(Gui.SIMPLIFIED_GRAPHICS_PROPERTY, "true");
            try (Gui gui = gui()) {
                assertTrue(gui.simplifiedGraphics());
                assertTrue(gui.simplifiedGraphics(false).simplifiedGraphics(), "-D is the run's, and the run wins");
            }
            System.setProperty(Gui.FLAT_SHADING_PROPERTY, "false");
            try (Gui gui = gui()) {
                assertFalse(gui.flatShading(true).flatShading(), "the choice's own property over the preset");
                assertTrue(gui.reducedMotion(false).reducedMotion(), "and the preset still decides the other");
            }
        });
    }

    @Test
    void theOsSaysWhatReducedMotionIsUntilSomethingAsks(@TempDir Path dir) {
        try (Gui gui = gui()) {
            gui.systemReducedMotion(true);
            assertTrue(gui.reducedMotion(), "the user told the OS, and that is the default");
            assertFalse(gui.flatShading(), "the OS has no word on shading");

            gui.graphics(Settings.at(dir.resolve("none.properties")));
            assertTrue(gui.reducedMotion(), "settings without the key ask nothing, so the OS still decides");

            gui.reducedMotion(false);
            assertFalse(gui.reducedMotion(), "the code asking says more than the OS");
            gui.systemReducedMotion(true);
            assertFalse(gui.reducedMotion(), "and once asked, the OS is only a default no longer in use");
        }
        try (Gui gui = gui()) {
            gui.systemReducedMotion(true)
                    .graphics(Settings.at(dir.resolve("s.properties")).putBoolean(Gui.REDUCED_MOTION, false));
            assertFalse(gui.reducedMotion(), "the user's own setting says more than the OS");
        }
        try (Gui gui = gui()) {
            gui.systemReducedMotion(null);
            assertFalse(gui.reducedMotion(), "an OS that cannot say leaves motion on");
        }
    }

    @Test
    void theRunSaysMoreThanTheOs() {
        withProperties(() -> {
            System.setProperty(Gui.REDUCED_MOTION_PROPERTY, "false");
            try (Gui gui = gui()) {
                assertFalse(gui.systemReducedMotion(true).reducedMotion());
            }
        });
    }

    @Test
    void theFocusRingArrivesWholeWithMotionReducedAndFadesAgainWithout() {
        try (Gui gui = gui()) {
            Node a = gui.box().width(Length.rem(1)).height(Length.rem(1));
            Node b = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().append(a).append(b);
            gui.focusable(a, true).focusable(b, true);
            gui.focusFade(Duration.ofMillis(500)).reducedMotion(true);
            gui.frame(400f, 100f, GraphicsChoicesTest::noText);

            gui.focus(a);
            gui.frame(400f, 100f, GraphicsChoicesTest::noText);
            assertEquals(1f, gui.focusState().shown(), "no fade: the ring is whole the frame focus arrives");

            gui.reducedMotion(false).flatShading(true);   // the fade the application asked for comes back
            gui.focus(b);
            gui.frame(400f, 100f, GraphicsChoicesTest::noText);
            assertTrue(gui.focusState().shown() < 1f, "flat shading does not stop the fade: " + gui.focusState());
        }
    }

    /** Run {@code body} with the three run properties restored afterwards, whatever it set. */
    private static void withProperties(Runnable body) {
        String[] keys = {Gui.SIMPLIFIED_GRAPHICS_PROPERTY, Gui.FLAT_SHADING_PROPERTY, Gui.REDUCED_MOTION_PROPERTY};
        String[] before = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            before[i] = System.getProperty(keys[i]);
        }
        try {
            body.run();
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (before[i] == null) {
                    System.clearProperty(keys[i]);
                } else {
                    System.setProperty(keys[i], before[i]);
                }
            }
        }
    }
}
