package dev.vexelray.gui.core.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-user home directory: {@code $HOME/.{name}}, redirectable, holding as many files as the application
 * wants — and closed to any path that would resolve outside it.
 */
class AppHomeTest {

    @Test
    void theHomeIsADotDirectoryUnderTheUserHome() {
        AppHome home = AppHome.of("vexelray-test-app");
        assertEquals(Path.of(System.getProperty("user.home"), ".vexelray-test-app"), home.dir());
        assertEquals("vexelray-test-app", home.name());
        assertEquals(home.dir().resolve("settings.properties"), home.settings().path());
    }

    @Test
    void aLeadingDotOnTheNameIsOptional() {
        assertEquals(AppHome.of("editor").dir(), AppHome.of(".editor").dir(), "one directory, either spelling");
        assertEquals("editor", AppHome.of(".editor").name(), "the name is reported without its dot");
    }

    @Test
    void aSystemPropertyRedirectsTheHomeWithoutTouchingTheApplication(@TempDir Path dir) {
        Path elsewhere = dir.resolve("portable").resolve("config");
        System.setProperty("vexelray-redirect-test.home", elsewhere.toString());
        try {
            AppHome home = AppHome.of("vexelray-redirect-test");
            assertEquals(elsewhere.toAbsolutePath().normalize(), home.dir(),
                    "{name}.home names the directory itself, not a parent to nest under");
            assertEquals(home.dir().resolve("settings.properties"), home.settings().path());
        } finally {
            System.clearProperty("vexelray-redirect-test.home");
        }
    }

    @Test
    void anExplicitDirectoryNamesItself(@TempDir Path dir) {
        AppHome home = AppHome.at(dir.resolve(".editor"));
        assertEquals(dir.resolve(".editor"), home.dir());
        assertEquals("editor", home.name());
    }

    @Test
    void openingLeavesNoMarkAndWritingCreatesTheDirectory(@TempDir Path dir) {
        AppHome home = AppHome.at(dir.resolve(".editor"));
        assertFalse(home.exists(), "merely opening a home leaves no directory behind");
        home.file("recent", "documents.txt");
        assertFalse(home.exists(), "nor does resolving a path inside it");

        home.settings().putInt("n", 1).save();
        assertTrue(home.exists());
        assertTrue(Files.isRegularFile(home.dir().resolve("settings.properties")));
    }

    @Test
    void createIsIdempotentAndFolderCreatesNestedDirectories(@TempDir Path dir) {
        AppHome home = AppHome.at(dir.resolve(".editor"));
        assertEquals(home.dir(), home.create());
        assertEquals(home.dir(), home.create(), "creating an existing home is not an error");

        Path cache = home.folder("cache", "thumbnails");
        assertTrue(Files.isDirectory(cache));
        assertEquals(home.dir().resolve("cache").resolve("thumbnails"), cache);
    }

    @Test
    void namedStoresSitBesideTheDefaultOneAndAreIndependent(@TempDir Path dir) {
        AppHome home = AppHome.at(dir.resolve(".editor"));
        home.settings().putString("theme", "dark").save();
        home.settings("session").putInt("window.x", 120).save();

        assertEquals(home.dir().resolve("session.properties"), home.settings("session").path());
        assertEquals(home.dir().resolve("settings.properties"), home.settings("settings").path(),
                "the default store is just the one named 'settings'");
        assertEquals("dark", home.settings().getString("theme", "?"));
        assertEquals(120, home.settings("session").getInt("window.x", 0));
        assertEquals(0, home.settings("session").getInt("theme", 0), "stores do not read each other's keys");
        assertEquals(home.dir().resolve("recent.properties"), home.settings("recent.properties").path(),
                "an explicit .properties suffix is not doubled");
    }

    @Test
    void filesResolveInsideTheHomeAndNowhereElse(@TempDir Path dir) {
        AppHome home = AppHome.at(dir.resolve(".editor"));
        assertEquals(home.dir().resolve("recent").resolve("documents.txt"), home.file("recent", "documents.txt"));

        assertThrows(IllegalArgumentException.class, () -> home.file(".."), "no climbing out");
        assertThrows(IllegalArgumentException.class, () -> home.file("cache", "../../secrets"));
        assertThrows(IllegalArgumentException.class, () -> home.file(dir.toAbsolutePath().toString()),
                "an absolute segment is not a path inside the home");
        assertThrows(IllegalArgumentException.class, () -> home.file(" "), "a blank segment names nothing");
        assertThrows(IllegalArgumentException.class, () -> home.settings("../elsewhere"),
                "a store name is a segment too");
    }

    @Test
    void aNameMustBeASinglePathSegment() {
        assertThrows(IllegalArgumentException.class, () -> AppHome.of(null));
        assertThrows(IllegalArgumentException.class, () -> AppHome.of("  "));
        assertThrows(IllegalArgumentException.class, () -> AppHome.of("."));
        assertThrows(IllegalArgumentException.class, () -> AppHome.of(".."));
        assertThrows(IllegalArgumentException.class, () -> AppHome.of("vendor/app"));
        assertThrows(IllegalArgumentException.class, () -> AppHome.of("vendor\\app"));
    }

    @Test
    void settingsOpenIsTheHomeShorthand() {
        assertEquals(AppHome.of("vexelray-test-app").settings().path(),
                Settings.open("vexelray-test-app").path(),
                "Settings.open(name) and AppHome.of(name).settings() are one file");
    }
}
