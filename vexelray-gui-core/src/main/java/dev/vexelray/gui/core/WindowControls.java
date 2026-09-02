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

    /**
     * Screenshot <b>this window</b> to {@code path} as a PNG — the first of the framework-owned title-bar
     * instruments (docs/automation.md §7).
     *
     * <p>Here rather than on the application because a screenshot is of a window, and this interface is already
     * exactly "the things a title bar asks of the window it sits in". A bar in a popup captures the popup; the
     * bar does not learn which window it is in, any more than it does to minimize one.
     *
     * <p><b>Asynchronous, like every other command here.</b> The call returns immediately and the capture happens
     * on the main thread at the top of a frame, because everything it touches is Vulkan. The file exists shortly
     * after, not on return — so a caller waits for it to appear. That wait is safe: the picture is written
     * beside the path and moved into place, so a file that is there is a file that is complete.
     */
    void capture(String path);

    /**
     * Controls bound to a real OS window — what an application-drawn title bar in <em>any</em> window commands,
     * not just the main one. Close is a <em>request</em>, not a teardown: it travels the same route the system
     * close button's does, so the frame loop observes it and releases the window's resources in the order it
     * always does, rather than having them pulled out from under a frame in flight.
     */
    static WindowControls of(dev.vexelray.os.NativeWindow window) {
        return of(window, path -> { });
    }

    /**
     * Controls bound to a real OS window, with a working {@link #capture}. The capture sink is separate because
     * a {@code NativeWindow} cannot take its own picture: the pixels come from the GUI's per-window render
     * bundle, which only the host owns. The host supplies a sink that posts the work to its frame loop.
     *
     * <p>{@link #of(dev.vexelray.os.NativeWindow)} binds a sink that does nothing — correct for a window nobody
     * has offered a capture path for, and the same posture {@link #NONE} takes.
     */
    static WindowControls of(dev.vexelray.os.NativeWindow window, java.util.function.Consumer<String> capture) {
        return new WindowControls() {

            @Override
            public void capture(String path) {
                capture.accept(path);
            }

            @Override
            public void minimize() {
                window.minimize();
            }

            @Override
            public void toggleMaximize() {
                if (window.isMaximized()) {
                    window.restore();
                } else {
                    window.maximize();
                }
            }

            @Override
            public boolean maximized() {
                return window.isMaximized();
            }

            @Override
            public void close() {
                window.requestClose();
            }
        };
    }

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

        @Override
        public void capture(String path) {
            // no window to photograph
        }
    };
}
