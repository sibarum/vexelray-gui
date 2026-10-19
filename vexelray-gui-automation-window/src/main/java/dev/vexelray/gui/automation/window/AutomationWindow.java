package dev.vexelray.gui.automation.window;

import dev.vexelray.gui.automation.Capability;
import dev.vexelray.gui.automation.Sockets;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.WindowInstrument.Lamp;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.ZoomKeys;
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
 * <p><b>The button is the sockets' lamps.</b> Beside the plug, remote control on the left and diagnostics on the
 * right, each socket shows whether its port is open and whether anything is, or was this session, connected to it
 * ({@link #lamp}), so a person sees at a glance whether anything can drive the application or read it, and whether
 * anything has. The tooltip says it in words. A run that offers neither socket gets no button
 * ({@code Sockets.AVAILABLE_SETTING}).
 *
 * <p>One window, however often it is asked for: a second click raises the one that is open ({@link AppWindow}). It
 * wears the main window's theme and the same client-drawn chrome as every other window of the application.
 */
public final class AutomationWindow {

    /** The name the window is registered under, so an application can reach it as {@code app.window("automation")}. */
    public static final String KEY = "automation";

    /** Remote control's lamp: the danger colour, since whatever is connected to it can drive the application. */
    static final Role CONTROL_LAMP = Role.DANGER;

    /** Diagnostics' lamp: danger's strength turned to blue, as {@code Role.CAUTION} turns it to amber. */
    static final Role DIAGNOSTICS_LAMP = p -> Oklab.polar(p.accent().l(), p.danger().chroma() * 0.85, 250).toColor();

    /** The dot that says something was connected earlier. */
    static final Role USED_LAMP = Role.DIM;

    private static final String TITLE = "Automation";

    private AutomationWindow() {
    }

    /**
     * The automation button for a title bar, with the lamps of {@code sockets}: opens (or raises) the switches, themed
     * like {@code main}. Built lazily, so a button nobody clicks costs a closure.
     */
    public static WindowInstrument instrument(GuiApp app, Gui main, Sockets sockets) {
        return WindowInstrument.automation("Remote control and diagnostics",
                controls -> window(app, main, sockets).show(),
                () -> lamps(main, sockets.state(Capability.CONTROL), sockets.state(Capability.DIAGNOSTICS)),
                new WindowInstrument.Live() {
                    @Override
                    public Runnable watch(Runnable changed) {
                        return sockets.watch(s -> changed.run());
                    }

                    @Override
                    public String tooltip() {
                        return describe(sockets);
                    }
                });
    }

    /**
     * The plug's two lamps for these states, in {@code main}'s colours: for a button built before its sockets are,
     * which shows {@code OFF} (or {@code UNAVAILABLE}) until they exist.
     */
    public static WindowInstrument.Lamps lamps(Gui main, Sockets.State control, Sockets.State diagnostics) {
        return new WindowInstrument.Lamps(lamp(control), lamp(diagnostics), main.theme().color(CONTROL_LAMP),
                main.theme().color(DIAGNOSTICS_LAMP), main.theme().color(USED_LAMP));
    }

    /** The lamp a state shows; {@code null} for a socket this run cannot open, which shows none. */
    static Lamp lamp(Sockets.State state) {
        return switch (state) {
            case UNAVAILABLE -> null;
            case OFF -> Lamp.OFF;
            case LISTENING -> Lamp.LISTENING;
            case CONNECTED -> Lamp.CONNECTED;
            case WAS_CONNECTED -> Lamp.WAS_CONNECTED;
            case CLOSED_AFTER_USE -> Lamp.CLOSED_AFTER_USE;
        };
    }

    /**
     * The tooltip: both sockets in words, a line each.
     *
     * <pre>
     * Remote control: connected now (1), localhost:61184
     * Diagnostics: off; something connected earlier
     * </pre>
     */
    public static String describe(Sockets sockets) {
        return "Remote control: " + say(sockets, Capability.CONTROL) + "\nDiagnostics: "
                + say(sockets, Capability.DIAGNOSTICS);
    }

    /** One socket's state in words, as the tooltip and the panel's rows say it. */
    static String say(Sockets sockets, Capability c) {
        int port = sockets.port(c);
        String where = port < 0 ? "" : ", localhost:" + port
                + (sockets.port(c == Capability.CONTROL ? Capability.DIAGNOSTICS : Capability.CONTROL) == port
                ? " (one socket, both)" : "");
        return switch (sockets.state(c)) {
            case UNAVAILABLE -> "not available in this run";
            case OFF -> "off";
            case LISTENING -> "open, nothing connected yet" + where;
            case CONNECTED -> "connected now (" + sockets.connected(c) + ")" + where;
            case WAS_CONNECTED -> "open; something was connected, none now" + where;
            case CLOSED_AFTER_USE -> "off; something was connected earlier";
        };
    }

    /** The switches' window, registered on first use — {@code show()} it from a menu item or a shortcut of its own. */
    public static AppWindow window(GuiApp app, Gui main, Sockets sockets) {
        return app.window(KEY, () -> {
            Gui g = new Gui();
            g.theme(main.theme());
            // Readable at the zoom the person chose for the window they opened it from, and zoomable on its own.
            float zoom = ZoomKeys.follow(g, main);
            TitleBar bar = new TitleBar(g, WindowControls.NONE, TITLE);
            AutomationPanel panel = new AutomationPanel(g, sockets);
            Node body = g.column().width(Length.FILL).height(Length.FILL).children(panel.node());
            g.root().background(g.theme().color(Role.PAGE)).children(bar.node(), body);
            return bar.commands(WindowSpec.of(
                    WindowConfig.of(TITLE, Math.round(680 * zoom), Math.round(260 * zoom))
                            .decorations(Decorations.CLIENT), g));
        });
    }
}
