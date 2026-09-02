package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * One framework-owned tool in a window's title bar — a screenshot, later a macro recorder (docs/automation.md
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
        return new WindowInstrument("instrument-screenshot", "Screenshot this window",
                WindowInstrument::cameraMark, controls -> controls.capture(path.get()));
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
