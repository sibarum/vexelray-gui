package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.WorkArea;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where each window was, across restarts. Reads saved bounds into the {@link WindowConfig} a window is
 * <em>created</em> with, watches the live windows for moves, resizes and zoom, and writes the result back to
 * an application's {@link Settings}.
 *
 * <p><b>Why the framework owns this,</b> the same argument {@link AppWindow} makes about tool windows. Every
 * application with more than one window ends up writing the same bookkeeping: a settings key per window, a
 * clamp against a desktop that may have changed shape, a maximized flag kept apart from the bounds it would
 * otherwise destroy, and a debounce so a drag is not four hundred disk writes. It is the same code every time,
 * it is easy to get subtly wrong in ways that only show up on the day a monitor is unplugged, and none of it
 * is about the application. Naming each window is all that is left of it here.
 *
 * <p><b>Saved bounds are a claim about a desktop, not a fact about this one.</b> Monitors get unplugged,
 * resolutions drop, a laptop comes back undocked — so every restore is clamped through
 * {@link WorkArea#fit}: shrunk if it no longer fits the screen it lands on, then nudged until it is fully
 * inside. Restoring at creation rather than after is deliberate too, so a window appears where it belongs
 * instead of jumping there on its first frame.
 *
 * <p><b>Maximized is a state, not a rectangle.</b> While a window is maximized its reported bounds are the
 * screen's, so saving them would lose the size to restore <em>down</em> to. This records the flag and leaves the
 * bounds from before, which is what makes un-maximizing after a restart land where it used to.
 *
 * <p>Writing is debounced: {@link #poll} notices a change every frame but the file is only written once the
 * dragging stops, and again at shutdown. A window drag would otherwise be a few hundred disk writes.
 */
public final class WindowMemory {

    /** How long a change must hold still before it is worth a disk write. */
    private static final long SETTLE_NANOS = 700_000_000L;

    /** Never restore a window smaller than this, whatever the file says. */
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 240;

    /** A zoom factor closer than this to the saved one is the same zoom, and not worth a write. */
    private static final float ZOOM_EPSILON = 1e-4f;

    private final Settings settings;
    private final Desktop desktop;
    private final Map<String, Watch> watched = new HashMap<>();
    private boolean dirty;
    private long lastChange;

    /**
     * Where the usable area of the desktop comes from. The platform answers it in a running application, and a
     * fixture answers it in a test — which is the point of the seam being here at all: the clamp is the rule
     * that stops a window stranding on a monitor that no longer exists, so it is the rule most worth having
     * tests for, and it cannot be tested against whatever monitors the build machine happens to have.
     */
    @FunctionalInterface
    public interface Desktop {

        /** The platform's own answer. */
        Desktop PLATFORM = GuiApp::workArea;

        /** The usable area of the monitor containing {@code (x, y)}, or empty if there is none (or none known). */
        Optional<WorkArea> covering(int x, int y);
    }

    /** One window being watched: its window, the bounds last written down, and the tree it is showing. */
    private static final class Watch {
        final NativeWindow window;
        final Gui gui;
        int x;
        int y;
        int width;
        int height;
        boolean maximized;
        float zoom;

        Watch(NativeWindow window, Gui gui,
              int x, int y, int width, int height, boolean maximized) {
            this.window = window;
            this.gui = gui;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.maximized = maximized;
            this.zoom = gui == null ? 1f : gui.zoom().value();
        }
    }

    public WindowMemory(Settings settings) {
        this(settings, Desktop.PLATFORM);
    }

    /** As {@link #WindowMemory(Settings)}, against a desktop of your choosing. For tests. */
    public WindowMemory(Settings settings, Desktop desktop) {
        this.settings = settings;
        this.desktop = desktop;
    }

    /**
     * The request for a window: its saved bounds if they are still somewhere a window can be, else
     * {@code defaultWidth × defaultHeight} wherever the OS wants to put it.
     *
     * @param key one window's name in the settings file — {@code main}, {@code folder}, {@code terminal}
     */
    public WindowConfig config(String key, String title, int defaultWidth, int defaultHeight) {
        WorkArea.Bounds fitted = saved(key, defaultWidth, defaultHeight);
        if (fitted == null) {
            // Nothing saved, or the platform cannot say what fits. Honour any saved size, let the OS choose the
            // place: an unclamped position is the one way this feature can strand a window off-screen.
            return WindowConfig.of(title,
                    Math.max(settings.getInt(key(key, "width"), defaultWidth), MIN_WIDTH),
                    Math.max(settings.getInt(key(key, "height"), defaultHeight), MIN_HEIGHT));
        }
        return WindowConfig.of(title, fitted.width(), fitted.height()).at(fitted.x(), fitted.y());
    }

    /**
     * Put {@code window} back at the bounds saved for {@code key}. Call it from {@code onCreated}, before the
     * window's first frame.
     *
     * <p>This exists because a reopened window is configured from a stale rectangle. A named window's
     * {@code WindowSpec} — and so its {@code WindowConfig} — is built once, when the name is first claimed, while
     * the bounds worth restoring are the ones the user last left the window at, which may be from later in the
     * same session: move the terminal, close it, reopen it. Creation gets the window roughly right and this
     * corrects it before anything is drawn, so there is no visible jump either way.
     */
    public void restoreBounds(String key, NativeWindow window, int defaultWidth, int defaultHeight) {
        WorkArea.Bounds fitted = saved(key, defaultWidth, defaultHeight);
        if (fitted == null) {
            return;   // nothing saved, or nowhere known to put it: leave the window where it was created
        }
        if (fitted.x() != window.screenX() || fitted.y() != window.screenY()
                || fitted.width() != window.outerWidth() || fitted.height() != window.outerHeight()) {
            window.setBounds(fitted.x(), fitted.y(), fitted.width(), fitted.height());
        }
    }

    /** The saved rectangle for {@code key}, clamped onto a monitor that exists, or null if there is no answer. */
    private WorkArea.Bounds saved(String key, int defaultWidth, int defaultHeight) {
        if (!settings.has(key(key, "x"))) {
            return null;
        }
        int x = settings.getInt(key(key, "x"), 0);
        int y = settings.getInt(key(key, "y"), 0);
        int w = settings.getInt(key(key, "width"), defaultWidth);
        int h = settings.getInt(key(key, "height"), defaultHeight);
        return desktop.covering(x, y).map(a -> a.fit(x, y, w, h, MIN_WIDTH, MIN_HEIGHT)).orElse(null);
    }

    /** Whether {@code key}'s window should come up maximized. Ask after {@link #config}, apply after creation. */
    public boolean maximized(String key) {
        return settings.getBoolean(key(key, "maximized"), false);
    }

    /** Whether {@code key}'s window was open when the application last closed — ask this at startup. */
    public boolean wasOpen(String key) {
        return settings.getBoolean(key(key, "open"), false);
    }

    /**
     * Record whether {@code key}'s window is open <em>right now</em>. Call it every frame, from the live state of
     * the window itself.
     *
     * <p>Polling rather than writing it from the open and close paths is what makes quitting with a tool window
     * up distinguishable from closing that window by hand. Both end in the same callback — the frame loop runs
     * every popup's {@code onClosed} as it tears the application down — so a flag written there would read
     * "closed" either way, and nothing would ever reopen. The last poll before the loop exits still sees the
     * window open, which is the truth worth saving.
     */
    public void open(String key, boolean nowOpen) {
        if (settings.getBoolean(key(key, "open"), false) != nowOpen) {
            settings.putBoolean(key(key, "open"), nowOpen);
            touch();
        }
    }

    /** What {@code key}'s window was last showing — the folder the file tree was pointed at. Empty if none. */
    public String shownPath(String key) {
        return settings.getString(key(key, "path"), "");
    }

    /** Remember what {@code key}'s window is showing, so reopening it can show the same thing. */
    public void shownPath(String key, Path path) {
        String value = path == null ? "" : path.toString();
        if (!settings.getString(key(key, "path"), "").equals(value)) {
            settings.putString(key(key, "path"), value);
            touch();
        }
    }

    /**
     * Start watching {@code window} under {@code key}, and record the bounds it actually has.
     *
     * <p>Recording at this point matters twice. On a first run there is nothing saved, so the placement the OS
     * just chose is the thing to remember — waiting for the user to move the window would throw away a perfectly
     * good answer. And on a restore that got clamped, what the window <em>became</em> is not what the file asked
     * for; writing the real bounds down is what stops an impossible rectangle surviving every restart, silently
     * re-clamped each time and never corrected.
     */
    public void watch(String key, NativeWindow window) {
        watch(key, window, null);
    }

    /**
     * As {@link #watch(String, NativeWindow)}, and also <b>restore and then follow the zoom</b> of the tree this
     * window shows.
     *
     * <p>Zoom belongs here rather than beside it because it is the same kind of fact as a window's size, and a
     * user who scaled a window up did so for the same reason they resized it: to read it. Losing that on quit
     * is the same loss. It is restored before the first frame — {@code em} resolves against it, so applying it
     * afterwards would lay the whole tree out twice and show the first one.
     *
     * @param gui the tree in {@code window}, or null not to touch its zoom at all
     */
    public void watch(String key, NativeWindow window, Gui gui) {
        if (gui != null) {
            float saved = settings.getFloat(key(key, "zoom"), 0f);
            if (saved > 0f) {
                gui.zoom(saved);
            }
        }
        Watch w = new Watch(window, gui, window.screenX(), window.screenY(),
                window.outerWidth(), window.outerHeight(), window.isMaximized());
        watched.put(key, w);
        if (gui != null) {
            storeZoom(key, w);
        }
        if (settings.getBoolean(key(key, "maximized"), false) != w.maximized) {
            settings.putBoolean(key(key, "maximized"), w.maximized);
            touch();
        }
        if (!w.maximized) {
            store(key, w);
        }
    }

    /** Write {@code w}'s bounds under {@code key}, if they are not already what the file says. */
    private void store(String key, Watch w) {
        if (settings.getInt(key(key, "x"), Integer.MIN_VALUE) == w.x
                && settings.getInt(key(key, "y"), Integer.MIN_VALUE) == w.y
                && settings.getInt(key(key, "width"), -1) == w.width
                && settings.getInt(key(key, "height"), -1) == w.height) {
            return;
        }
        settings.putInt(key(key, "x"), w.x).putInt(key(key, "y"), w.y)
                .putInt(key(key, "width"), w.width).putInt(key(key, "height"), w.height);
        touch();
    }

    /** Write {@code w}'s zoom under {@code key}, if it has moved off what the file says. */
    private void storeZoom(String key, Watch w) {
        if (Math.abs(settings.getFloat(key(key, "zoom"), 0f) - w.zoom) < ZOOM_EPSILON) {
            return;
        }
        settings.putFloat(key(key, "zoom"), w.zoom);
        touch();
    }

    /** Stop watching {@code key} — its window is gone, and its last recorded bounds are the ones to keep. */
    public void forget(String key) {
        watched.remove(key);
    }

    /**
     * Main thread, once per frame: notice what moved or resized, and write the file once the movement settles.
     *
     * <p>A minimized window reports nothing useful, so it is skipped rather than recorded as being at the
     * bottom-left of nowhere.
     */
    public void poll() {
        for (Map.Entry<String, Watch> entry : watched.entrySet()) {
            Watch w = entry.getValue();
            // Zoom is checked before the minimized guard: it is a property of the tree, not of where the
            // window is, and Ctrl+= while a window is minimized is not a thing that happens but the reason it
            // is skipped below would not be a reason here.
            if (w.gui != null) {
                float now = w.gui.zoom().value();
                if (Math.abs(now - w.zoom) >= ZOOM_EPSILON) {
                    w.zoom = now;
                    storeZoom(entry.getKey(), w);
                }
            }
            if (w.window.isMinimized()) {
                continue;
            }
            boolean maximized = w.window.isMaximized();
            if (maximized != w.maximized) {
                w.maximized = maximized;
                settings.putBoolean(key(entry.getKey(), "maximized"), maximized);
                touch();
            }
            if (maximized) {
                // The bounds now are the screen's. Keep the ones to restore down to.
                continue;
            }
            int x = w.window.screenX();
            int y = w.window.screenY();
            int width = w.window.outerWidth();
            int height = w.window.outerHeight();
            if (x == w.x && y == w.y && width == w.width && height == w.height) {
                continue;
            }
            w.x = x;
            w.y = y;
            w.width = width;
            w.height = height;
            store(entry.getKey(), w);
        }
        if (dirty && System.nanoTime() - lastChange >= SETTLE_NANOS) {
            save();
        }
    }

    /**
     * Nanoseconds until the debounced write falls due, or {@link Long#MAX_VALUE} when nothing is
     * pending. Zero once it is due.
     *
     * <p>{@link #poll} writes {@code SETTLE_NANOS} after the last change, which is a deadline in the
     * future and therefore something a host loop has to be told about. A loop that parks when nothing
     * is happening has no next frame to discover it on: the drag ends, the last OS event goes by, and
     * the placement is written whenever something unrelated happens to wake the loop — or, if the
     * window has lost focus by then, not until {@link #save()} on the way out, so a kill loses it.
     *
     * <p>It belongs in the frame budget, alongside every other deadline the application holds:
     *
     * <pre>{@code
     * app.pacing(() -> Math.min(krono.kron().sleepTimeout().nanos(), memory.nanosUntilSettle()));
     * }</pre>
     *
     * <p>Costs one frame, 700 ms after a window stops moving, and only when one has.
     */
    public long nanosUntilSettle() {
        if (!dirty) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, SETTLE_NANOS - (System.nanoTime() - lastChange));
    }

    /** Write now, whatever the debounce thinks — the shutdown path, where there is no next frame. */
    public void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            settings.save();
        } catch (RuntimeException e) {
            // Settings are a convenience. Failing to write them must not take the application down with it.
            System.out.println("could not save window placement (" + e.getMessage() + ")");
        }
    }

    private void touch() {
        dirty = true;
        lastChange = System.nanoTime();
    }

    private static String key(String window, String field) {
        return "window." + window + "." + field;
    }
}
