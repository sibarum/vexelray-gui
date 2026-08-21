package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.NativeWindow;

/**
 * Input attached to one window: whatever the application edge does to make a window hear the keyboard and the
 * pointer, and the per-frame pump that keeps it flowing. The framework creates windows the application never
 * asked for by name — a dialog, a tool window reopened from a shortcut — and a window nobody remembered to wire
 * up is a window that draws and does nothing.
 *
 * <p><b>Why a seam rather than a dependency.</b> The GUI speaks {@code tactroller-api} and Atchung topics; the
 * bridge that carries device events from a backend onto a bus is chosen at the application edge, and the
 * framework must not name it (that is the layering rule the architecture guard enforces). So the application
 * supplies one {@link Factory} to {@link GuiApp#input}, and every window the framework opens from then on —
 * {@link AppWindow}, a modal dialog, a popup — gets input attached at creation, pumped every frame, and closed
 * with the window, with no per-window bookkeeping left in the application.
 *
 * <p>Both methods run on the main thread, inside the frame loop.
 */
public interface WindowInput extends AutoCloseable {

    /** Input that does nothing — the default, and what a headless or render-only window gets. */
    WindowInput NONE = new WindowInput() {
        @Override
        public void pump() {
            // nothing to pump
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    /**
     * Deliver whatever the backend has observed since the last frame — typically publishing it onto the window's
     * bus, where the GUI's dispatch picks it up during {@link Gui#frame}. Called once per frame, before the tree
     * is laid out and drawn.
     */
    void pump();

    /** Release the backend. Called when the window closes, before the window's own resources go. */
    @Override
    void close();

    /** Attaches input to a freshly created window. Supplied once, by the application edge. */
    @FunctionalInterface
    interface Factory {

        /** Attaches nothing: every framework-opened window renders but hears no device. */
        Factory NONE = (window, gui) -> WindowInput.NONE;

        /**
         * Attach a backend to {@code window} and bridge it onto {@code gui}'s bus. Runs on the main thread,
         * immediately after the window exists and before its first frame. Returning {@link WindowInput#NONE} is
         * a legitimate answer for a window that should not take input.
         */
        WindowInput attach(NativeWindow window, Gui gui);
    }
}
