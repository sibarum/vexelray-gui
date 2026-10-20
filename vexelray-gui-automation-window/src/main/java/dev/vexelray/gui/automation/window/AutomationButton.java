package dev.vexelray.gui.automation.window;

import dev.vexelray.gui.automation.Capability;
import dev.vexelray.gui.automation.Sockets;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.WindowInstrument.Lamp;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;

import java.util.function.Consumer;

/**
 * What the title bar's automation button shows of the sockets: their lamps, their state in words, and their ports
 * as the first lines of its menu. The menu's commands are its caller's: the framework's {@code Driver} adds the ways
 * into and out of a debug run.
 *
 * {@snippet :
 * titleBar.instruments(List.of(SaveScreenshot.instrument(), AutomationButton.instrument(gui, sockets, menu -> { })));
 * }
 *
 * <p><b>Sockets are opened at launch, not by hand.</b> The probe that feeds the Inspector is decided when the process
 * starts, so a socket switched on later reached an application with nothing to stream. A debug run is therefore a
 * launch ({@code --automation=0}), and a running application becomes one by restarting
 * ({@code vexelray-framework}'s {@code Relaunch}); there are no switches.
 *
 * <p><b>The button is the sockets' lamps.</b> Beside the mark, remote control on the left and diagnostics on the
 * right, each socket shows whether its port is open and whether anything is, or was this session, connected to it
 * ({@link #lamp}). The tooltip says it in words.
 */
public final class AutomationButton {

    /** Remote control's lamp: the danger colour, since whatever is connected to it can drive the application. */
    static final Role CONTROL_LAMP = Role.DANGER;

    /** Diagnostics' lamp: danger's strength turned to blue, as {@code Role.CAUTION} turns it to amber. */
    static final Role DIAGNOSTICS_LAMP = p -> Oklab.polar(p.accent().l(), p.danger().chroma() * 0.85, 250).toColor();

    /** The dot that says something was connected earlier. */
    static final Role USED_LAMP = Role.DIM;

    private AutomationButton() {
    }

    /**
     * The debug run's button for a title bar, with the lamps of {@code sockets}: a click opens a menu of the ports,
     * then whatever {@code more} adds.
     */
    public static WindowInstrument instrument(Gui main, Sockets sockets, Consumer<MenuSink> more) {
        return WindowInstrument.debugging("Debug run: remote control and diagnostics",
                menu -> {
                    ports(menu, sockets);
                    menu.separator();
                    more.accept(menu);
                },
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

    /** The sockets as the menu's first lines, read-only: what each is, and where it listens. */
    public static void ports(MenuSink menu, Sockets sockets) {
        menu.item("Remote control: " + say(sockets, Capability.CONTROL), false, null);
        menu.item("Diagnostics: " + say(sockets, Capability.DIAGNOSTICS), false, null);
    }

    /**
     * The mark's two lamps for these states, in {@code main}'s colours: for a button built before its sockets are,
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

    /** One socket's state in words, as the tooltip and the menu say it. */
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
}
