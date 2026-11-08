package dev.vexelray.gui.harness;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.LayeredIcon;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.fileicons.FileFamily;
import dev.vexelray.gui.fileicons.FileIconView;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
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
 * Icons drawn as SDG draw what they drew as fields: each icon twice in one window, once as the field it was
 * ({@code Node.field}, a layered icon as its stacked fields), once by {@link IconView} as it is now ({@code Node.sdg}),
 * small and large, plain and with a glow and a shadow; the two captured regions compared. The gate for slice 3 of
 * vexelray-framework's {@code docs/plans/sdg-native.md}. A background run; needs a Vulkan device.
 */
class IconSdgCaptureTest {

    private static final int W = 1000, H = 720;
    private static final int TOLERANCE = 2;
    private static final float GLOW = 0.15f, SHADOW = 0.07f;

    /** One icon drawn both ways, at {@code px}, with or without effects: the old node and the new one. */
    private record Pair(String what, Node field, Node sdg) {
    }

    @Test
    void iconsDrawAsTheirFieldsDid() throws Exception {
        Gui gui = new Gui().theme(Theme.DARK);
        Color ink = Theme.DARK.color(Role.INK);
        List<Pair> pairs = new ArrayList<>();
        Node page = gui.box().direction(Direction.COLUMN).width(Length.FILL).height(Length.FILL).background(Role.PAGE);
        Icon[] icons = Icon.values();
        for (float px : new float[]{16f, 48f}) {
            for (boolean effects : new boolean[]{false, true}) {
                // Room round each icon for the whole reach of its effects, so no region compared takes in a neighbour.
                float pad = pad(px);
                page.append(gui.box().width(Length.dp(1)).height(Length.dp(pad)));
                Node row = gui.box().direction(Direction.ROW).height(Length.dp(px + pad));
                row.append(gap(gui, pad + 8));
                for (int k = 0; k < 3; k++) {
                    Icon icon = icons[(k * 23 + (int) px) % icons.length];
                    ImageField f = icon.field(px, ink);
                    Node old = gui.box().size(Length.dp(px), Length.dp(px))
                            .field(effects ? f.effects(px * GLOW, px * SHADOW) : f);
                    IconView view = IconView.of(gui, icon).size(Length.dp(px)).tint(ink);
                    if (effects) {
                        view.glow(GLOW).shadow(SHADOW);
                    }
                    row.append(old).append(gap(gui, 2 * pad)).append(view.node()).append(gap(gui, 2 * pad));
                    pairs.add(new Pair(icon.file() + " " + (int) px + (effects ? " effects" : ""), old, view.node()));
                }
                page.append(row);
            }
        }
        // A layered icon: its fields stacked in nodes over one another, as IconView drew it, and the drawing now.
        float layeredPad = pad(48f);
        page.append(gui.box().width(Length.dp(1)).height(Length.dp(layeredPad)));
        Node row = gui.box().direction(Direction.ROW).height(Length.dp(48f + layeredPad));
        row.append(gap(gui, layeredPad + 8));
        for (FileFamily family : new FileFamily[]{FileFamily.FOLDERS, FileFamily.SOURCE_CODE, FileFamily.PDF}) {
            float px = 48f;
            List<ImageField> layers = family.layers(px, LayeredIcon.dark(Theme.DARK));
            Node old = gui.box().size(Length.dp(px), Length.dp(px)).field(layers.getFirst());
            for (ImageField layer : layers.subList(1, layers.size())) {
                old.append(gui.box().size(Length.dp(px), Length.dp(px)).floatAt(Length.ZERO, Length.ZERO)
                        .hitInert(true).field(layer));
            }
            Node now = FileIconView.node(gui, family, Length.dp(px));
            row.append(old).append(gap(gui, 2 * layeredPad)).append(now).append(gap(gui, 2 * layeredPad));
            pairs.add(new Pair(family.name() + " layered", old, now));
        }
        page.append(row);
        gui.root().append(page);

        Path dir = Path.of("target", "icon-sdg");
        Files.createDirectories(dir);
        File out = dir.resolve("icons.png").toFile();
        Files.deleteIfExists(out.toPath());
        BufferedImage shot;
        List<Rect[]> rects = new ArrayList<>();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("icon-sdg", W, H))) {
            harness.settle();
            harness.app().controls().capture(out.getAbsolutePath());
            assertTrue(harness.await(() -> out.isFile() && out.length() > 0L, 5_000), "the capture never reached disk");
            for (Pair p : pairs) {
                rects.add(new Rect[]{p.field().layout().rect(), p.sdg().layout().rect()});
            }
        }
        shot = ImageIO.read(out);

        StringBuilder report = new StringBuilder();
        int failing = 0;
        for (int i = 0; i < pairs.size(); i++) {
            Rect a = rects.get(i)[0], b = rects.get(i)[1];
            assertEquals(a.w(), b.w(), 1e-3, pairs.get(i).what() + ": the same size both ways");
            // The box and the reach of its effects round it, which both ways draw past the box.
            int pad = (int) pad(a.w());
            int bg = shot.getRGB((int) a.x() - pad, (int) a.y() - pad);
            int dx = Math.round(b.x() - a.x()), dy = Math.round(b.y() - a.y());
            // The cell: the box and its margin, a quarter of it each side in both of the suite's grids. Inside it both
            // ways read the same texels. Past it, a field's quad runs on 2 px (its AA pad) and samples the atlas cell
            // beside it, a neighbour's shape; an SDG layer reads nothing past its rectangle. So past the cell is
            // reported, not held to.
            float m = a.w() * 0.25f;
            int worst = 0, different = 0, lit = 0, past = 0;
            for (int y = (int) a.y() - pad; y < (int) (a.y() + a.h()) + pad; y++) {
                for (int x = (int) a.x() - pad; x < (int) (a.x() + a.w()) + pad; x++) {
                    if (x < 0 || y < 0 || x + dx >= shot.getWidth() || y + dy >= shot.getHeight()) {
                        continue;
                    }
                    int p = shot.getRGB(x, y), q = shot.getRGB(x + dx, y + dy);
                    int d = 0;
                    for (int c = 0; c < 24; c += 8) {
                        d = Math.max(d, Math.abs((p >> c & 0xFF) - (q >> c & 0xFF)));
                    }
                    boolean inCell = x + 0.5f > a.x() - m && x + 0.5f < a.x() + a.w() + m
                            && y + 0.5f > a.y() - m && y + 0.5f < a.y() + a.h() + m;
                    if (!inCell) {
                        past += d > TOLERANCE ? 1 : 0;
                        continue;
                    }
                    worst = Math.max(worst, d);
                    different += d > TOLERANCE ? 1 : 0;
                    if (d > TOLERANCE && Boolean.getBoolean("icon.sdg.where")) {
                        System.out.printf("    %s at (%d, %d) from the box: field %06x sdg %06x%n", pairs.get(i).what(),
                                x - (int) a.x(), y - (int) a.y(), p & 0xFFFFFF, q & 0xFFFFFF);
                    }
                    lit += p != bg ? 1 : 0;
                }
            }
            report.append(String.format("%n  %-28s lit %5d, differ %3d, worst %3d; past the cell %d",
                    pairs.get(i).what(), lit, different, worst, past));
            if (different > 0 || lit == 0) {
                failing++;
            }
        }
        System.out.println("[icon sdg] field against sdg:" + report);
        assertEquals(0, failing, "icons drawn as SDG differ from their fields:" + report);
    }

    /** How far round an icon {@code px} across its effects can reach: the glow's five radii, the shadow's 1.6, a pixel more. */
    private static float pad(float px) {
        return (float) Math.ceil(px * (GLOW * 5 + SHADOW * 1.6f)) + 2f;
    }

    private static Node gap(Gui gui, float dp) {
        return gui.box().width(Length.dp(dp)).height(Length.dp(1));
    }
}
