package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The suite's graphics store: what it reads, what it saves, and where its voice sits among a tree's. */
class SuiteGraphicsTest {

    @Test
    void anEmptyStoreHasNoChoiceAndNoRefusal(@TempDir Path dir) {
        SuiteGraphics s = SuiteGraphics.at(dir.resolve("settings.properties"));
        assertNull(s.flatShading());
        assertNull(s.reducedMotion());
        assertFalse(s.offerRefused());
    }

    @Test
    void savedSimplifiedIsReadBackAsBothAndLeavesNoKeyToTalkOverIt(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings.at(file).putBoolean(Gui.FLAT_SHADING, false).save();
        SuiteGraphics s = SuiteGraphics.at(file);
        s.saveSimplified();
        SuiteGraphics back = s.reread();
        assertEquals(Boolean.TRUE, back.flatShading(), "an old key for one choice does not survive the preset");
        assertEquals(Boolean.TRUE, back.reducedMotion());
    }

    @Test
    void aWriteKeepsWhatAnotherApplicationWroteInBetween(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        SuiteGraphics read = SuiteGraphics.at(file);
        Settings.at(file).putString("other.key", "kept").save();   // another application, after this one read
        read.refuseOffers();
        assertEquals("kept", Settings.at(file).getString("other.key", ""));
        assertTrue(read.reread().offerRefused());
    }

    @Test
    void theSuitesVoiceIsOverTheOsAndUnderTheApplication(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings.at(file).putBoolean(Gui.SIMPLIFIED_GRAPHICS, true).save();
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.systemReducedMotion(false);
            SuiteGraphics.at(file).applyTo(gui);
            assertTrue(gui.simplifiedGraphics(), "the user's suite-wide choice over the OS's word");
            gui.flatShading(false);
            assertFalse(gui.flatShading(), "and this application's own choice over the suite's");
            assertTrue(gui.reducedMotion());
        }
    }
}
