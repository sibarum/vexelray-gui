package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;

import java.util.function.Consumer;

/**
 * Everything the framework needs to open a window on the application's behalf: how the OS window is requested
 * ({@link WindowConfig}), what it shows ({@link Gui}), and the three moments an application may want to be told
 * about — the window exists, the window was asked to close, the window is gone.
 *
 * <p>The tree is built <b>once</b> and outlives the window. That is what makes a window reopenable rather than
 * rebuilt: an {@link AppWindow} closed and shown again comes back with its scroll position, its text, its
 * selection — the state was never in the window, it was in the {@link Gui}.
 *
 * @param config         the window to request — title, size, decorations, placement
 * @param gui            the tree shown in it, built once and reused across open/close cycles
 * @param onCreated      run on the main thread when the OS window exists, before its first frame — where a
 *                       {@code TitleBar} is pointed at the real window (a popup's controls are not the main
 *                       window's)
 * @param onClosed       run on the main thread after the window is gone
 * @param onCloseRequest asked before this window closes, on the handler executor; {@code null} means a close
 *                       just closes (see {@link CloseRequest})
 */
public record WindowSpec(WindowConfig config, Gui gui, Consumer<NativeWindow> onCreated, Runnable onClosed,
                         Consumer<CloseRequest> onCloseRequest) {

    public WindowSpec {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (gui == null) {
            throw new IllegalArgumentException("gui must not be null");
        }
        if (onCreated == null) {
            onCreated = w -> { };
        }
        if (onClosed == null) {
            onClosed = () -> { };
        }
    }

    /** A window showing {@code gui}, with nothing to do at its lifecycle moments. */
    public static WindowSpec of(WindowConfig config, Gui gui) {
        return new WindowSpec(config, gui, null, null, null);
    }

    /** This spec, with {@code onCreated} run once the OS window exists. */
    public WindowSpec onCreated(Consumer<NativeWindow> onCreated) {
        return new WindowSpec(config, gui, onCreated, onClosed, onCloseRequest);
    }

    /** This spec, with {@code onClosed} run once the window is gone. */
    public WindowSpec onClosed(Runnable onClosed) {
        return new WindowSpec(config, gui, onCreated, onClosed, onCloseRequest);
    }

    /** This spec, with a say in whether this window closes at all. */
    public WindowSpec onCloseRequest(Consumer<CloseRequest> onCloseRequest) {
        return new WindowSpec(config, gui, onCreated, onClosed, onCloseRequest);
    }
}
