package dev.vexelray.gui.demo;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.AppHome;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowInput;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.os.Decorations;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

/**
 * The vexelray-gui gallery: one chapter per part of the framework, in one window.
 *
 * <p>The demo used to be a single screen that grew a button every time the framework grew a feature, and a
 * screen that grows that way ends up demonstrating that there are many features and nothing about any of them.
 * It is now a {@link Shell} — a navigation rail, a page, an activity rail — over a list of {@link Chapter}s, and
 * adding a subsystem to the demo is adding a file to {@link Gallery}.
 *
 * <p>What is left in this class is the application edge, which is exactly the part a client application has to
 * write for itself: opening an input backend and settling the coordinate space, installing a clipboard,
 * remembering where the window was, wiring the frame loop, and deciding what closing the window means. Every
 * one of those is a decision the framework deliberately does not take.
 *
 * <p>Run: {@code Demo} (windowed, interactive), {@code Demo <frames>} (capped), {@code Demo --capture <out.png>}
 * (headless), {@code Demo --capture-zoom} (the em check, as a strip of images). Needs
 * {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class Demo {

    /** The application's own name, which is what its settings directory is called. */
    private static final String APP = "vexelray-demo";

    /** Window size on a first run, in the engine's logical coordinates (see {@code attachInput}). */
    private static final int W = 1240;
    private static final int H = 780;

    /** Zoom levels the capture ladder walks; the interactive shortcuts move within {@link #look}'s range. */
    private static final float[] ZOOM_STEPS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        Gui gui = new Gui();
        // The smallest canvas this UI is still coherent on — a *floor*, not the design size. Setting it to the
        // design size leaves no headroom, so any window even slightly smaller starts cropping; this is the point
        // below which two rails and a page stop making sense, which is a good deal lower.
        gui.minSize(Length.em(52), Length.em(30));
        // The look is a preference, so it lives where preferences live — and it is applied before the UI is
        // built, because a role resolves when a prop is written. One call, and every widget, every piece of the
        // renderer's own chrome and the clear colour below all follow it. Held as a value rather than applied
        // and forgotten, because the dialogs further down need the same answer this window got.
        Theme theme = "light".equalsIgnoreCase(AppHome.of(APP).settings().getString("theme", "dark"))
                ? Theme.LIGHT
                : Theme.DARK;
        look(gui, theme);
        // The frame clock. Attached before the UI is built, because a widget that animates is handed its timing
        // at construction, and ticked from the run loop's beforeFrame hook below — one tick per presented frame.
        KronoGui krono = KronoGui.attach(gui);

        Shell shell = new Shell(gui, krono, Gallery.chapters());
        zoomShortcuts(gui);
        // The Vulkan clear colour: the same role the root paints, so the frame behind the tree is never a second
        // opinion about what the page is.
        Color page = gui.theme().color(Role.PAGE);

        if (args.length >= 1 && args[0].equals("--capture-zoom")) {
            // One run, the same tree captured at each step of the ladder: the em check, as a strip of images.
            // Every length in the UI resolves through zoom, so each file should be the previous one scaled —
            // any element that holds its pixel size while the rest grow is still pinned to device pixels.
            for (float z : ZOOM_STEPS) {
                gui.zoom(z);
                GuiApp.capture(gui, W, H, page.r(), page.g(), page.b(), "gui-zoom-" + z + "x.png");
            }
            System.out.println("captured " + ZOOM_STEPS.length + " zoom levels");
            return;
        }
        if (args.length >= 1 && args[0].equals("--capture")) {
            // A capture may name the chapter it wants, so the gallery can be documented a page at a time
            // without the shot having to be taken by hand.
            if (args.length >= 3) {
                shell.selectNow(Integer.parseInt(args[2]));
            }
            GuiApp.capture(gui, W, H, page.r(), page.g(), page.b(),
                    args.length >= 2 ? args[1] : "gui.png");
            System.out.println("captured");
            return;
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        startWorker(gui, shell.console());

        // Placement is read before the window exists, so the gallery is *created* where it was left rather than
        // appearing and then moving — and clamped on the way, because the desk may have changed shape since.
        // Every window here goes through the same three lines: config it, restore it, watch it.
        WindowMemory memory = new WindowMemory(Settings.open(APP));
        try (Tactroller input = openInput();
             GuiApp app = new GuiApp(memory.config("main", "VexelRay GUI", W, H)
                     // The GUI draws the frame. The window keeps every window-manager behaviour it had —
                     // dragging, snapping, Win+arrow, double-click-to-maximize, the system menu — and gains a
                     // title bar made of the same widgets as the rest of the UI.
                     .decorations(Decorations.CLIENT));
             Clipboard clipboard = openClipboard(gui)) {

            attachInput(input, gui, app);
            shell.titleBar().controls(app.controls());   // the window exists now; point the chrome at it
            if (memory.maximized("main")) {
                app.window().maximize();
            }
            // Watched with its tree, so the UI zoom is remembered too: Ctrl+= is the same kind of decision as
            // dragging the window bigger, and losing it on quit is the same loss.
            memory.watch("main", app.window(), gui);
            // One seam, and every window the framework opens from here on can hear the user: its own backend,
            // attached when the window is created, pumped by the loop, released with it.
            app.input(Demo::windowInput);
            // The dialogs get the look this window got. Modals builds a tree of its own, so anything not
            // handed to it here is a window this application's look never reached — which is what a dialog
            // drawn dark inside the light theme was, before the seam existed to hand it through.
            Modals.install(app, dialog -> look(dialog, theme));
            // Everything a chapter registered against a window it could not yet have.
            shell.stage().started(app);

            if (maxFrames > 0) {
                // A frames-capped run exercises the window verticals it would otherwise never reach: a dialog,
                // and the named popup — which is looked up by its key rather than handed over by the chapter
                // that registered it, since that is the whole point of a window having a name.
                Modals.info("Modal dialog", "Shown by a frames-capped run, over a dimmed application.");
                app.window("popup").ifPresent(dev.vexelray.gui.core.app.AppWindow::show);
            }

            // Closing the window is a *request* the application may refuse. Installed only when there is input
            // to answer it with: a dialog nobody can click would be a window that cannot be closed at all.
            if (input != null) {
                app.onCloseRequest(request -> Modals.show(
                        Modal.of("Quit the gallery?", "Window placement and zoom are saved either way.")
                                .defaultButton("Quit", request::proceed)
                                .cancelButton("Stay", request::cancel)));
            }

            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
            if (maxFrames <= 0) {
                // Render on demand: park until something says a frame is due, and only on an uncapped run — a
                // frame cap is a script, and blocking would make N frames of a still window take forever rather
                // than N presents.
                //
                // Every deadline this application holds, in one supplier. The clock knows about animations; it
                // does not know that the window placement is 700ms from being written, and a loop that parks has
                // no next frame on which to discover that.
                app.pacing(() -> Math.min(krono.kron().sleepTimeout().nanos(), memory.nanosUntilSettle()));
                app.idleRefresh(200_000_000L)   // 5 Hz floor while focused: a missed wake is late, never lost
                        .maxFrameRate(16_666_666L);   // 60 Hz ceiling while animating
                // And the wakes, without which the parking above is a hang rather than a saving: a worker's
                // mutation and a timeline's tick are not OS input, so each has to nudge the message queue.
                gui.onWork(app::postWake);
                krono.kron().onWork(app::postWake);
            }
            try {
                // Input first, then the clock: the tick returns with its batch complete, so anything an
                // animation posts this frame is on the bus before Gui.frame reconciles it — the frame that
                // presents a value is the frame that computed it.
                app.run(gui, maxFrames, () -> {
                    pump(bridge);
                    krono.tick();
                    memory.poll();
                });
            } finally {
                // The debounce has no next frame to fire on once the loop is over, so the last move of the
                // session is written here or not at all.
                memory.save();
            }
        }
        krono.close();   // the clock outlives the window but not the process: closed with the GUI it drove
        gui.close();
        System.out.println("clean shutdown");
    }

    /**
     * This application's look, applied to a tree: the theme, and how far that tree may zoom. One method with
     * two callers, which is the whole point — the main window and the dialogs are separate trees, and a look
     * applied to one of them is a look the other never heard about. The framework's {@code Appearance.applyTo}
     * is this method, generalised; an application on the framework passes that instead.
     */
    private static Gui look(Gui gui, Theme theme) {
        return gui.theme(theme).zoomRange(0.5f, 3f, 1.25f);
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0 — zoom in, out, reset. Registered here rather than in core because which chord
     * zooms (or whether zooming exists at all) is an application decision; {@code gui.shortcut} is an ordinary
     * {@code GLOBAL} claim, so a focused element that wants these chords can outrank it. The range these move
     * within is part of the look, and set in {@link #look}.
     */
    private static void zoomShortcuts(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        // Numpad equivalents, for keyboards where the main row needs a modifier to reach + at all.
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }

    /**
     * Open tactroller. Returns {@code null} (input disabled) if no backend is present, so the gallery still
     * renders headless and in CI — a window nobody can click is a degraded window rather than a failed launch.
     */
    private static Tactroller openInput() {
        try {
            Tactroller t = Tactroller.open();
            System.out.println("input: " + t.backendName());
            return t;
        } catch (BackendException e) {
            System.out.println("input unavailable (" + e.getMessage() + "); running without pointer input");
            return null;
        }
    }

    /**
     * Attach input to the window and settle the coordinate space.
     *
     * <p><b>{@code CLIENT}, and density deliberately left at 1.0.</b> Both follow from one fact: the engine's
     * window and {@code Canvas} are in <em>logical</em> (client) coordinates, not framebuffer pixels. On a 125%
     * display that has two consequences, and getting either wrong is visible immediately:
     *
     * <ul>
     *   <li>{@code FRAMEBUFFER} coordinates are {@code CLIENT × contentScale}, so input would arrive 25% further
     *       right and further down than the cursor actually is, and every press would land past its target.</li>
     *   <li>The OS is already scaling a logical-space window's output. Feeding {@code contentScale()} into
     *       {@link Gui#dpi} scales the content <em>again</em> — 1.56x in total — which reads as "everything is
     *       too big" and overflows the window.</li>
     * </ul>
     *
     * <p>So the framework's density support is correct and tested and cannot be switched on here yet: it becomes
     * live only once the process is DPI-aware and the engine reports a real framebuffer extent, at which point
     * the canvas is in pixels and both settings above flip together.
     */
    private static void attachInput(Tactroller input, Gui gui, GuiApp app) {
        if (input == null) {
            return;
        }
        try {
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
            System.out.println("display scale: " + input.contentScale()
                    + "x (not applied — see attachInput; the canvas is logical, the OS already scales it)");
        } catch (BackendException e) {
            System.out.println("input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /**
     * Open the OS clipboard and install it on the GUI so text widgets can cut, copy and paste. Returns
     * {@code null} — leaving the GUI's in-memory default in place — if no clipboard backend is present.
     */
    private static Clipboard openClipboard(Gui gui) {
        try {
            Clipboard clip = Clipboard.open();
            gui.clipboard(new TextClipboard() {

                @Override
                public String get() {
                    try {
                        return clip.getText().orElse("");
                    } catch (ClipboardException e) {
                        return "";
                    }
                }

                @Override
                public void set(String text) {
                    try {
                        clip.setText(text);
                    } catch (ClipboardException e) {
                        // Best effort — a transient clipboard failure just drops the copy.
                    }
                }
            });
            return clip;
        } catch (ClipboardException e) {
            System.out.println("clipboard unavailable (" + e.getMessage() + "); cut/copy/paste use a buffer");
            return null;
        }
    }

    /**
     * Input for a window the framework opened on its own — a dialog, a named window: its own tactroller backend,
     * attached to that window's handle, bridged onto that window's bus, and pumped by the frame loop. This is
     * the whole of what an application has to say about input for every window it does not create itself; the
     * main window is still wired by hand above because it exists before the app does.
     */
    private static WindowInput windowInput(dev.vexelray.os.NativeWindow window, Gui windowGui) {
        Tactroller backend;
        try {
            backend = Tactroller.open();
            backend.attach(NativeWindow.ofHwnd(window.osHandle()));
            backend.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.out.println("input unavailable for this window (" + e.getMessage() + ")");
            return WindowInput.NONE;
        }
        TactrollerInputBridge bridge = new TactrollerInputBridge(backend, windowGui.bus());
        return new WindowInput() {

            @Override
            public void pump() {
                Demo.pump(bridge);
            }

            @Override
            public void close() {
                backend.close();
            }
        };
    }

    /** Snapshot input onto the bus for this frame; a transient backend poll failure just skips the frame. */
    private static void pump(TactrollerInputBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.pump();
        } catch (BackendException e) {
            // Transient poll failure — drop this frame's input rather than tear down the loop.
        }
    }

    /**
     * Application logic off the GUI thread, mutating the tree through handles exactly as a click handler does.
     *
     * <p>It says something once and then stops, which is the honest version of this demonstration: a worker that
     * chattered forever would keep the render-on-demand loop awake for no reason, and a gallery whose activity
     * rail is always moving cannot show you that something you did moved it.
     */
    private static void startWorker(Gui gui, Console console) {
        gui.async(() -> {
            try {
                Thread.sleep(600);
                console.note("worker thread reporting in — this line was appended from off the GUI thread");
                Thread.sleep(900);
                console.note("every handle is thread-safe: a mutation is a message, reconciled next frame");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private Demo() {
    }
}
