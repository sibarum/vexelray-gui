package dev.vexelray.gui.harness;

import dev.vexelray.canvas.Color;
import dev.vexelray.fields.art.Raster;
import dev.vexelray.fields.art.Svg;
import dev.vexelray.fields.sdg.Sdg;
import dev.vexelray.fields.sdg.SdgRender;
import dev.vexelray.fields.sdg.SvgToSdg;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageSdg;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDG in a window: a node holding a drawing ({@link Node#sdg}) is drawn by a real {@code GuiApp}, its pages uploaded by
 * the renderer, and the capture matches {@code SdgRender} laid over the page; and a paint naming a role follows a
 * theme swap. The window is a background run. Needs a Vulkan device; an integration test.
 */
class SdgCaptureTest {

    private static final int W = 400, H = 300;
    /** Per channel, of 255: the GPU weighs texels in fixed point, the reference in doubles (vexelray's SdgRenderTest). */
    private static final int TOLERANCE = 3, WORST = 8;

    /** vexelray-fields' badge: MSDF fills, an SDF stroke, a gradient, a white star. */
    static final String BADGE = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <defs>
                <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="#2b5fd9"/><stop offset="1" stop-color="#9fd3ff"/>
                </linearGradient>
              </defs>
              <rect x="4" y="4" width="92" height="92" rx="14" fill="url(#sky)"/>
              <circle cx="68" cy="34" r="16" fill="#ffcc33"/>
              <path d="M4 74 L30 48 L50 66 L66 54 L96 80 L96 82 Q96 96 82 96 L18 96 Q4 96 4 82 Z" fill="#2f8f4e"/>
              <path d="M10 88 C30 70 60 98 90 80" fill="none" stroke="#173d22" stroke-width="3"
                    stroke-linecap="round"/>
              <polygon points="22,30 26,40 37,40 28,46 31,57 22,50 13,57 16,46 7,40 18,40" fill="#ffffff"/>
            </svg>
            """;

    static Sdg badge() {
        Svg svg = Svg.parse(BADGE);
        return SvgToSdg.convert(svg, SvgToSdg.Options.across(svg, 64));
    }

    @Test
    void aNodesDrawingIsItsReferenceOverThePage() throws Exception {
        Sdg sdg = badge();
        Rect[] where = new Rect[1];
        BufferedImage shot = capture("badge", ImageSdg.of(sdg), null, Theme.DARK, null, where);
        int[] counts = compare(shot, sdg, where[0]);
        int worst = counts[0], different = counts[1], pixels = counts[2];
        System.out.printf("[sdg capture] box %s: %d of %d pixels differ by more than %d; worst %d%n",
                where[0], different, pixels, TOLERANCE, worst);
        assertTrue(different <= pixels / 100, different + " pixels differ from the reference (worst " + worst + ")");
        assertTrue(worst <= WORST, "a pixel differs from the reference by " + worst);
    }

    /**
     * The capture inside {@code r} against {@code sdg} fitted into it, as the canvas fits a drawing and as
     * {@code SdgRender.render} does (k pixels a unit, centred), over the dark theme's page: {@code {worst, pixels past
     * TOLERANCE, pixels}}. Writes a diff image beside the capture.
     */
    private static int[] compare(BufferedImage shot, Sdg sdg, Rect r) throws java.io.IOException {
        double k = Math.min(r.w() / sdg.width(), r.h() / sdg.height());
        double ox = r.x() + (r.w() - sdg.width() * k) / 2, oy = r.y() + (r.h() - sdg.height() * k) / 2;
        Raster ref = SdgRender.render(sdg, shot.getWidth(), shot.getHeight(), -ox / k, -oy / k, 1 / k);
        Color page = Theme.DARK.color(Role.PAGE);

        int x0 = (int) Math.floor(r.x()), y0 = (int) Math.floor(r.y());
        int x1 = (int) Math.ceil(r.x() + r.w()), y1 = (int) Math.ceil(r.y() + r.h());
        int worst = 0, different = 0, pixels = 0;
        BufferedImage diff = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_RGB);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int o = (y * shot.getWidth() + x) * 4;
                float a = (ref.rgba()[o + 3] & 0xFF) / 255f;
                int got = shot.getRGB(x, y);
                int d = 0;
                float[] bg = {page.r(), page.g(), page.b()};
                for (int c = 0; c < 3; c++) {
                    int want = Math.round((ref.rgba()[o + c] & 0xFF) * a + bg[c] * 255f * (1 - a));
                    int have = got >> (16 - 8 * c) & 0xFF;
                    d = Math.max(d, Math.abs(want - have));
                }
                worst = Math.max(worst, d);
                different += d > TOLERANCE ? 1 : 0;
                pixels++;
                diff.setRGB(x - x0, y - y0, d > TOLERANCE ? 0xFF0000 : (got & 0xFFFFFF) >> 2 & 0x3F3F3F);
            }
        }
        ImageIO.write(diff, "png", Path.of("target", "sdg", "badge-diff.png").toFile());
        return new int[]{worst, different, pixels};
    }

    /**
     * A drawing in a picture ({@code Sketch.drawing}) draws as a node's drawing does: the same badge, a mark in a
     * picture over the same box, against the reference; and its SVG export holds it, drawn, as an image.
     */
    @Test
    void aPicturesDrawingIsItsReferenceAndExportsAsAnImage() throws Exception {
        Sdg sdg = badge();
        ImageSdg drawing = ImageSdg.of(sdg);
        dev.vexelray.gui.draw.Picture picture = new dev.vexelray.gui.draw.Sketch().tag("badge")
                .drawing(0, 0, 256, 192, drawing.source(), drawing.pages()).picture();
        Rect[] where = new Rect[1];
        BufferedImage shot = capture("picture", null, picture, Theme.DARK, null, where);
        int[] counts = compare(shot, sdg, where[0]);
        System.out.printf("[sdg capture] picture %s: %d of %d pixels differ by more than %d; worst %d%n",
                where[0], counts[1], counts[2], TOLERANCE, counts[0]);
        assertTrue(counts[1] <= counts[2] / 100, counts[1] + " pixels differ (worst " + counts[0] + ")");
        assertTrue(counts[0] <= WORST);

        String svg = dev.vexelray.gui.draw.Svg.document(picture, 256, 192);
        assertTrue(svg.contains("<image ") && svg.contains("href=\"data:image/png;base64,")
                && svg.contains("class=\"badge\""), "the export holds the drawing as an image, its tag its class");
    }

    /** The star names the foreground: drawn in the dark theme's ink, and swapped to the light theme's with it. */
    @Test
    void aRoleFollowsTheTheme() throws Exception {
        Sdg sdg = badge();
        List<Sdg.Layer> layers = new ArrayList<>();
        for (Sdg.Layer l : sdg.layers()) {
            layers.add(l.paint() instanceof Sdg.Paint.Solid s && s.rgba() == 0xFFFFFFFF
                    ? new Sdg.Layer(l.name(), l.stencil(), s.as("foreground"), l.opacity()) : l);
        }
        ImageSdg themed = ImageSdg.of(new Sdg(sdg.width(), sdg.height(), sdg.pages(), layers));
        Rect[] where = new Rect[1];
        BufferedImage dark = capture("role-dark", themed, null, Theme.DARK, null, where);
        BufferedImage light = capture("role-light", themed, null, Theme.DARK, Theme.LIGHT, new Rect[1]);

        Rect r = where[0];
        double k = Math.min(r.w() / sdg.width(), r.h() / sdg.height());
        double ox = r.x() + (r.w() - sdg.width() * k) / 2, oy = r.y() + (r.h() - sdg.height() * k) / 2;
        int sx = (int) (ox + 22 * k), sy = (int) (oy + 46 * k);   // inside the star
        assertColour(Theme.DARK.color(Role.INK), dark.getRGB(sx, sy), "dark: the star in the dark theme's ink");
        assertColour(Theme.LIGHT.color(Role.INK), light.getRGB(sx, sy), "swapped: the light theme's ink");
    }

    private static void assertColour(Color want, int got, String what) {
        float[] w = {want.r(), want.g(), want.b()};
        for (int c = 0; c < 3; c++) {
            assertEquals(Math.round(w[c] * 255f), got >> (16 - 8 * c) & 0xFF, 2, what);
        }
    }

    /** A page with {@code drawing} in a box on it, built in {@code born}, swapped to {@code then} if not null. */
    private static BufferedImage capture(String name, ImageSdg drawing,
                                         dev.vexelray.gui.draw.Picture picture, Theme born, Theme then, Rect[] where)
            throws Exception {
        Path dir = Path.of("target", "sdg");
        Files.createDirectories(dir);
        File out = dir.resolve(name + ".png").toFile();
        Files.deleteIfExists(out.toPath());
        Gui gui = new Gui().theme(born);
        Node box = gui.box().width(Length.dp(256)).height(Length.dp(192)).sdg(drawing).picture(picture);
        Node page = gui.box().direction(Direction.COLUMN).width(Length.FILL).height(Length.FILL)
                .background(Role.PAGE);
        // Off the window's edge, which the window draws a line of its own round.
        Node row = gui.box().direction(Direction.ROW);
        row.append(gui.box().width(Length.dp(24)).height(Length.dp(1)));
        row.append(box);
        page.append(gui.box().width(Length.dp(1)).height(Length.dp(24)));
        page.append(row);
        gui.root().append(page);
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("sdg", W, H))) {
            harness.settle();
            if (then != null) {
                gui.theme(then);
                harness.window().postWake();
                assertTrue(harness.await(() -> gui.drawnTheme() == then, 5_000), "the swap was never drawn");
                harness.settle(300L, 5_000L);
            }
            harness.app().controls().capture(out.getAbsolutePath());
            assertTrue(harness.await(() -> out.isFile() && out.length() > 0L, 5_000), "the capture never reached disk");
            where[0] = box.layout().rect();
        }
        return ImageIO.read(out);
    }
}
