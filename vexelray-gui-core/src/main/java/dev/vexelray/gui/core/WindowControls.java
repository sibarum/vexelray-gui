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
     * instruments (docs/reference/automation.md §7).
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
     * The window's drawable width in pixels — the space the tree is laid out and input arrives in — or
     * {@code 0} where there is no window. A question rather than a value the GUI holds, like {@link #maximized}:
     * the window manager can change it without being asked.
     */
    default int width() {
        return 0;
    }

    /** The window's drawable height in pixels; see {@link #width}. */
    default int height() {
        return 0;
    }

    /**
     * Ask for the window's <b>drawable</b> area to be {@code width} by {@code height} pixels, leaving its
     * position alone. Not a title-bar command: it is for an instrument that has to photograph a window at a
     * chosen size, which is the one thing capturing it cannot do for itself.
     *
     * <p><b>A request, and asynchronous.</b> The window manager and the application's own minimum size decide
     * what the window actually becomes, so a caller that cares reads {@link #width} and {@link #height} after
     * the frame loop has caught up rather than assuming. A window that is maximized or minimized has no size of
     * its own to set, and ignores this.
     */
    default void resize(int width, int height) {
        // no window to size
    }

    /**
     * The OS handle of this window — an {@code HWND} on Windows — or {@code 0} where there is no window. For an
     * instrument that parents a native dialog to the window it was clicked in, so the dialog is modal to that
     * window rather than to whichever one happens to be in front.
     */
    default long osHandle() {
        return 0L;
    }

    /**
     * Run {@code task} on the thread that owns this window. A caption button is clicked on a worker, and some of
     * what an instrument does — a native file dialog — has to happen on the window's own thread. Where there is
     * no window there is no such thread, and the task runs here.
     */
    default void post(Runnable task) {
        task.run();
    }

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
        return of(window, capture, Runnable::run);
    }

    /**
     * As above, with {@code mainThread} running the commands that have to happen on the thread that owns the
     * window — {@link #resize}, and whatever is handed to {@link #post}. A host hands it its post: a caller on the automation's own
     * thread and a title-bar click (which runs on a worker, like every click) are both on the wrong side of the line.
     */
    static WindowControls of(dev.vexelray.os.NativeWindow window, java.util.function.Consumer<String> capture,
                             java.util.function.Consumer<Runnable> mainThread) {
        return new WindowControls() {

            @Override
            public void capture(String path) {
                capture.accept(path);
            }

            @Override
            public long osHandle() {
                return window.osHandle();
            }

            @Override
            public void post(Runnable task) {
                mainThread.accept(task);
            }

            @Override
            public int width() {
                return window.width();
            }

            @Override
            public int height() {
                return window.height();
            }

            @Override
            public void resize(int width, int height) {
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("a window cannot be " + width + "x" + height);
                }
                mainThread.accept(() -> {
                    if (window.isMaximized() || window.isMinimized()) {
                        return;
                    }
                    // The outer rect is what the platform sizes, and the chrome around the drawable area is
                    // whatever the two differ by now: a system frame has some, a client-drawn one has none.
                    window.setBounds(window.screenX(), window.screenY(),
                            window.outerWidth() + (width - window.width()),
                            window.outerHeight() + (height - window.height()));
                });
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
