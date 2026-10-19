package dev.vexelray.gui.keymap;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.ZoomKeys;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link KeymapEditor} in a window of its own, and the title-bar button that opens it — the opt-in way to
 * offer rebinding without finding a place for it in the application's UI:
 *
 * {@snippet :
 * titleBar.instruments(List.of(SaveScreenshot.instrument(), KeymapWindow.instrument(app, gui, keymap)));
 * }
 *
 * <p>One window, however often it is asked for: a second click raises the one that is open ({@link AppWindow}).
 * It wears the main window's theme and the same client-drawn chrome every other window of the application
 * does, and its tree outlives it, so a filter typed and the window closed is still there when it comes back.
 * Each opening is a visit: the editor's Cancel puts back what the bindings were when the window opened, and
 * closes it.
 */
public final class KeymapWindow {

    /** The name the window is registered under, so an application can reach it as {@code app.window("keymap")}. */
    public static final String KEY = "keymap";

    private static final String TITLE = "Key bindings";

    private KeymapWindow() {
    }

    /**
     * The key-bindings button for a title bar: opens (or raises) the editor for {@code keymap}, themed like
     * {@code main}. Built lazily, so a button nobody clicks costs a closure.
     */
    public static WindowInstrument instrument(GuiApp app, Gui main, Keymap keymap) {
        return WindowInstrument.keymap("Change key bindings", controls -> window(app, main, keymap).show());
    }

    /** {@link WindowInstrument#standard()} with the key-bindings button after the screenshot. */
    public static List<WindowInstrument> withStandard(GuiApp app, Gui main, Keymap keymap) {
        List<WindowInstrument> tools = new ArrayList<>(WindowInstrument.standard());
        tools.add(instrument(app, main, keymap));
        return List.copyOf(tools);
    }

    /** The editor's window, registered on first use — {@code show()} it from a menu item or a shortcut of its own. */
    public static AppWindow window(GuiApp app, Gui main, Keymap keymap) {
        return app.window(KEY, () -> {
            Gui g = new Gui();
            g.theme(main.theme());
            // Readable at the zoom the person chose for the window they opened it from, and zoomable on its own.
            float zoom = ZoomKeys.follow(g, main);
            TitleBar bar = new TitleBar(g, WindowControls.NONE, TITLE);
            // Cancel undoes what was changed since this window opened, and closes it.
            KeymapEditor editor = new KeymapEditor(g, keymap)
                    .onCancel(() -> app.window(KEY).ifPresent(AppWindow::close));
            // The bar is a child of the root and the padding is the editor's own, below it: a bar inset from the
            // window edge is a bar with a gap the window manager still treats as caption.
            Node body = g.column().width(Length.FILL).height(Length.FILL).children(editor.node());
            g.root().background(Role.PAGE).children(bar.node(), body);
            return bar.commands(WindowSpec.of(
                    WindowConfig.of(TITLE, Math.round(680 * zoom), Math.round(520 * zoom))
                            .decorations(Decorations.CLIENT), g)
                    .onCreated(w -> editor.checkpoint()));
        });
    }
}
