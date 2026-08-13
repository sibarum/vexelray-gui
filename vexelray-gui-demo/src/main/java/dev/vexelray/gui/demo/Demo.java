package dev.vexelray.gui.demo;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;

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
        Refs refs = buildUi(gui);

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
        try (GuiApp app = new GuiApp("VexelRay GUI", W, H);
             Tactroller input = openInput(app, gui)) {
            TactrollerInputBridge bridge = input == null ? null : bridgeFor(input, gui);
            app.run(gui, maxFrames, () -> pump(bridge));
        }
        gui.close();
        System.out.println("clean shutdown");
    }

    /**
     * Open tactroller and attach it to the app window for client-space coordinates + focus gating. Returns
     * {@code null} (input disabled) if no backend is present, so the showcase still renders headless/in CI.
     */
    private static Tactroller openInput(GuiApp app, Gui gui) {
        try {
            Tactroller t = Tactroller.open();
            t.attach(NativeWindow.ofHwnd(app.windowHandle()));
            t.setCoordinateSpace(CoordinateSpace.CLIENT);
            System.out.println("input: " + t.backendName());
            return t;
        } catch (BackendException e) {
            System.out.println("input unavailable (" + e.getMessage() + "); running without pointer input");
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

        Node log = gui.column().width(Length.FILL).height(Length.FILL).gap(Length.rem(0.375f));
        // Pre-fill enough lines to overflow the panel, so the auto vertical scrollbar appears (and reserves space).
        for (int i = 1; i <= 16; i++) {
            log.append(gui.text("log line " + i + " — overflows, scrolls")
                    .height(Length.rem(1.5f)).textSize(Length.rem(1)).textColor(DIM));
        }

        Node leftCard = card(gui, "Retained tree", ACCENT,
                "Built through Node handles, mutated by messages, laid out by flex - no hard-coded rects.")
                .width(Length.FILL).height(Length.FILL);
        Node rightCard = gui.column().width(Length.FILL).height(Length.FILL)
                .background(PANEL).corner(Length.rem(1)).border(Length.rem(0.1f), LINE)
                .padding(Length.rem(1.25f)).gap(Length.rem(0.625f))
                .children(
                        gui.text("Live from a worker").height(Length.rem(1.875f)).textSize(Length.rem(1.375f))
                                .textColor(ACCENT),
                        log);

        Node body = gui.row().width(Length.FILL).height(Length.FILL).padding(Length.rem(1.5f)).gap(Length.rem(1.5f))
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

        Node controls = gui.row().width(Length.FILL).height(Length.rem(4)).padding(Length.rem(1.5f))
                .gap(Length.rem(0.75f)).justify(Justify.START).alignItems(AlignItems.CENTER)
                .children(getStarted, button(gui, "Docs", DIM, PANEL, PANEL_HOVER, PANEL_PRESSED, true),
                        slider.node(), valueLabel);

        Node footer = gui.text("flex layout: rows/columns, padding/margin/border, border-box, relative units")
                .width(Length.FILL).height(Length.rem(2.25f)).textSize(Length.rem(0.9375f)).textColor(DIM)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);

        gui.root().background(BG).children(header, body, controls, footer);
        return new Refs(header, log);
    }

    /** A bordered card: coloured title row + a wrapped body paragraph. */
    private static Node card(Gui gui, String title, Color titleColor, String body) {
        return gui.column().background(PANEL).corner(Length.rem(1)).border(Length.rem(0.1f), LINE)
                .padding(Length.rem(1.25f)).gap(Length.rem(0.625f))
                .children(
                        gui.text(title).height(Length.rem(1.875f)).textSize(Length.rem(1.375f)).textColor(titleColor),
                        gui.text(body).height(Length.FILL).textSize(Length.rem(1.0625f)).textColor(DIM)
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP));
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
                                .height(Length.rem(1.5f)).textSize(Length.rem(1)).textColor(INK));
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
