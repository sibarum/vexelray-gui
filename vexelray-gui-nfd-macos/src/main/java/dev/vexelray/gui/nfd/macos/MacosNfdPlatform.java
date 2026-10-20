package dev.vexelray.gui.nfd.macos;

import dev.vexelray.gui.nfd.spi.NfdPlatform;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** NFDe on macOS: NSOpenPanel and NSSavePanel. */
public final class MacosNfdPlatform implements NfdPlatform {

    /** The GUI thread: {@code NSOpenPanel} and {@code NSSavePanel} are AppKit, and AppKit is main-thread only. */
    @Override
    public Lane lane() {
        return Lane.GUI_THREAD;
    }

    @Override
    public Charset charset() {
        return StandardCharsets.UTF_8;
    }

    @Override
    public int charSize() {
        return 1;
    }

    @Override
    public long windowHandleType() {
        return WINDOW_HANDLE_TYPE_COCOA;
    }

    @Override
    public String libraryFileName() {
        return "libnfd.dylib";
    }

    /** The bundled build is aarch64 only; another architecture falls back to an NFDe the system provides. */
    @Override
    public String libraryResource() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (arch.equals("aarch64") || arch.equals("arm64")) ? "/natives/macos-aarch64/libnfd.dylib" : null;
    }
}
