package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Lattice;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A theme swapped at runtime draws what the same tree built in that theme draws: the gate of
 * {@code docs/plans/runtime-themes.md}.
 *
 * <p>One tree, two lives. Built in {@link Theme#DARK}, swapped to {@link Theme#LIGHT} and settled; built fresh in
 * {@code LIGHT}. The two captures are diffed. Every pixel that differs is a colour some widget resolved when it was
 * built and never again, and the plan is done when there are none.
 *
 * <p><b>A ratchet, not a pass.</b> Today it is not empty, by design of the plan's phases: pictures, spans and cues still
 * resolve early (§5 of the plan). A disabled test would stop measuring, and the number is the point, so the test
 * asserts the count it found when it was written as a ceiling. A change that moves a site onto paints lowers the count
 * and lowers {@link #CEILING} with it; a change that adds a stale pixel fails the build. When R3 lands the ceiling is
 * zero, and this is the gate R6 names.
 *
 * <p>The tree is one of each widget in §5 that can be built from {@code vexelray-gui-widget} without input: the tabs'
 * indicator, a slider's tick sketch, a text span's wash, a button, and a panel of node paints as the control — the
 * panel is R1's, and must contribute nothing. The diff is attributed to each by its laid-out rect, and printed.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class RethemeCaptureTest {

    private static final int W = 640;
    private static final int H = 360;

    /** Per channel, for the 8-bit round trip; a stale colour is off by far more. */
    private static final int TOLERANCE = 2;

    /**
     * The stale pixels measured when this test was written (2026-10-19): tabs indicator 238, slider ticks 170, text
     * spans 1131, the same on every run. May only go down; see the class comment.
     */
    private static final long CEILING = 1539L;

    @Test
    void aSwappedTreeDrawsWhatATreeBornInThatThemeDraws() throws Exception {
        Path dir = Path.of("target", "retheme");
        Files.createDirectories(dir);

        Map<String, Rect> regions = new LinkedHashMap<>();
        BufferedImage swapped = capture(Theme.DARK, dir.resolve("swapped.png").toFile(), regions);
        BufferedImage born = capture(Theme.LIGHT, dir.resolve("born-light.png").toFile(), new LinkedHashMap<>());

        CaptureDiff.Result r = CaptureDiff.compare(born, swapped, TOLERANCE);
        ImageIO.write(CaptureDiff.diffImage(born, swapped, TOLERANCE), "png", dir.resolve("diff.png").toFile());

        StringBuilder report = new StringBuilder("retheme diff: ").append(r.describe());
        long attributed = 0;
        for (Map.Entry<String, Rect> e : regions.entrySet()) {
            long n = differing(born, swapped, e.getValue());
            attributed += n;
            report.append("\n  ").append(e.getKey()).append(": ").append(n);
        }
        report.append("\n  elsewhere: ").append(r.differing() - attributed);
        System.out.println(report);

        assertEquals(0L, differing(born, swapped, regions.get("panel (node paints, R1)")),
                "a node paint is resolved per frame since R1, so the control panel must swap clean\n" + report);
        assertTrue(r.differing() <= CEILING,
                "a runtime swap left more stale pixels than when this was measured - something resolves a theme "
                        + "colour early that did not before\n" + report);
    }

    /**
     * The tree, built in {@code born}, swapped to {@link Theme#LIGHT} if it was not born there, settled and captured.
     * Fills {@code regions} with each widget's rect in capture pixels.
     */
    private static BufferedImage capture(Theme born, File out, Map<String, Rect> regions) throws Exception {
        Gui gui = new Gui().theme(born);
        Map<String, Node> parts = build(gui);
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("retheme", W, H))) {
            harness.settle();
            if (born != Theme.LIGHT) {
                gui.theme(Theme.LIGHT);
                harness.window().postWake();
                assertTrue(harness.await(() -> gui.drawnTheme() == Theme.LIGHT, 5_000), "the swap was never drawn");
                // Long enough for the cross-fade a swap starts to finish, and quiet after it.
                harness.settle(300L, 5_000L);
            }
            harness.app().controls().capture(out.getAbsolutePath());
            assertTrue(harness.await(() -> out.isFile() && out.length() > 0L, 5_000), "the capture never reached disk");
            parts.forEach((name, node) -> regions.put(name, node.layout().rect()));
        }
        return ImageIO.read(out);
    }

    /** One of each widget §5 names that can be built without input, in a column. Keyed by what the region shows. */
    private static Map<String, Node> build(Gui gui) {
        Theme theme = gui.theme();
        Map<String, Node> parts = new LinkedHashMap<>();

        Node panel = gui.box().width(Length.dp(200)).height(Length.dp(32))
                .background(Role.PANEL).border(Length.dp(1), Role.LINE);
        parts.put("panel (node paints, R1)", panel);

        // An indicator that arrives at once: the motion is not under test, the colour it was drawn in is.
        dev.vexelray.gui.widget.Ramp instant = (progress, done) -> {
            progress.accept(1d);
            done.run();
        };
        Tabs tabs = new Tabs(gui).indicator(instant)
                .add("One", gui.text("first page")).add("Two", gui.text("second page"));
        tabs.node().width(Length.dp(300)).height(Length.dp(80));
        parts.put("tabs (indicator picture)", tabs.node());

        Slider slider = new Slider(gui, 0.4f).lattice(Lattice.intervals(8));
        slider.node().width(Length.dp(300));
        parts.put("slider (tick sketch)", slider.node());

        // As the demo's text chapter does it: a span's colour is a Color, so it is the theme's at build time.
        TextField text = new TextField(gui, "a wash under this word and an accent on that one").readOnly(true);
        text.setSpans(List.of(
                Span.background(2, 6, theme.color(Role.LINE)),
                Span.foreground(40, 44, theme.color(Role.ACCENT))));
        text.node().width(Length.dp(400));
        parts.put("text field (spans)", text.node());

        Button button = new Button(gui, "Apply");
        parts.put("button", button.node());

        Node column = gui.box().direction(Direction.COLUMN).width(Length.FILL).height(Length.FILL)
                .background(Role.PAGE);
        parts.values().forEach(column::append);
        gui.root().append(column);
        return parts;
    }

    /** Differing pixels inside {@code r}, clamped to the image. */
    private static long differing(BufferedImage a, BufferedImage b, Rect r) {
        int x0 = Math.max(0, (int) Math.floor(r.x()));
        int y0 = Math.max(0, (int) Math.floor(r.y()));
        int x1 = Math.min(Math.min(a.getWidth(), b.getWidth()), (int) Math.ceil(r.x() + r.w()));
        int y1 = Math.min(Math.min(a.getHeight(), b.getHeight()), (int) Math.ceil(r.y() + r.h()));
        long n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                for (int s = 0; s < 32; s += 8) {
                    if (Math.abs(((p >>> s) & 0xFF) - ((q >>> s) & 0xFF)) > TOLERANCE) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }
}
