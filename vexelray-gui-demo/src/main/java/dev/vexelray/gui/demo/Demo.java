package dev.vexelray.gui.demo;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.gui.widget.TextField;
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
        try (Tactroller input = openInput(gui);
             GuiApp app = new GuiApp("VexelRay GUI", W, H);
             Clipboard clipboard = openClipboard(gui)) {
            attachInput(input, gui, app);
            TactrollerInputBridge bridge = input == null ? null : bridgeFor(input, gui);
            app.run(gui, maxFrames, () -> pump(bridge));
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

    private record Refs(Node header, Node log) {
    }

    /** Build the dashboard with flex; return the handles the worker will mutate. */
    private static Refs buildUi(Gui gui) {
        Node header = gui.text("VexelRay GUI")
                .width(Length.FILL).height(Length.rem(4)).background(PANEL).textSize(Length.rem(1.75f)).textColor(INK)
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

        Node leftCard = gui.column().width(Length.FILL).height(Length.FILL)
                .background(PANEL).corner(Length.rem(1)).border(Length.rem(0.1f), LINE)
                .padding(Length.dp(20)).gap(Length.rem(0.625f))
                .children(
                        gui.text("Editable, multiline").height(Length.rem(1.875f)).textSize(Length.rem(1.375f))
                                .textColor(ACCENT),
                        notes.node());
        Node rightCard = gui.column().width(Length.FILL).height(Length.FILL)
                .background(PANEL).corner(Length.rem(1)).border(Length.rem(0.1f), LINE)
                .padding(Length.dp(20)).gap(Length.rem(0.625f))
                .children(
                        gui.text("Live from a worker").height(Length.rem(1.875f)).textSize(Length.rem(1.375f))
                                .textColor(ACCENT),
                        log);

        Node body = gui.row().width(Length.FILL).height(Length.FILL).padding(Length.dp(24)).gap(Length.rem(1.5f))
                .children(leftCard, rightCard);

        Node getStarted = button(gui, "Get started", Color.WHITE, ACCENT, ACCENT_HOVER, ACCENT_PRESSED, false);
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

        // Per-axis padding: small vertically so 44px buttons fit a 64px bar, but full horizontally (dp(24)) so the
        // first button aligns with the cards' left edge (body padding is also dp(24)). These are dp rather than
        // rem because they are frame, not content: zoom should grow what you are reading, not the gutter round it.
        Node controls = gui.row().width(Length.FILL).height(Length.rem(4)).padding(Length.dp(8), Length.dp(24))
                .gap(Length.rem(0.75f)).justify(Justify.START).alignItems(AlignItems.CENTER)
                .children(getStarted, button(gui, "Docs", DIM, PANEL, PANEL_HOVER, PANEL_PRESSED, true),
                        slider.node(), valueLabel);

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

        gui.root().background(BG).children(header, body, controls, fieldRow, footer);
        return new Refs(header, log);
    }

    /** A fixed-size labelled button that lightens on hover and darkens while pressed. */
    private static Node button(Gui gui, String label, Color fg, Color base, Color hover, Color pressed,
                               boolean bordered) {
        Node b = gui.text(label).width(Length.rem(10)).height(Length.rem(2.75f)).background(base)
                .corner(Length.rem(0.625f)).textColor(fg).align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);
        if (bordered) {
            b.border(Length.rem(0.1f), LINE);
        }
        // Restyle on pointer interaction — the handler runs on a worker thread and mutates via the handle.
        gui.onState(b, state -> b.background(switch (state) {
            case NORMAL -> base;
            case HOVER -> hover;
            case PRESSED -> pressed;
        }));
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
