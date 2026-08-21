package dev.vexelray.gui.demo;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppHome;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowInput;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.ContextMenu;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.Modals;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * vexelray-gui showcase — step 6. The UI is built declaratively through {@link Gui}/{@link Node} handles and laid
 * out by the flex engine (no hard-coded rects); a worker thread mutates it live; and the "Get started" button is
 * clickable — input flows tactroller → atchung → framework dispatch → a click handler that mutates the tree,
 * proving the whole vertical with app logic off the GUI thread.
 *
 * <p>Run: {@code Demo} (windowed, interactive), {@code Demo <frames>} (capped), {@code Demo --capture <out.png>}
 * (headless). Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class Demo {

    /** Window and capture size, in the engine's logical coordinates (see {@code attachInput} on why not pixels). */
    private static final int W = 900;
    private static final int H = 560;

    private static final Color BG = Color.rgb(0x11141b);
    private static final Color PANEL = Color.rgb(0x1b2130);
    private static final Color PANEL_HOVER = Color.rgb(0x232a3d);
    private static final Color PANEL_PRESSED = Color.rgb(0x151a26);
    private static final Color LINE = Color.rgb(0x2b3346);
    private static final Color ACCENT = Color.rgb(0x3aa0ff);
    private static final Color ACCENT_HOVER = Color.rgb(0x57b1ff);
    private static final Color ACCENT_PRESSED = Color.rgb(0x2b86e0);
    // Button blue: deeper than the text accent, so filled controls sit into the page rather than glowing on it
    // (and the letterpress glint has somewhere darker to catch).
    private static final Color BTN_BLUE = Color.rgb(0x2668b3);
    private static final Color BTN_BLUE_HOVER = Color.rgb(0x2f78c9);
    private static final Color BTN_BLUE_PRESSED = Color.rgb(0x1d548f);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final Color DIM = Color.rgb(0x93a0b4);

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        Gui gui = new Gui();
        // The smallest canvas this UI is still coherent on — a *floor*, not the design size. Setting it to the
        // design size leaves no headroom, so any window even slightly smaller starts cropping; this is the point
        // below which the layout would stop making sense, which is a good deal lower.
        gui.minSize(Length.em(40), Length.em(25));   // 640 x 400 at 1x
        Refs refs = buildUi(gui);
        zoomShortcuts(gui);

        if (args.length >= 1 && args[0].equals("--capture-zoom")) {
            // One run, the same tree captured at each step of the ladder: the em check, as a strip of images.
            // Every length in the UI resolves through zoom, so each file should be the previous one scaled —
            // any element that holds its pixel size while the rest grow is still pinned to device pixels (§6).
            for (float z : ZOOM_STEPS) {
                gui.zoom(z);
                GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, "gui-zoom-" + z + "x.png");
            }
            System.out.println("captured " + ZOOM_STEPS.length + " zoom levels");
            return;
        }
        if (args.length >= 1 && args[0].equals("--capture")) {
            GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, args.length >= 2 ? args[1] : "gui.png");
            System.out.println("captured");
            return;
        }
        if (args.length >= 1 && args[0].equals("--capture-live")) {
            startWorker(gui, refs);          // let a worker mutate the tree for a few seconds first
            Thread.sleep(3200);
            GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, args.length >= 2 ? args[1] : "gui-live.png");
            System.out.println("captured");
            return;
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        startWorker(gui, refs);        // app logic off the GUI thread, mutating via the bus
        // W and H are density-independent: the window is created at their *pixel* size on this display, so a UI
        // that honours density gets a window that honours it too. Sizing the window in raw pixels while the
        // content scales is the mismatch that leaves a 125% display showing three quarters of the UI.
        //
        // Placement persists across runs (~/.vexelray-demo/session.properties): the window is *created* at its
        // last bounds — outer rect on both sides of the round trip — rather than appearing and then jumping.
        // AppHome is the directory, Settings a file in it; bounds go in "session" rather than the default
        // "settings" store because they are where the user left the window, not a preference the user chose.
        Settings settings = AppHome.of("vexelray-demo").settings("session");
        WindowConfig windowConfig = WindowConfig
                .of("VexelRay GUI", settings.getInt("window.w", W), settings.getInt("window.h", H))
                .at(settings.getInt("window.x", WindowConfig.UNPOSITIONED),
                        settings.getInt("window.y", WindowConfig.UNPOSITIONED))
                // The GUI draws the frame. The window keeps every window-manager behaviour it had — the bounds
                // above still describe the same outer rect, so placement saved by an OS-framed run restores here
                // unchanged — and gains a title bar made of the same widgets as the rest of the UI.
                .decorations(Decorations.CLIENT);
        try (Tactroller input = openInput(gui);
             GuiApp app = new GuiApp(windowConfig);
             Clipboard clipboard = openClipboard(gui)) {
            attachInput(input, gui, app);
            refs.titleBar().controls(app.controls());   // the window exists now; point the chrome at it
            // One seam, and every window the framework opens from here on can hear the user: its own backend,
            // attached when the window is created, pumped by the loop, released with it. Dialogs and named
            // windows need nothing further to be interactive.
            app.input(Demo::windowInput);
            Modals.install(app);

            // The popup vertical, as a *named* window: "popup" is one window however many times it is asked for,
            // so clicking the button (or pressing Ctrl+`) while it is open raises the window that exists instead
            // of making a second one — and closing it does not lose its tree, which is waiting for the next show.
            AppWindow popup = app.window("popup",
                    () -> WindowSpec.of(WindowConfig.of("VexelRay popup", 420, 280), popupGui()));
            gui.onClick(refs.popupButton(), popup::show);
            gui.shortcut(Key.GRAVE_ACCENT, popup::toggle, Modifier.CONTROL);
            if (maxFrames > 0) {
                // Frames-capped runs exercise both window verticals: a named window, and a dialog that blocks
                // and dims everything else while it is up.
                popup.show();
                Modals.info("Modal dialog", "Shown by a frames-capped run, over a dimmed application.");
            }

            // A dialog is a value, from any thread, with no class behind it. While it is up, every other window
            // of this application is disabled by the window manager and dimmed.
            gui.onClick(refs.dialogButton(), () -> Modals.show(
                    Modal.of("A real window", "This dialog is an OS window of its own, owned by the main "
                                    + "window and drawn with the same chrome. The application behind it is "
                                    + "disabled and dimmed until you answer.\n\nAsk twice and the second "
                                    + "question waits its turn.")
                            .defaultButton("Ask again", () -> Modals.info("Second question",
                                    "Queued behind the first, presented when it closed."))
                            .cancelButton("Close", () -> { })));

            // Closing the window is a *request* the application may refuse. Installed only when there is input
            // to answer it with: a dialog nobody can click would be a window that cannot be closed at all.
            if (input != null) {
                app.onCloseRequest(request -> Modals.show(
                        Modal.of("Quit the demo?", "Window placement is saved either way.")
                                .defaultButton("Quit", request::proceed)
                                .cancelButton("Stay", request::cancel)));
            }
            TactrollerInputBridge bridge = input == null ? null : bridgeFor(input, gui);
            app.run(gui, maxFrames, () -> pump(bridge));
            // The window still exists here (close only *requested* the exit), so its final bounds are readable.
            settings.putInt("window.x", app.window().screenX())
                    .putInt("window.y", app.window().screenY())
                    .putInt("window.w", app.window().outerWidth())
                    .putInt("window.h", app.window().outerHeight())
                    .save();
        }
        gui.close();
        System.out.println("clean shutdown");
    }

    /** Zoom levels the capture ladder walks; the interactive shortcuts use {@link Gui#zoomRange} instead. */
    private static final float[] ZOOM_STEPS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0 — zoom in, out, reset. Registered here rather than in core because which chord
     * zooms (or whether zooming exists at all) is an application decision; {@code gui.shortcut} is an ordinary
     * {@code GLOBAL} claim, so a focused element that wants these chords can outrank them (§8).
     *
     * <p>The commands run on a worker thread and only commit to the zoom {@code State}; the next frame reads it
     * and relays out.
     */
    private static void zoomShortcuts(Gui gui) {
        gui.zoomRange(0.5f, 3f, 1.25f);
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        // Numpad equivalents, for keyboards where the main row needs a modifier to reach + at all.
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }

    /**
     * Open tactroller, attach it to the app window, and settle the two things that must agree about density.
     * Returns {@code null} (input disabled) if no backend is present, so the showcase still renders headless/in CI.
     *
     * <p><b>Coordinate space.</b> {@code FRAMEBUFFER}, not {@code CLIENT}. The GUI hit-tests input against the
     * rects it laid out, and it lays out in the viewport {@code GuiApp} hands it — which is the drawable the
     * Canvas and swapchain are sized to, i.e. pixels. On a Retina-class display a window's point extent is half
     * its pixel extent, so client-space coordinates would land at half their true position and every press would
     * hit the control above the one aimed at. On a 1:1 display the two spaces are the same number, which is
     * exactly why picking the wrong one survives development on Windows and fails on the first Mac
     * (DpiTest reproduces it by arithmetic).
     *
     * <p><b>Density.</b> Fed from {@code contentScale()}, so every {@code Length} resolves through the real
     * points-to-pixels ratio and the UI keeps its physical size on a dense screen. This comes from tactroller
     * because {@code NativeWindow} cannot answer it — it exposes one size and no scale (architecture.md §3, E4).
     */
    private static Tactroller openInput(Gui gui) {
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
     * <p>So the framework's density support is correct and tested ({@code DpiTest}, {@code Length.dp}) but cannot
     * be switched on here yet: it becomes live only once the process is DPI-aware and the engine reports a real
     * framebuffer extent, at which point the canvas is in pixels and both settings above flip together. That is
     * E4, and its packaging half — DPI awareness is declared by a manifest or {@code SetProcessDpiAwarenessContext}
     * rather than by code, and the engine owns window creation.
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
     * Open the OS clipboard and install it on the GUI so text widgets can cut/copy/paste. Returns {@code null}
     * (leaving the GUI's in-memory default in place) if no clipboard backend is present, so the showcase still
     * runs headless/in CI.
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
                        // best effort — a transient clipboard failure just drops the copy
                    }
                }
            });
            return clip;
        } catch (ClipboardException e) {
            System.out.println("clipboard unavailable (" + e.getMessage() + "); cut/copy/paste use in-memory buffer");
            return null;
        }
    }

    /** Bridge tactroller onto the GUI's bus: pumped once per frame, its edges feed the framework's dispatch. */
    private static TactrollerInputBridge bridgeFor(Tactroller input, Gui gui) {
        return new TactrollerInputBridge(input, gui.bus());
    }

    /**
     * Input for a window the framework opened on its own — a dialog, a named window: its own tactroller backend,
     * attached to that window's handle, bridged onto that window's bus, and pumped by the frame loop. This is the
     * whole of what an application has to say about input for every window it does not create itself
     * ({@link GuiApp#input}); the main window is still wired by hand above because it exists before the app does.
     *
     * <p>A window whose backend will not open renders and hears nothing, which is a degraded window rather than a
     * failed launch — the same policy as the main window's.
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

    private record Refs(Node header, Node log, Node popupButton, Node dialogButton, TitleBar titleBar) {
    }

    /**
     * A self-contained {@link Gui} for a popup window: its own tree, laid out against the popup's own viewport.
     * The handles are as thread-safe as any other, so a worker could mutate this tree live, exactly like the main
     * window's.
     */
    private static Gui popupGui() {
        Gui p = new Gui();
        Node card = p.column().width(Length.FILL).height(Length.FILL)
                .background(PANEL).corner(Length.rem(1)).border(Length.rem(0.1f), LINE)
                .lit(true).elevation(Length.rem(1.25f))
                .padding(Length.dp(16)).gap(Length.rem(0.5f))
                .children(
                        p.text("A true OS window").height(Length.rem(2)).textSize(Length.rem(1.375f))
                                .textColor(ACCENT),
                        p.text("Created on the main thread, presented by the same frame loop as the main "
                                        + "window, drawn by the same shared device and SDF pipeline. Close it "
                                        + "with the title-bar button; the app keeps running.")
                                .textSize(Length.rem(1)).textColor(DIM)
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP));
        p.root().background(BG).padding(Length.dp(16)).children(card);
        return p;
    }

    /** Build the dashboard with flex; return the handles the worker will mutate. */
    private static Refs buildUi(Gui gui) {
        // The horizontal padding is declared: a label's text area is its whole box (the phantom text inset is
        // editable-only now), so a left-aligned label that wants a margin says so.
        Node header = gui.text("VexelRay GUI")
                .width(Length.FILL).height(Length.rem(4)).background(PANEL).textSize(Length.rem(1.75f)).textColor(INK)
                .lit(true).elevation(Length.rem(0.5f))
                .padding(Length.ZERO, Length.em(0.625f))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);

        // Tail the log: pinned to the bottom as the worker appends lines, until the user scrolls up (§8.5).
        Node log = gui.column().width(Length.FILL).height(Length.FILL).gap(Length.rem(0.375f))
                .scrollLock(LayoutEnums.ScrollLock.BOTTOM);
        // Pre-fill enough lines to overflow the panel, so the auto vertical scrollbar appears (and reserves space).
        // Log lines set no height on purpose: a text node sizes to its *wrapped* content, so a long message that
        // wraps onto two lines reserves two lines. Pinning a height here would opt back out of that.
        for (int i = 1; i <= 16; i++) {
            log.append(gui.text("log line " + i + " — overflows, scrolls")
                    .textSize(Length.rem(1)).textColor(DIM));
        }

        // An editable *multiline* field: word-wrapped, Enter inserts a newline, Up/Down move by visual line and
        // keep a sticky column across short ones, and the view scrolls vertically to follow the caret. All of it
        // is widget code over the published layout read-model — core gained exactly one seam for it
        // (TextMeasurer.lineSpans) and no caret plumbing at all. Height comes from the card via FILL, so the
        // field is a fixed box that scrolls internally rather than growing (docs/layout-read-model.md §11.8).
        TextField notes = new TextField(gui,
                "Built through Node handles, mutated by messages, laid out by flex — no hard-coded rects.\n\n"
                        + "This paragraph is editable. It wraps at the card's width, Enter starts a new line, and "
                        + "Up/Down keep your column across short lines. Keep typing and the view follows the caret.")
                .multiline(true).wordWrap(true).lineNumbers(true);
        notes.node().width(Length.FILL).height(Length.FILL);
        // Formatting spans on a multiline field, set once and never touched again. The point is what happens
        // next: type before or inside them and they follow their text, because every edit remaps them through
        // its own TextEdit diff (req 12). Nothing re-runs a highlighter — the spans are not recomputed at all.
        notes.setSpans(java.util.List.of(
                dev.vexelray.gui.core.text.Span.foreground(14, 26, ACCENT),   // "Node handles"
                dev.vexelray.gui.core.text.Span.underline(39, 47),            // "messages"
                dev.vexelray.gui.core.text.Span.background(61, 65, LINE)));   // "flex"

        // A tabbed panel. Pages are hidden rather than removed, so the editor on the first tab keeps its content,
        // its caret and its handlers while another tab is up: switch away mid-sentence and come back to it.
        Node about = gui.text("Two pages, one panel. This page is a plain label; the other is the live editor.\n\n"
                        + "Switching hides a page rather than removing it, so nothing on it is rebuilt -- type in "
                        + "the editor, come back here, go back, and the caret is where you left it.")
                .textSize(Length.rem(1)).textColor(DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        // A tree explorer over the real filesystem: hasChildren answers from the directory bit without listing,
        // and children() runs its directory listing on the handler executor the first time a folder opens — the
        // ordered input stages never touch the disk. Collapse hides the subtree; re-opening it is free.
        TreeView<java.nio.file.Path> files = new TreeView<>(gui, new TreeView.Source<>() {
            @Override
            public java.util.List<java.nio.file.Path> roots() {
                return java.util.List.of(java.nio.file.Path.of(".").toAbsolutePath().normalize());
            }

            @Override
            public String label(java.nio.file.Path p) {
                java.nio.file.Path name = p.getFileName();
                return name == null ? p.toString() : name.toString();
            }

            @Override
            public boolean hasChildren(java.nio.file.Path p) {
                return java.nio.file.Files.isDirectory(p);
            }

            @Override
            public java.util.List<java.nio.file.Path> children(java.nio.file.Path p) {
                try (var kids = java.nio.file.Files.list(p)) {
                    return kids.sorted(java.util.Comparator
                                    .comparing((java.nio.file.Path k) -> !java.nio.file.Files.isDirectory(k))
                                    .thenComparing(k -> k.getFileName().toString().toLowerCase()))
                            .toList();
                } catch (java.io.IOException e) {
                    return java.util.List.of();   // an unreadable directory shows as empty, not as a crash
                }
            }
        });
        files.node().width(Length.FILL).height(Length.FILL);

        // A context menu on the tree: right-click a row and it opens at the pointer, floating over the page (a
        // floating last child of the root — no layer machinery, no reflow). Esc or a click elsewhere dismisses.
        var contextTarget = new java.util.concurrent.atomic.AtomicReference<java.nio.file.Path>();
        ContextMenu fileMenu = new ContextMenu(gui)
                .item("Open", () -> log.append(gui.text("open: " + contextTarget.get())
                        .textSize(Length.rem(1)).textColor(INK)))
                .item("Copy path", () -> gui.clipboard().set(String.valueOf(contextTarget.get())))
                .separator()
                .item("Properties", () -> log.append(gui.text("properties: " + contextTarget.get())
                        .textSize(Length.rem(1)).textColor(DIM)));
        files.onContext((path, e) -> {
            contextTarget.set(path);
            fileMenu.show(e.x(), e.y());
        });

        Tabs tabs = new Tabs(gui);
        tabs.add("Editor", notes.node());
        tabs.add("Files", files.node());
        tabs.add("About", about);

        // Cards float: a lit fill (top-left edge light + faint vertical gradient) over a soft analytic shadow.
        // Both are transfer functions of the same rounded-box SDF the fill already evaluates — no textures.
        Node leftCard = gui.column().width(Length.FILL).height(Length.FILL)
                .background(PANEL).corner(Length.rem(1)).border(Length.rem(0.1f), LINE)
                .lit(true).elevation(Length.rem(1.25f))
                .padding(Length.dp(20)).gap(Length.rem(0.625f))
                .children(tabs.node());
        Node rightCard = gui.column().width(Length.FILL).height(Length.FILL)
                .background(PANEL).corner(Length.rem(1)).border(Length.rem(0.1f), LINE)
                .lit(true).elevation(Length.rem(1.25f))
                .padding(Length.dp(20)).gap(Length.rem(0.625f))
                .children(
                        gui.text("Live from a worker").height(Length.rem(1.875f)).textSize(Length.rem(1.375f))
                                .textColor(ACCENT),
                        log);

        Node body = gui.row().width(Length.FILL).height(Length.FILL).padding(Length.dp(24)).gap(Length.rem(1.5f))
                .children(leftCard, rightCard);

        Node getStarted = button(gui, "Get started", Color.WHITE, BTN_BLUE, BTN_BLUE_HOVER, BTN_BLUE_PRESSED, false)
                .textSunken(true);   // white on accent: letterpress the label for contrast
        // The click vertical: tactroller -> atchung -> dispatch -> this handler (on a worker thread), which
        // mutates the tree through handles just like the background worker does.
        AtomicInteger clicks = new AtomicInteger();
        gui.onClick(getStarted, () -> {
            int n = clicks.incrementAndGet();
            log.append(gui.text("clicked \"Get started\" x" + n)
                    .height(Length.rem(1.5f)).textSize(Length.rem(1)).textColor(ACCENT));
        });

        // A slider (drag with pointer capture) driving a live value label.
        Node valueLabel = gui.text("50%").width(Length.rem(5)).textSize(Length.rem(1)).textColor(INK)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        Slider slider = new Slider(gui, 0.5f).onChange(v -> valueLabel.text(Math.round(v * 100) + "%"));
        slider.node().width(Length.rem(14));

        // An editable single-line text field: typed text flows tactroller CharTyped -> bus -> dispatch -> onChar;
        // Enter submits into the log. Proves the keyboard/focus/text vertical end to end.
        TextField field = new TextField(gui, "type here, Enter to log — long text scrolls and is masked at the edge");
        field.node().width(Length.rem(18));
        // Formatting spans: they auto-diff — colours/underline stay attached to their text as you edit.
        field.setSpans(java.util.List.of(
                dev.vexelray.gui.core.text.Span.foreground(0, 4, ACCENT),      // "type"
                dev.vexelray.gui.core.text.Span.background(5, 9, LINE),         // "here"
                dev.vexelray.gui.core.text.Span.underline(11, 16)));           // "Enter"
        field.onSubmit(s -> log.append(gui.text("submitted: " + s)
                .textSize(Length.rem(1)).textColor(INK)));

        // Wrap vs horizontal scroll, toggled live. Both are the same field: turning wrap off makes the node
        // report content wider than its box, which is what grows an h-scrollbar — a text leaf is a scroll
        // citizen like any container. A wrapped node never scrolls horizontally, because nothing lies to the
        // right of a wrapped line to reach.
        // A toggle, not a button: its base palette depends on its own state (accent while on, panel while off),
        // and hover/press shade whichever palette is current. One handler owns all restyling, reading the toggle
        // state plus the last interaction state, so a flip mid-hover repaints correctly.
        boolean[] wrapping = {true};
        Node wrapToggle = gui.text("Wrap: on").width(Length.rem(10)).height(Length.rem(2.75f))
                .corner(Length.rem(0.625f)).border(Length.rem(0.1f), LINE)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(true);
        var lastState = new java.util.concurrent.atomic.AtomicReference<>(
                dev.vexelray.gui.core.input.InteractionState.NORMAL);
        Runnable restyleWrap = () -> {
            boolean on = wrapping[0];
            var state = lastState.get();
            wrapToggle.text(on ? "Wrap: on" : "Wrap: off").textColor(on ? Color.WHITE : DIM)
                    .textSunken(on);   // letterpress only while white-on-accent; the off state is low-contrast

            wrapToggle.background(switch (state) {
                case NORMAL -> on ? BTN_BLUE : PANEL;
                case HOVER -> on ? BTN_BLUE_HOVER : PANEL_HOVER;
                case PRESSED -> on ? BTN_BLUE_PRESSED : PANEL_PRESSED;
            });
            wrapToggle.elevation(switch (state) {
                case NORMAL -> Length.rem(0.375f);
                case HOVER -> Length.rem(0.625f);
                case PRESSED -> Length.ZERO;
            });
        };
        restyleWrap.run();
        gui.onState(wrapToggle, state -> {
            lastState.set(state);
            restyleWrap.run();
        });
        gui.onClick(wrapToggle, () -> {
            wrapping[0] = !wrapping[0];
            notes.wordWrap(wrapping[0]);
            restyleWrap.run();
        });

        // Per-axis padding: small vertically so 44px buttons fit a 64px bar, but full horizontally (dp(24)) so the
        // first button aligns with the cards. left edge (body padding is also dp(24)). These are dp rather than
        // rem because they are frame, not content: zoom should grow what you are reading, not the gutter round it.
        Node popupButton = button(gui, "Popup", DIM, PANEL, PANEL_HOVER, PANEL_PRESSED, true);
        Node dialogButton = button(gui, "Dialog", DIM, PANEL, PANEL_HOVER, PANEL_PRESSED, true);
        Node controls = gui.row().width(Length.FILL).height(Length.rem(4)).padding(Length.dp(8), Length.dp(24))
                .gap(Length.rem(0.75f)).justify(Justify.START).alignItems(AlignItems.CENTER)
                .children(getStarted, button(gui, "Docs", DIM, PANEL, PANEL_HOVER, PANEL_PRESSED, true),
                        wrapToggle, popupButton, dialogButton, slider.node(), valueLabel);

        Node fieldRow = gui.row().width(Length.FILL).height(Length.rem(3.25f))
                .padding(Length.dp(6), Length.dp(24)).gap(Length.rem(0.75f))
                .alignItems(AlignItems.CENTER).scroll(false, false)
                .children(
                        gui.text("Field:").width(Length.rem(4)).textSize(Length.rem(1)).textColor(DIM)
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        field.node());

        Node footer = gui.text("flex layout: rows/columns, padding/margin/border, border-box, relative units")
                .width(Length.FILL).height(Length.rem(2.25f)).textSize(Length.rem(0.9375f)).textColor(DIM)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);

        // Tooltips: hover a control and a hit-inert bubble appears below it — drawn over the page, invisible to
        // the pointer, so nothing about the hover target changes. It coexists with each button's own hover
        // restyle because interaction-state observers accumulate.
        new Tooltip(gui)
                .attach(getStarted, "Append a line to the live log")
                .attach(popupButton, "Open a true OS popup window (Ctrl+`)")
                .attach(dialogButton, "Ask a question in a modal dialog")
                .attach(wrapToggle, "Toggle word wrap in the editor");

        // The window's own chrome, drawn by the GUI: a title bar that is a row of widgets, and two declarations
        // (WindowRegion.DRAG on the strip, INTERACTIVE on each button) that tell the window manager which of
        // those pixels are caption. Dragging, snapping, double-click-to-maximize and the system menu stay
        // Windows'. Bound to the real window in main(), once there is one.
        TitleBar titleBar = new TitleBar(gui, WindowControls.NONE, "VexelRay GUI");

        gui.root().background(BG).children(titleBar.node(), header, body, controls, fieldRow, footer);
        return new Refs(header, log, popupButton, dialogButton, titleBar);
    }

    /** A fixed-size labelled button that lightens on hover and darkens while pressed. */
    private static Node button(Gui gui, String label, Color fg, Color base, Color hover, Color pressed,
                               boolean bordered) {
        Node b = gui.text(label).width(Length.rem(10)).height(Length.rem(2.75f)).background(base)
                .corner(Length.rem(0.625f)).textColor(fg).align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(true).elevation(Length.rem(0.375f));
        if (bordered) {
            b.border(Length.rem(0.1f), LINE);
        }
        // Restyle on pointer interaction — the handler runs on a worker thread and mutates via the handle.
        // Depth is part of the feedback: hover lifts the button a little, pressing sets it down flush.
        gui.onState(b, state -> {
            b.background(switch (state) {
                case NORMAL -> base;
                case HOVER -> hover;
                case PRESSED -> pressed;
            });
            b.elevation(switch (state) {
                case NORMAL -> Length.rem(0.375f);
                case HOVER -> Length.rem(0.625f);
                case PRESSED -> Length.ZERO;
            });
        });
        return b;
    }

    private static void startWorker(Gui gui, Refs refs) {
        gui.async(() -> {
            int n = 0;
            try {
                while (true) {
                    Thread.sleep(900);
                    n++;
                    refs.header().text("VexelRay GUI    tick " + n);
                    if (n <= 8) {
                        refs.log().append(gui.text("event " + n + " from worker thread")
                                .textSize(Length.rem(1)).textColor(INK));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private Demo() {
    }
}
