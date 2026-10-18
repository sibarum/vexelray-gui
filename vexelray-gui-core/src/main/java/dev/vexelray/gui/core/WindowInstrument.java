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
 */
public record WindowInstrument(String role, String tooltip,
                               java.util.function.BiFunction<Rect, Color, Picture> mark,
                               Consumer<WindowControls> action) {

    /** Where a screenshot goes when nobody said: a timestamped PNG in the working directory. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public WindowInstrument {
        role = role == null ? "" : role;
        tooltip = tooltip == null ? "" : tooltip;
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
     * <p>Never in {@link #standard()}: a shipped binary has no socket to switch, and must not carry a button for one.
     */
    public static WindowInstrument automation(String tooltip, Consumer<WindowControls> open) {
        return new WindowInstrument("instrument-automation", tooltip, WindowInstrument::plugMark, open);
    }

    /**
     * A plug — two prongs, a body, a cord — for the thing that is plugged into from outside. Not a wall socket: a ring
     * with two slots, at caption size, is a pause button. Drawn, like the camera and the keyboard beside it, and at
     * their weight.
     */
    private static Picture plugMark(Rect box, Color ink) {
        double s = Math.min(box.w(), box.h()) * 0.42;
        double cx = box.w() * 0.5;
        double top = box.h() * 0.5 - s * 0.75;
        double prongW = s * 0.14;
        double prongH = s * 0.4;
        double bodyW = s * 0.95;
        double bodyH = s * 0.7;
        double bodyY = top + prongH;
        double cordW = s * 0.16;
        return Picture.of(
                new Picture.Fill(cx - s * 0.24 - prongW * 0.5, top, prongW, prongH + 1, prongW * 0.3, 0, ink, null),
                new Picture.Fill(cx + s * 0.24 - prongW * 0.5, top, prongW, prongH + 1, prongW * 0.3, 0, ink, null),
                new Picture.Outline(cx - bodyW * 0.5, bodyY, bodyW, bodyH, s * 0.12, s * 0.3, 1.5, ink, null),
                new Picture.Fill(cx - cordW * 0.5, bodyY + bodyH - 1, cordW, s * 0.4 + 1, 0, 0, ink, null));
    }

    /**
     * A keyboard, drawn for the reason the camera is: the mark is composed against the button's own box, so it
     * is crisp at every zoom, and it carries the same visual weight as the camera beside it.
     */
    private static Picture keyboardMark(Rect box, Color ink) {
        double s = Math.min(box.w(), box.h()) * 0.42;
        double w = s * 1.6;
        double h = s;
        double x = box.w() * 0.5 - w * 0.5;
        double y = box.h() * 0.5 - h * 0.5;
        double key = s * 0.16;
        double pitch = (w - s * 0.3) / 4;
        java.util.List<Picture.Mark> marks = new java.util.ArrayList<>();
        marks.add(new Picture.Outline(x, y, w, h, s * 0.14, s * 0.14, 1.5, ink, null));
        // Two rows of keys and a space bar: few enough to read at caption size, enough to read as a keyboard.
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                double kx = x + s * 0.15 + col * pitch + (pitch - key) * 0.5;
                double ky = y + h * (0.2 + row * 0.25);
                marks.add(new Picture.Fill(kx, ky, key, key, key * 0.25, 0, ink, null));
            }
        }
        marks.add(new Picture.Fill(x + w * 0.28, y + h * 0.7, w * 0.44, key, key * 0.25, 0, ink, null));
        return Picture.of(marks.toArray(Picture.Mark[]::new));
    }

    /**
     * A camera, drawn rather than typed. The primary atlas carries no camera glyph — and a mark composed of
     * rectangles is resolution-independent in a way a glyph at this size is not, which matters for a control
     * that is 46dp wide at every zoom the window is opened at.
     */
    private static Picture cameraMark(Rect box, Color ink) {
        // Authored against the button's own box, centred, at a size that leaves the caption buttons' visual
        // weight: a 16-unit square inside a 46-wide button reads as the same size as the close glyph beside it.
        double s = Math.min(box.w(), box.h()) * 0.42;
        double cx = box.w() * 0.5;
        double cy = box.h() * 0.5;
        double bodyW = s * 1.35;
        double bodyH = s;
        double x = cx - bodyW * 0.5;
        double y = cy - bodyH * 0.5;
        double lens = s * 0.28;
        return Picture.of(
                // The body, and the viewfinder bump on top of it.
                new Picture.Outline(x, y, bodyW, bodyH, s * 0.18, s * 0.18, 1.5, ink, null),
                new Picture.Fill(cx - s * 0.22, y - s * 0.2, s * 0.44, s * 0.22, s * 0.08, 0, ink, null),
                // The lens: a ring, so the mark reads as a camera rather than as a filled box with a bump.
                new Picture.Outline(cx - lens, cy - lens, lens * 2, lens * 2, lens, lens, 1.5, ink, null));
    }
}
