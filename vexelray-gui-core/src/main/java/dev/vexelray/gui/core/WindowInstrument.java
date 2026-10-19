package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * One framework-owned tool in a window's title bar — a screenshot, later a macro recorder (docs/reference/automation.md
 * §7). A value: what to draw, what to call it, and what to ask the window when it is clicked.
 *
 * <p><b>Why the framework owns these and an application does not put its own buttons here.</b> A title bar is
 * window chrome, and chrome belongs to whoever owns the window. An application contributes identity — its title,
 * an icon as identity — and never controls; a control an application needs belongs in the application's own UI.
 * That is not tidiness. It is the only thing that makes a utility free everywhere: the moment one application
 * puts its own button in the caption, the strip is application-addressable, and no framework instrument can rely
 * on the space existing or meaning the same thing from one window to the next.
 *
 * <p><b>Rendered, never interpreted.</b> {@code TitleBar} draws whatever list it is handed and branches on
 * nothing here — so a module this one has never heard of (the automation module, an inspector) can contribute an
 * instrument without any of this changing. The same posture {@link WindowControls} takes: the bar commands an
 * interface and knows no implementations.
 *
 * <p><b>Opt-in.</b> A bar has no instruments unless it is given some. A shipped application must not find itself
 * carrying a macro-record button it never asked for, so {@link #standard()} is a set to reach for rather than
 * one that arrives by default.
 *
 * @param role    the {@code Node.role} the instrument's button declares, so an agent can find it by name
 * @param tooltip what it is called, shown on hover of the button (never changing the button's own size)
 * @param mark    its icon, as a function of the button's box <b>and the ink the bar hands it</b>. A function of
 *                the box because a {@link Picture} is authored in pixels and so has to be rebuilt when the box
 *                changes, exactly as a {@code Cue} is. A function of the ink because an instrument does not get
 *                to pick a colour: the bar it sits in reads that from the theme like every other caption icon,
 *                so a second theme does not have to fork this file to exist.
 * @param action  what it asks of the window it is in
 * @param live    what the mark and tooltip show changes while the window is open, and this says when; {@code null}
 *                for a mark that only changes with its box
 */
public record WindowInstrument(String role, String tooltip,
                               java.util.function.BiFunction<Rect, Color, Picture> mark,
                               Consumer<WindowControls> action, Live live) {

    /** Where a screenshot goes when nobody said: a timestamped PNG in the working directory. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public WindowInstrument {
        role = role == null ? "" : role;
        tooltip = tooltip == null ? "" : tooltip;
    }

    /** An instrument whose mark changes only with its box. */
    public WindowInstrument(String role, String tooltip, java.util.function.BiFunction<Rect, Color, Picture> mark,
                            Consumer<WindowControls> action) {
        this(role, tooltip, mark, action, null);
    }

    /**
     * What an instrument that shows a state outside the bar (the automation sockets' lamps) says about it. The bar
     * re-authors the mark, and reads the tooltip again, after every {@code changed}, which may come from any thread:
     * a node's setters are thread-safe.
     */
    public interface Live {

        /** Call {@code changed} after every change to what the mark or tooltip shows; return what stops it. */
        Runnable watch(Runnable changed);

        /** The tooltip now, or {@code null} for the instrument's own. */
        default String tooltip() {
            return null;
        }
    }

    /**
     * One automation socket's lamp beside the plug (docs/reference/automation.md §5, <i>Lamps</i>). It shows two facts:
     * whether the port is open now (a ring), and whether anything has connected to it this session (the centre).
     */
    public enum Lamp {
        /** Not open, and nothing connected to it this session (never opened, or opened and closed unused): no lamp. */
        OFF,
        /** Open, and nothing has connected yet: the ring. */
        LISTENING,
        /** Open, and something is connected now: the ring, filled. */
        CONNECTED,
        /** Open; something was connected, and has gone: the ring, with a grey dot. */
        WAS_CONNECTED,
        /** Closed, after something had connected this session: the grey dot alone. */
        CLOSED_AFTER_USE
    }

    /**
     * The two lamps and their colours: remote control left of the plug, diagnostics right. {@code used} is the grey
     * of the dot that says something connected earlier. A {@code null} lamp (a socket this run cannot open) draws
     * nothing.
     */
    public record Lamps(Lamp control, Lamp diagnostics, Color controlColor, Color diagnosticsColor, Color used) {
    }

    /**
     * The instruments a window gets by asking for them: today, the screenshot. Kept as a list rather than as
     * individual factories so that adding one is a change here rather than in every application that wanted
     * "the usual tools".
     */
    public static List<WindowInstrument> standard() {
        return List.of(screenshot());
    }

    /**
     * Photograph this window into a timestamped PNG in the working directory.
     *
     * <p>The picture is of the window the bar sits in — a bar in a popup photographs the popup — because
     * {@link WindowControls} is per window and this asks it, rather than asking the application.
     */
    public static WindowInstrument screenshot() {
        return screenshot(() -> "vexelray-shot-" + LocalDateTime.now().format(STAMP) + ".png");
    }

    /** As {@link #screenshot()}, naming each file through {@code path} — one call per click. */
    public static WindowInstrument screenshot(java.util.function.Supplier<String> path) {
        return screenshot("Screenshot this window", controls -> controls.capture(path.get()));
    }

    /**
     * The screenshot button, with {@code take} deciding where the picture goes and taking it — for one that has
     * to ask first, and may be told no. The same role and mark, so an agent finds it by the same name however it
     * files its pictures. {@code vexelray-gui-nfd}'s {@code SaveScreenshot} is the one that asks.
     */
    public static WindowInstrument screenshot(String tooltip, Consumer<WindowControls> take) {
        return new WindowInstrument("instrument-screenshot", tooltip, WindowInstrument::cameraMark, take);
    }

    /**
     * The key-bindings button: {@code open} shows whatever lets the user change the application's chords. Like
     * the screenshot, the button — role and mark — is core's, so an agent finds it by one name in every
     * application; what it opens is the caller's. {@code vexelray-gui-keymap}'s {@code KeymapWindow} is the one
     * that opens the editor.
     *
     * <p>Never in {@link #standard()}: an application without rebindable commands has nothing for it to open.
     */
    public static WindowInstrument keymap(String tooltip, Consumer<WindowControls> open) {
        return new WindowInstrument("instrument-keymap", tooltip, WindowInstrument::keyboardMark, open);
    }

    /**
     * The automation button: {@code open} shows whatever switches the application's automation sockets — remote
     * control and diagnostics — on and off while it runs. Core's button, so an agent and a person find it by one name
     * in every application; what it opens is the caller's. {@code vexelray-gui-automation-window}'s
     * {@code AutomationWindow} is the one that opens the switches.
     *
     * <p>Never in {@link #standard()}: an application without the automation module has no socket to switch, and must
     * not carry a button for one.
     */
    public static WindowInstrument automation(String tooltip, Consumer<WindowControls> open) {
        return new WindowInstrument("instrument-automation", tooltip, WindowInstrument::plugMark, open);
    }

    /**
     * The automation button with each socket's lamp beside the plug, read from {@code lamps} whenever {@code live} says
     * they changed, so whether a port is open, and whether anything is or was connected to it, shows in the title bar
     * at a glance. {@code vexelray-gui-automation-window}'s {@code AutomationWindow} is the one that reads the sockets.
     */
    public static WindowInstrument automation(String tooltip, Consumer<WindowControls> open,
                                              java.util.function.Supplier<Lamps> lamps, Live live) {
        return new WindowInstrument("instrument-automation", tooltip, (box, ink) -> {
            Picture plug = plugMark(box, ink);
            Lamps l = lamps.get();
            if (l == null) {
                return plug;
            }
            // Pips on the plug's centre line, the size of a key on the keyboard beside it: status, not a second icon.
            CaptionGrid g = CaptionGrid.of(box);
            java.util.List<Picture.Mark> marks = new java.util.ArrayList<>(plug.marks());
            marks.addAll(lampMarks(g, -8.5, l.control(), l.controlColor(), l.used()));
            marks.addAll(lampMarks(g, 8.5, l.diagnostics(), l.diagnosticsColor(), l.used()));
            return Picture.of(marks.toArray(Picture.Mark[]::new));
        }, open, live);
    }

    /** One lamp centred in {@code box}, for a panel that shows the same lamp beside a row. */
    public static Picture lamp(Rect box, Lamp lamp, Color color, Color used) {
        double r = Math.min(box.w(), box.h()) * 0.3;
        double cx = box.w() * 0.5;
        double cy = box.h() * 0.5;
        return Picture.of(lampMarks(lamp, color, used,
                (d, c) -> new Picture.Fill(cx - d * r, cy - d * r, 2 * d * r, 2 * d * r, d * r, d * r, c, null),
                c -> new Picture.Outline(cx - r, cy - r, r * 2, r * 2, r, r, 1.5, c, null))
                .toArray(Picture.Mark[]::new));
    }

    /** A title-bar lamp: radius two grid units, {@code gx} from the button's centre, at the bar's stroke. */
    private static java.util.List<Picture.Mark> lampMarks(CaptionGrid g, double gx, Lamp lamp, Color color,
                                                          Color used) {
        return lampMarks(lamp, color, used,
                (d, c) -> g.dot(gx, 0, 4 * d, c),
                c -> g.outline(gx - 2, -2, 4, 4, 2, c));
    }

    /**
     * A lamp: a ring while the port is open, filled while something is connected, a grey dot for a connection that
     * has gone. Shapes as well as colours, so the states differ for a reader who cannot tell red from grey.
     * {@code disc} draws a filled disc at a fraction of the lamp's radius; {@code ring} the open ring.
     */
    private static java.util.List<Picture.Mark> lampMarks(Lamp lamp, Color color, Color used,
                                                          java.util.function.BiFunction<Double, Color, Picture.Mark> disc,
                                                          java.util.function.Function<Color, Picture.Mark> ring) {
        if (lamp == null) {
            return java.util.List.of();
        }
        return switch (lamp) {
            case OFF -> java.util.List.of();
            case LISTENING -> java.util.List.of(ring.apply(color));
            case CONNECTED -> java.util.List.of(disc.apply(1.0, color));
            case WAS_CONNECTED -> java.util.List.of(ring.apply(color), disc.apply(0.45, used));
            case CLOSED_AFTER_USE -> java.util.List.of(disc.apply(0.45, used));
        };
    }

    // --- the marks ------------------------------------------------------------------------------------------
    //
    // All three are drawn on the caption grid (CaptionGrid): the same unit, the same single stroke and the same
    // pixel snapping as the window controls they sit beside, inside a 16 x 12 unit envelope centred in the button,
    // so the strip reads as one hand. Outlines, not fills: a filled key or a filled prong is three times the ink of
    // the hairline close beside it, and that difference in weight is what makes a strip look assembled.

    /**
     * A plug — two prongs, a body rounded at the bottom, a cord — for the thing that is plugged into from outside. Not
     * a wall socket: a ring with two slots, at caption size, is a pause button.
     */
    private static Picture plugMark(Rect box, Color ink) {
        CaptionGrid g = CaptionGrid.of(box);
        return Picture.of(
                g.vline(-2, -6, -2.5, ink),
                g.vline(2, -6, -2.5, ink),
                g.outline(-4.5, -3, 9, 6, 1, 3, ink),
                g.vline(0, 2.5, 6, ink));
    }

    /**
     * A keyboard: the case, two rows of four keys, and the space bar — few enough to read at caption size. The keys
     * are points one stroke square, so the gaps between them survive at 100%; drawn as blocks they ran together into
     * a bar.
     */
    private static Picture keyboardMark(Rect box, Color ink) {
        CaptionGrid g = CaptionGrid.of(box);
        java.util.List<Picture.Mark> marks = new java.util.ArrayList<>();
        marks.add(g.outline(-8, -5.5, 16, 11, 2, ink));
        for (double row : new double[] {-2.5, 0}) {
            for (double col : new double[] {-4.5, -1.5, 1.5, 4.5}) {
                marks.add(g.fill(col - 0.5, row - 0.5, 1, 1, 0, ink));
            }
        }
        marks.add(g.hline(-3.5, 3.5, 2.5, ink));
        return Picture.of(marks.toArray(Picture.Mark[]::new));
    }

    /**
     * A camera: body, viewfinder housing, lens, and the small flash that makes it a camera rather than a box with a
     * ring in it. Drawn rather than typed — the primary atlas carries no camera glyph, and a mark composed against
     * the button's own box is crisp at every density the window is opened at.
     */
    private static Picture cameraMark(Rect box, Color ink) {
        CaptionGrid g = CaptionGrid.of(box);
        return Picture.of(
                // The housing first, so its lower edge lies under the body's top edge: one stroke where they meet.
                g.outline(-3, -6, 6, 3, 1, 0, ink),
                g.outline(-7.5, -4, 15, 10, 2.5, ink),
                g.outline(-3, -2, 6, 6, 3, ink),
                g.dot(4.75, -1.75, 1.5, ink));
    }
}
