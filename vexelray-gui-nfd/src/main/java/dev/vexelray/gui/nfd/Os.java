package dev.vexelray.gui.nfd;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The platform this is running on, and the only place in the module that reads {@code os.name}.
 *
 * <p>It exists because there were three copies of the question and one of them was wrong in a way the other two
 * hid. Each asked {@code os.name.contains("win")} before asking about macOS — and {@code "darwin".contains("win")}
 * is <b>true</b>, so the {@code darwin} branch each of them carefully wrote could never run. The consequences
 * differed by site, which is why no single symptom would have found it: the loader would ask for {@code nfd.dll}
 * instead of {@code libnfd.dylib} and fail loudly; the charset would be UTF-16LE, silently corrupting every path
 * in and out; and the window handle would be tagged as an {@code HWND} while holding an {@code NSWindow*}.
 *
 * <p>So the test is {@code startsWith} rather than {@code contains}, which removes the ambiguity instead of
 * ordering around it: no name can begin with both {@code windows} and {@code darwin}. And each constant carries
 * its own answers rather than being switched on, which is the same rule the rest of the framework follows —
 * a platform difference is behaviour, and behaviour belongs on the type that varies.
 */
enum Os {

    WINDOWS(StandardCharsets.UTF_16LE, 2, Nfd.NFD_WINDOW_HANDLE_TYPE_WINDOWS, "windows") {
        @Override
        String libraryFileName(String libName) {
            return libName + ".dll";
        }
    },

    MACOS(StandardCharsets.UTF_8, 1, Nfd.NFD_WINDOW_HANDLE_TYPE_COCOA, "macos") {
        @Override
        String libraryFileName(String libName) {
            return "lib" + libName + ".dylib";
        }
    };

    private final Charset charset;
    private final int charSize;
    private final long windowHandleType;
    private final String resourceDirectory;

    Os(Charset charset, int charSize, long windowHandleType, String resourceDirectory) {
        this.charset = charset;
        this.charSize = charSize;
        this.windowHandleType = windowHandleType;
        this.resourceDirectory = resourceDirectory;
    }

    /** What this platform calls a shared library named {@code libName}. */
    abstract String libraryFileName(String libName);

    /**
     * How NFDe's {@code nfdnchar_t} is encoded here — {@code wchar_t} (UTF-16LE) on Windows, {@code char}
     * (UTF-8) on POSIX.
     */
    Charset charset() {
        return charset;
    }

    /** The width of one {@code nfdnchar_t} in bytes, which is also the width of its null terminator. */
    int charSize() {
        return charSize;
    }

    /** Which of NFDe's {@code NFD_WINDOW_HANDLE_TYPE_*} a native window handle from this platform is. */
    long windowHandleType() {
        return windowHandleType;
    }

    /** The {@code /natives/<os>-<arch>/} segment bundled libraries are looked up under. */
    String resourceDirectory() {
        return resourceDirectory;
    }

    /** The platform this JVM is on. */
    static Os current() {
        return of(System.getProperty("os.name", ""));
    }

    /**
     * The platform {@code osName} names. Separate from {@link #current()} so the mapping can be tested without a
     * JVM on each platform — which is the only way the {@code darwin} case above could have been caught.
     */
    static Os of(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return MACOS;
        }
        throw new UnsatisfiedLinkError("unsupported OS: " + osName
                + " — this module binds NFDe, which ships for Windows and macOS only");
    }
}
