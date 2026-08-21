package dev.vexelray.gui.core;

/**
 * The four things an application-drawn title bar has to be able to ask of its window. The application edge binds
 * this to the real OS window ({@code GuiApp.controls()}); a widget takes it as a constructor argument and never
 * learns what platform it is on — which is also what keeps a title bar renderable headless, against
 * {@link #NONE}, for a capture or a test.
 *
 * <p>Deliberately four commands and no state: everything else a title bar shows — the window's own title, its
 * focus, its size — the GUI already knows. Whether the window is currently maximized it does not, because the
 * window manager can change that without being asked (a snap gesture, Win+Up), so {@link #maximized()} is a
 * question rather than a value the GUI holds.
 *
 * <p>Called on the GUI thread.
 */
public interface WindowControls {

    /** Minimize (iconify) the window. */
    void minimize();

    /** Maximize the window, or restore it if it already is — what a maximize button does when clicked. */
    void toggleMaximize();

    /** Whether the window is maximized right now. Asked each frame; the answer may change without the GUI. */
    boolean maximized();

    /**
     * Ask the window to close. The request travels the same route the system close button's does, so the host
     * tears the window down on its own terms — this does not destroy anything itself.
     */
    void close();

    /** Controls that do nothing: for a headless render, a test, or a window that has no chrome to command. */
    WindowControls NONE = new WindowControls() {

        @Override
        public void minimize() {
            // no window to minimize
        }

        @Override
        public void toggleMaximize() {
            // no window to maximize
        }

        @Override
        public boolean maximized() {
            return false;
        }

        @Override
        public void close() {
            // no window to close
        }
    };
}
