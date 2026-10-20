package dev.vexelray.gui.nfd.linux;

import dev.vexelray.gui.nfd.spi.NfdPlatform;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** NFDe on Linux: NFDe 1.3.0's xdg-desktop-portal backend over D-Bus, so the desktop's own file chooser. */
public final class LinuxNfdPlatform implements NfdPlatform {

    /** The dialog thread. The portal backend talks to xdg-desktop-portal over a D-Bus connection of its own and blocks for the answer; nothing ties it to the window's thread, so that thread goes on drawing behind the dialog. */
    @Override
    public Lane lane() {
        return Lane.DIALOG_THREAD;
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
        return WINDOW_HANDLE_TYPE_X11;
    }

    @Override
    public String libraryFileName() {
        return "libnfd.so";
    }

    /** The bundled build is x64 only; another architecture falls back to an NFDe the system provides. */
    @Override
    public String libraryResource() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (arch.equals("amd64") || arch.equals("x86_64")) ? "/natives/linux-x64/libnfd.so" : null;
    }
}
