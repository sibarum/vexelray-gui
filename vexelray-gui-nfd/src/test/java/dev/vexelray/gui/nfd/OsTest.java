package dev.vexelray.gui.nfd;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The module's first tests, and they exist for one reason: the platform question was asked three times, answered
 * wrongly in all three, and nothing could have caught it.
 *
 * <p>Every site tested {@code os.name.contains("win")} before asking about macOS, and {@code "darwin"} contains
 * {@code "win"}. So the {@code darwin} branch each of them had carefully written was unreachable, and a JVM
 * reporting that name would have loaded {@code nfd.dll} on a Mac, encoded every path as UTF-16LE, and tagged an
 * {@code NSWindow*} as an {@code HWND}. A test on the machine that runs this suite would have proved nothing:
 * the bug is only visible on a platform the suite does not run on.
 *
 * <p>Which is why {@link Os#of(String)} takes the name rather than reading it. The mapping is the part that can
 * be wrong, so the mapping is the part that is a value.
 */
class OsTest {

    @Test
    void darwinIsMacOsAndNotWindows() {
        assertSame(Os.MACOS, Os.of("Darwin"), "\"darwin\" contains \"win\", which is how this was wrong three times");
        assertSame(Os.MACOS, Os.of("darwin"));
    }

    @Test
    void theNamesTheseJvmsActuallyReport() {
        for (String windows : List.of("Windows 10", "Windows 11", "Windows Server 2019", "windows 7")) {
            assertSame(Os.WINDOWS, Os.of(windows), windows);
        }
        for (String mac : List.of("Mac OS X", "macOS", "MacOS")) {
            assertSame(Os.MACOS, Os.of(mac), mac);
        }
    }

    @Test
    void anythingElseSaysSoRatherThanGuessing() {
        for (String other : List.of("Linux", "SunOS", "FreeBSD", "")) {
            assertThrows(UnsatisfiedLinkError.class, () -> Os.of(other), other);
        }
        assertThrows(UnsatisfiedLinkError.class, () -> Os.of(null));
    }

    /**
     * The three answers that differed by site. Holding them on the constant is what stops the next one being a
     * fourth reading of {@code os.name}.
     */
    @Test
    void eachPlatformCarriesItsOwnAnswers() {
        assertEquals("nfd.dll", Os.WINDOWS.libraryFileName("nfd"));
        assertEquals("libnfd.dylib", Os.MACOS.libraryFileName("nfd"));

        assertEquals(StandardCharsets.UTF_16LE, Os.WINDOWS.charset(), "nfdnchar_t is wchar_t on Windows");
        assertEquals(StandardCharsets.UTF_8, Os.MACOS.charset(), "and char on POSIX");

        assertEquals(2, Os.WINDOWS.charSize());
        assertEquals(1, Os.MACOS.charSize());

        assertEquals(Nfd.NFD_WINDOW_HANDLE_TYPE_WINDOWS, Os.WINDOWS.windowHandleType());
        assertEquals(Nfd.NFD_WINDOW_HANDLE_TYPE_COCOA, Os.MACOS.windowHandleType());

        assertEquals("windows", Os.WINDOWS.resourceDirectory());
        assertEquals("macos", Os.MACOS.resourceDirectory());
    }

    /**
     * A char's width and its charset have to agree, because {@code readNString} scans for the terminator in
     * units of the first and decodes with the second. They were two independent ternaries on one boolean before.
     */
    @Test
    void charSizeAgreesWithTheCharset() {
        for (Os os : Os.values()) {
            assertEquals(os.charSize(), "a".getBytes(os.charset()).length, os + " scans and decodes differently");
        }
    }
}
