package dev.vexelray.gui.harness;

import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.shadelets.Effects;
import dev.vexelray.text.FontSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.InputEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A framework widget whose look names an effect the application left out of its canvas shader still draws, without
 * that effect, says so once, and does not crash; and where the application did compose it, the effect is drawn.
 *
 * <p>The widgets are the icon set's {@link IconView}, whose {@code weight} and {@code outline} are the effects
 * {@code Effects.WEIGHT} and {@code Effects.OUTLINE}, and {@link IconButton}, which thickens its icon under the
 * pointer. Each is captured headless, as the application would draw it, once with the core set alone (the default
 * shader) and once with a shader composed of the core set and {@code Effects.ALL}.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class UnknownEffectsTest {

    private static final int W = 320;
    private static final int H = 160;
    /** The clear colour, which nothing in the theme is. */
    private static final float BG = 0f;
    private static final FontSet FONTS = FontSet.standard().withFallback("sans", "mono");
    private static final List<Icon> ICONS = List.of(Icon.SAVE, Icon.CLOSE, Icon.EDIT);
    private static final float WEIGHT = 0.04f;
    private static final float OUTLINE = 0.05f;

    /** The core set and every effect, as an application that opts in composes its shader. */
    public static final class AllEffects extends CanvasShader.Composed {
        public AllEffects() {
            super(CoreShadelets.composition().with(Effects.ALL));
        }
    }

    @TempDir
    Path dir;
    private int shots;

    @BeforeEach
    void silence() {
        Diagnostics.reset();
    }

    // --- IconView ---

    @Test
    void withTheCoreSetAloneAnIconsEffectsAreDroppedAndSaidOnce() throws Exception {
        BufferedImage plain = capture(icons(v -> { }), new CanvasShader.Fragment());
        assertTrue(drawn(plain) > 500, "the plain icons are drawn: " + drawn(plain) + " px");

        Gui styled = icons(v -> v.weight(WEIGHT).outline(Role.ACCENT, OUTLINE));
        for (int frame = 0; frame < 3; frame++) {
            BufferedImage shot = capture(styled, new CanvasShader.Fragment());
            assertEquals(0, differing(plain, shot),
                    "frame " + frame + ": an icon whose weight and outline the shader lacks draws as the plain icon");
        }

        List<String> said = Diagnostics.recorded();
        System.out.println("UnknownEffectsTest core-only IconView: Diagnostics.recorded() = " + said);
        assertEquals(1, said.stream().filter(m -> m.contains("'weight'")).count(), "weight said once: " + said);
        assertEquals(1, said.stream().filter(m -> m.contains("'outline'")).count(), "outline said once: " + said);
        assertEquals(2, said.size(), "nothing else said, and nothing per icon or per frame: " + said);
    }

    @Test
    void composedWithTheEffectsAnIconIsWeightedAndOutlined() throws Exception {
        BufferedImage plain = capture(icons(v -> { }), new AllEffects());
        BufferedImage weighted = capture(icons(v -> v.weight(WEIGHT)), new AllEffects());
        BufferedImage outlined = capture(icons(v -> v.outline(Role.ACCENT, OUTLINE)), new AllEffects());

        int plainPx = drawn(plain);
        int weightedPx = drawn(weighted);
        System.out.println("UnknownEffectsTest Effects.ALL IconView: plain " + plainPx + " px, weighted "
                + weightedPx + " px");
        assertTrue(weightedPx > plainPx * 1.15,
                "a weighted icon covers measurably more: " + weightedPx + " px against " + plainPx);

        Color accent = new Gui().theme().color(Role.ACCENT);
        int accentPlain = near(plain, accent);
        int accentOutlined = near(outlined, accent);
        int atEdge = nearEdge(outlined, accent, plain, (int) Math.ceil(64 * OUTLINE / 2) + 2);
        System.out.println("UnknownEffectsTest Effects.ALL IconView: accent px plain " + accentPlain
                + ", outlined " + accentOutlined + ", of them at the plain icon's edge " + atEdge);
        assertEquals(0, accentPlain, "the plain icon has no accent in it");
        assertTrue(accentOutlined > 200, "the outline is drawn in the accent: " + accentOutlined + " px");
        assertTrue(atEdge >= accentOutlined * 0.95, "the outline is at the icon's edge: " + atEdge + " of "
                + accentOutlined);
        assertNothingDropped();
    }

    // --- IconButton ---

    @Test
    void withTheCoreSetAloneAHoveredButtonsIconIsTheUnweightedOne() throws Exception {
        BufferedImage hovered = capture(hoveredButton(), new CanvasShader.Fragment(), true);
        BufferedImage twin = capture(twin(), new CanvasShader.Fragment());
        BufferedImage rest = capture(hoveredButton(), new CanvasShader.Fragment(), false);

        assertTrue(differing(rest, hovered) > 0, "hover changes the button: the fill at least");
        assertEquals(0, differing(twin, hovered),
                "the hovered button is its fill and the plain icon, the weight left out");
        List<String> said = Diagnostics.recorded();
        System.out.println("UnknownEffectsTest core-only IconButton: Diagnostics.recorded() = " + said);
        assertEquals(1, said.size(), "one warning: " + said);
        assertTrue(said.get(0).contains("'weight'"), "and it is the weight: " + said);
    }

    @Test
    void composedWithTheEffectsAHoveredButtonsIconIsThicker() throws Exception {
        BufferedImage hovered = capture(hoveredButton(), new AllEffects(), true);
        BufferedImage twin = capture(twin(), new AllEffects());
        Color fill = new Gui().theme().color(Role.SELECTION);
        int hoveredPx = drawn(hovered, fill);
        int twinPx = drawn(twin, fill);
        System.out.println("UnknownEffectsTest Effects.ALL IconButton: icon px hovered " + hoveredPx
                + ", unweighted twin " + twinPx);
        assertTrue(hoveredPx > twinPx * 1.1,
                "the hovered icon is thicker: " + hoveredPx + " px against " + twinPx);
        assertNothingDropped();
    }

    /**
     * No shadelet was said to be missing. A test's composed shader has no pre-compiled SPIR-V in any jar on this
     * classpath, so it is lowered at startup and that is said too; that one is not about the look, and is let be.
     */
    private static void assertNothingDropped() {
        List<String> dropped = Diagnostics.recorded().stream()
                .filter(m -> m.contains("was not composed with it"))
                .toList();
        assertEquals(List.of(), dropped, "nothing is dropped from a shader that has it");
    }

    // --- scenes ---

    /** The test icons side by side, 64 px each, every one dressed by {@code dress}. */
    private static Gui icons(Consumer<IconView> dress) {
        Gui gui = new Gui();
        Node row = gui.row().gap(Length.dp(24)).padding(Length.dp(24));
        for (Icon icon : ICONS) {
            IconView view = IconView.of(gui, icon).size(Length.dp(64));
            dress.accept(view);
            row.append(view.node());
        }
        gui.root().append(row);
        return gui;
    }

    /** An icon button and the bus that moves the pointer onto it. */
    private record Hovered(Gui gui, Atchung bus, Node node) {
    }

    private static Hovered hoveredButton() {
        Atchung bus = Atchung.create();
        Gui gui = new Gui(bus, Runnable::run);
        IconButton button = new IconButton(gui, Icon.SAVE, "Save").size(Length.dp(96), Length.dp(64));
        gui.root().append(gui.row().padding(Length.dp(24)).children(button.node()));
        return new Hovered(gui, bus, button.node());
    }

    /**
     * What the hovered button is, built by hand without a weight: its box in the hover fill, and the icon in the
     * ink a hovered button draws it in. Laid out as {@code IconButton} lays itself out.
     */
    private static Gui twin() {
        Gui gui = new Gui();
        IconView glyph = IconView.of(gui, Icon.SAVE).size(Length.dp(64)).tint(Role.INK);
        Node label = gui.text("Save").visible(false);
        Node box = gui.row().size(Length.dp(96), Length.dp(96))
                .corner(Length.rem(0.35f))
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .scroll(false, false)
                .background(gui.theme().color(Role.SELECTION))
                .children(glyph.node(), label);
        gui.root().append(gui.row().padding(Length.dp(24)).children(box));
        return gui;
    }

    // --- capture ---

    /** {@code gui} as the application would draw it with {@code shader}. */
    private BufferedImage capture(Gui gui, CanvasShader.Composed shader) throws Exception {
        Path out = dir.resolve("shot-" + (shots++) + ".png");
        GuiApp.capture(gui, FONTS, shader, W, H, BG, BG, BG, out.toString());
        return ImageIO.read(out.toFile());
    }

    /** The button, the pointer first moved onto it if {@code hover}, else left at rest. */
    private BufferedImage capture(Hovered h, CanvasShader.Composed shader, boolean hover) throws Exception {
        if (hover) {
            RetainedNode root = h.gui.frame(W, H, new TextFaces(FONTS));
            RetainedNode n = find(root, h.node.id());
            h.bus.publish(InputTopics.INPUT,
                    new InputEvent.PointerMoved((int) (n.x + n.w / 2), (int) (n.y + n.h / 2), 0, 0, 0));
        }
        return capture(h.gui, shader);
    }

    private static RetainedNode find(RetainedNode n, long id) {
        if (n == null || n.id == id) {
            return n;
        }
        for (RetainedNode child : n.children) {
            RetainedNode hit = find(child, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    // --- measuring ---

    /** Pixels that differ at all. */
    private static int differing(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Pixels clearly not the clear colour. */
    private static int drawn(BufferedImage img) {
        return drawn(img, Color.rgba(BG, BG, BG, 1f));
    }

    /** Pixels clearly not {@code ground}: what is drawn over it. */
    private static int drawn(BufferedImage img, Color ground) {
        int n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (distance(img.getRGB(x, y), ground) > 96) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Pixels within a little of {@code c}. */
    private static int near(BufferedImage img, Color c) {
        int n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (distance(img.getRGB(x, y), c) < 40) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Pixels of {@code img} near {@code c} within {@code r} of where {@code plain}'s drawing turns into clear. */
    private static int nearEdge(BufferedImage img, Color c, BufferedImage plain, int r) {
        boolean[][] on = new boolean[H][W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                on[y][x] = distance(plain.getRGB(x, y), Color.rgba(BG, BG, BG, 1f)) > 96;
            }
        }
        int n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (distance(img.getRGB(x, y), c) < 40 && edgeWithin(on, x, y, r)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean edgeWithin(boolean[][] on, int cx, int cy, int r) {
        for (int y = Math.max(1, cy - r); y <= Math.min(H - 2, cy + r); y++) {
            for (int x = Math.max(1, cx - r); x <= Math.min(W - 2, cx + r); x++) {
                if (on[y][x] && !(on[y - 1][x] && on[y + 1][x] && on[y][x - 1] && on[y][x + 1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The largest channel difference, 0 to 255. */
    private static int distance(int argb, Color c) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return Math.max(Math.abs(r - Math.round(c.r() * 255)),
                Math.max(Math.abs(g - Math.round(c.g() * 255)), Math.abs(b - Math.round(c.b() * 255))));
    }
}
