package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.NativeWindow;

/**
 * A window the application refers to <b>by name</b> — "terminal", "history", "preferences" — and commands from
 * anywhere: {@link #show()}, {@link #hide()}, {@link #toggle()}, {@link #close()}. Asking for a window that is
 * already open raises and focuses the one that exists rather than making a second one, which is the whole point:
 * "Ctrl+` opens the terminal" must mean the terminal, not a terminal.
 *
 * <p><b>Why the framework owns this.</b> Every application that has a tool window ends up writing the same
 * bookkeeping — a boolean for "already open", a handle to focus instead of reopening, a callback to clear the
 * boolean when the user closes it, an input backend attached on creation and released on close, and a queue to
 * get all of that back onto the main thread. It is the same code every time, it is easy to get subtly wrong
 * (the duplicate window, the stale handle, the backend that outlives its window), and none of it is about the
 * application. Naming the window is all that is left of it here.
 *
 * <p><b>The tree outlives the window.</b> The {@link Gui} in the {@link WindowSpec} is built once. Hiding keeps
 * the OS window; closing destroys it; showing again re-creates a window around the same tree — so a terminal
 * closed and reopened still has its scrollback, and a preferences window still has whatever the user typed. What
 * a window <em>is</em>, here, is a place the application returns to.
 *
 * <p>Every command is safe from any thread: each only enqueues, and the frame loop performs it at the top of the
 * next frame — window creation and destruction belong to the main thread, on every platform.
 */
public final class AppWindow {

    private final String key;
    private final GuiApp app;
    private final WindowSpec spec;

    /** The live window, or null while closed. Written on the main thread, read from anywhere. */
    private volatile OpenWindow live;

    AppWindow(String key, GuiApp app, WindowSpec spec) {
        this.key = key;
        this.app = app;
        this.spec = spec;
    }

    /** The name this window is known by. */
    public String key() {
        return key;
    }

    /** The tree shown in this window — live whether or not the window is currently open. */
    public Gui gui() {
        return spec.gui();
    }

    /** Whether an OS window currently exists for this handle (visible or hidden). */
    public boolean open() {
        return live != null;
    }

    /** Whether this window is currently on screen: open, not hidden, not minimized away by {@link #hide()}. */
    public boolean visible() {
        OpenWindow w = live;
        return w != null && w.window.window.isVisible();
    }

    /**
     * The live OS window, or {@code null} while closed — for reading placement to persist. Its lifecycle stays
     * this handle's business: close it through {@link #close()}, never through the window itself.
     */
    public NativeWindow window() {
        OpenWindow w = live;
        return w == null ? null : w.window.window;
    }

    /**
     * Show this window: create it if it is closed, unhide it if it is hidden, and either way raise it and give
     * it the keyboard. The idempotent command — "make this window be in front of the user" — which is what a
     * shortcut that opens a tool window actually means.
     */
    public AppWindow show() {
        app.post(() -> {
            OpenWindow w = live;
            if (w == null) {
                live = app.openWindow(spec, this);
            } else {
                w.window.window.focus();   // focus() unhides and un-minimizes on the way
            }
        });
        return this;
    }

    /** Take this window off the screen, keeping it (and everything in it) alive for the next {@link #show()}. */
    public AppWindow hide() {
        app.post(() -> {
            OpenWindow w = live;
            if (w != null) {
                w.window.window.hide();
            }
        });
        return this;
    }

    /** Show this window if it is not in front of the user, hide it if it is — one shortcut, both directions. */
    public AppWindow toggle() {
        app.post(() -> {
            if (visible()) {
                hide();
            } else {
                show();
            }
        });
        return this;
    }

    /**
     * Close this window: the OS window is destroyed and its resources released, while the tree survives for a
     * later {@link #show()}. Travels the ordinary close route, so a {@link WindowSpec#onCloseRequest} on this
     * window is asked first and may refuse — an application-issued close is still a close.
     */
    public AppWindow close() {
        app.post(() -> {
            OpenWindow w = live;
            if (w != null) {
                w.window.window.requestClose();
            }
        });
        return this;
    }

    /** The frame loop, reporting that this window is gone. Main thread. */
    void cleared() {
        live = null;
    }
}
