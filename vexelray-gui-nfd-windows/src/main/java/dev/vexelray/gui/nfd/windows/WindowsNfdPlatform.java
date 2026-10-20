package dev.vexelray.gui.nfd.windows;

import dev.vexelray.gui.nfd.spi.NfdPlatform;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** NFDe on Windows: IFileDialog through COM. */
public final class WindowsNfdPlatform implements NfdPlatform {

    /** The dialog thread. {@code IFileDialog} runs on any thread in a single-threaded COM apartment, provided the init, the dialogs and the quit are all on that one thread; a dialog owned by another thread's window disables that window while it is up, and the window's own thread goes on drawing. */
    @Override
    public Lane lane() {
        return Lane.DIALOG_THREAD;
    }

    @Override
    public Charset charset() {
        return StandardCharsets.UTF_16LE;
    }

    @Override
    public int charSize() {
        return 2;
    }

    @Override
    public long windowHandleType() {
        return WINDOW_HANDLE_TYPE_WINDOWS;
    }

    @Override
    public String libraryFileName() {
        return "nfd.dll";
    }

    /** The bundled build is x64 only; another architecture falls back to an NFDe the system provides. */
    @Override
    public String libraryResource() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (arch.equals("amd64") || arch.equals("x86_64")) ? "/natives/windows-x64/nfd.dll" : null;
    }
}
