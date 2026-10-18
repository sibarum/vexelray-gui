package dev.vexelray.gui.automation.window;

import dev.vexelray.gui.automation.Sockets;
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
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;

/**
 * The {@link AutomationPanel} in a window of its own, and the title-bar button that opens it — beside the key
 * bindings, where the window's other tools are:
 *
 * {@snippet :
 * titleBar.instruments(List.of(SaveScreenshot.instrument(), KeymapWindow.instrument(app, gui, keymap),
 *         AutomationWindow.instrument(app, gui, sockets)));
 * }
 *
 * <p>One window, however often it is asked for: a second click raises the one that is open ({@link AppWindow}). It
 * wears the main window's theme and the same client-drawn chrome as every other window of the application.
 *
 * <p><b>Debug editions only.</b> The button switches on a socket that hands its reader the application's input, so
 * it belongs where the socket's module is linked at all; a release binary links neither.
 */
public final class AutomationWindow {

    /** The name the window is registered under, so an application can reach it as {@code app.window("automation")}. */
    public static final String KEY = "automation";

    private static final String TITLE = "Automation";

    private AutomationWindow() {
    }

    /**
     * The automation button for a title bar: opens (or raises) the switches for {@code sockets}, themed like
     * {@code main}. Built lazily, so a button nobody clicks costs a closure.
     */
    public static WindowInstrument instrument(GuiApp app, Gui main, Sockets sockets) {
        return WindowInstrument.automation("Remote control and diagnostics",
                controls -> window(app, main, sockets).show());
    }

    /** The switches' window, registered on first use — {@code show()} it from a menu item or a shortcut of its own. */
    public static AppWindow window(GuiApp app, Gui main, Sockets sockets) {
        return app.window(KEY, () -> {
            Gui g = new Gui();
            g.theme(main.theme());
            TitleBar bar = new TitleBar(g, WindowControls.NONE, TITLE);
            AutomationPanel panel = new AutomationPanel(g, sockets);
            Node body = g.column().width(Length.FILL).height(Length.FILL).children(panel.node());
            g.root().background(g.theme().color(Role.PAGE)).children(bar.node(), body);
            return bar.commands(WindowSpec.of(
                    WindowConfig.of(TITLE, 560, 260).decorations(Decorations.CLIENT), g));
        });
    }
}
