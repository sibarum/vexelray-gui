package dev.vexelray.gui.nfd.spi;

import java.nio.charset.Charset;

/**
 * Everything about NFDe that differs per operating system, provided by that OS's module
 * ({@code vexelray-gui-nfd-windows}, {@code -macos}, {@code -linux}) through {@link java.util.ServiceLoader}. The
 * facade, {@code vexelray-gui-nfd}, holds no platform knowledge of its own: it asks the one implementation on the
 * class path, which the consumer's OS-activated build profile put there.
 *
 * <p>Each answer is a fact about the platform, not a decision about the application, so an implementation is a
 * handful of constants and a bundled library.
 */
public interface NfdPlatform {

    /** NFDe's {@code NFD_WINDOW_HANDLE_TYPE_WINDOWS}: the handle is an {@code HWND}. */
    long WINDOW_HANDLE_TYPE_WINDOWS = 1;

    /** NFDe's {@code NFD_WINDOW_HANDLE_TYPE_COCOA}: the handle is an {@code NSWindow*}. */
    long WINDOW_HANDLE_TYPE_COCOA = 2;

    /** NFDe's {@code NFD_WINDOW_HANDLE_TYPE_X11}: the handle is an X11 {@code Window}. */
    long WINDOW_HANDLE_TYPE_X11 = 3;

    /** Where a dialog may run on this platform. */
    enum Lane {
        /**
         * The facade's own long-lived dialog thread, which owns {@code NFD_Init} and {@code NFD_Quit} and runs the
         * dialogs one at a time, so the window's thread goes on drawing behind a dialog.
         */
        DIALOG_THREAD,

        /** The thread that owns the window, because the platform's dialogs may run nowhere else (AppKit). */
        GUI_THREAD
    }

    /** Where this platform's dialogs run. */
    Lane lane();

    /** How NFDe's {@code nfdnchar_t} is encoded: {@code wchar_t} (UTF-16LE) on Windows, UTF-8 elsewhere. */
    Charset charset();

    /** The width of one {@code nfdnchar_t} in bytes, which is also the width of its null terminator. */
    int charSize();

    /** Which {@code NFD_WINDOW_HANDLE_TYPE_*} a native window handle from this platform is. */
    long windowHandleType();

    /** What this platform calls the NFDe library file: {@code nfd.dll}, {@code libnfd.dylib}, {@code libnfd.so}. */
    String libraryFileName();

    /**
     * The class-path resource holding the library built for this machine's architecture, or {@code null} when the
     * module ships none for it, in which case the facade falls back to the system loader.
     */
    String libraryResource();
}
