package dev.vexelray.gui.core.app;

/**
 * One window the frame loop is driving, and everything that has to be released with it: the window bundle
 * itself, the input backend attached at creation, the spec's callbacks, and this window's close policy. Popups
 * and named {@link AppWindow}s are the same thing here — the loop pumps, presents and tears them down
 * identically, and only {@code owner} distinguishes a window someone can ask for by name.
 */
final class OpenWindow {

    final GuiWindow window;
    final WindowInput input;
    final WindowSpec spec;
    final CloseGate gate = new CloseGate();
    /** The named handle to notify when this window is gone, or null for an anonymous popup. */
    final AppWindow owner;

    OpenWindow(GuiWindow window, WindowInput input, WindowSpec spec, AppWindow owner) {
        this.window = window;
        this.input = input;
        this.spec = spec;
        this.owner = owner;
        gate.handler(spec.onCloseRequest());
    }

    /** Release input first, then the window: a backend must never outlive the window it is bound to. */
    void release() {
        input.close();
        window.close();
        if (owner != null) {
            owner.cleared();
        }
        spec.onClosed().run();
    }
}
