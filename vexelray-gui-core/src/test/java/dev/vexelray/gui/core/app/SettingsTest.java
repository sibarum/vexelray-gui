package dev.vexelray.gui.core.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings store: typed values in, the same values out after a save and a fresh open — and every failure
 * mode (missing file, malformed value, corrupt file) is a default, never an exception at startup.
 */
class SettingsTest {

    @Test
    void valuesRoundTripThroughSaveAndReopen(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings s = Settings.at(file);
        s.putString("window.title", "editor")
                .putInt("window.x", 120)
                .putInt("window.y", -45)
                .putLong("session.stamp", 1234567890123L)
                .putFloat("zoom", 1.25f)
                .putBoolean("wrap", true)
                .putList("open.files", List.of("C:\\a b\\one.txt", "/home/u/two = three.md"));
        s.save();

        Settings back = Settings.at(file);
        assertEquals("editor", back.getString("window.title", "?"));
        assertEquals(120, back.getInt("window.x", 0));
        assertEquals(-45, back.getInt("window.y", 0));
        assertEquals(1234567890123L, back.getLong("session.stamp", 0L));
        assertEquals(1.25f, back.getFloat("zoom", 0f), 0.0001f);
        assertTrue(back.getBoolean("wrap", false));
        assertEquals(List.of("C:\\a b\\one.txt", "/home/u/two = three.md"), back.getList("open.files"),
                "paths with spaces and '=' survive — order preserved");
    }

    @Test
    void aMissingFileIsAnEmptyStore(@TempDir Path dir) {
        Settings s = Settings.at(dir.resolve("never-written.properties"));
        assertEquals("fallback", s.getString("anything", "fallback"));
        assertEquals(7, s.getInt("anything", 7));
        assertEquals(List.of(), s.getList("anything"));
        assertFalse(s.has("anything"));
    }

    @Test
    void aMalformedValueFallsBackToTheDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "window.x=not-a-number\nwrap=yes-ish\n");
        Settings s = Settings.at(file);
        assertEquals(42, s.getInt("window.x", 42), "malformed is treated as missing");
        assertFalse(s.getBoolean("wrap", false), "non-'true' parses false, the parseBoolean contract");
    }

    @Test
    void aCorruptFileIsAnEmptyStoreNotALaunchFailure(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.properties");
        Files.write(file, new byte[] {0x02, 0x00, (byte) 0xFF, 0x1F});
        Settings s = Settings.at(file);   // must not throw
        assertEquals("d", s.getString("k", "d"));
    }

    @Test
    void saveCreatesTheDotDirectoryButOpenDoesNot(@TempDir Path dir) {
        Path file = dir.resolve(".myapp").resolve("settings.properties");
        Settings s = Settings.at(file);
        assertFalse(Files.exists(file.getParent()), "merely reading settings leaves no mark");

        s.putInt("n", 1).save();
        assertTrue(Files.isRegularFile(file), "save created the directory and the file");
    }

    @Test
    void removingAKeyRestoresTheDefault(@TempDir Path dir) {
        Settings s = Settings.at(dir.resolve("settings.properties"));
        s.putInt("n", 9);
        assertEquals(9, s.getInt("n", 1));
        s.remove("n");
        assertEquals(1, s.getInt("n", 1));
    }

    @Test
    void anEmptyListRemovesTheKeyAndSeparatorsAreRejected(@TempDir Path dir) {
        Settings s = Settings.at(dir.resolve("settings.properties"));
        s.putList("files", List.of("a"));
        s.putList("files", List.of());
        assertFalse(s.has("files"), "an empty list is an absent key");
        assertThrows(IllegalArgumentException.class, () -> s.putList("files", List.of("bad\u001Felement")));
    }

    @Test
    void resavingOverwritesInPlace(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings s = Settings.at(file);
        s.putInt("n", 1).save();
        s.putInt("n", 2).save();
        assertEquals(2, Settings.at(file).getInt("n", 0), "the second save replaced the first");
    }
}
