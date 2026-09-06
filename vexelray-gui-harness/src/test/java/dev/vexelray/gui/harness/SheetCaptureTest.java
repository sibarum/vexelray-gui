package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application's own pixels, on the screen: {@code GuiApp.texture} uploads them and {@code ImageRegion} says
 * which part of them a node shows. Asserted against a real capture, because everything below the API — the
 * staging copy, the layout transition, the descriptor set, the sampler's coordinates — is invisible until a
 * texel reaches a pixel.
 *
 * <p>The sheet is four flat quadrants in four colours that exist nowhere in the theme. Two nodes sample two
 * different quadrants of the <b>same</b> texture, so a capture that shows both colours proves the two claims the
 * unit tests cannot reach: the bytes handed to {@code texture()} are the bytes sampled, and a region selects
 * within one upload rather than needing one texture per picture.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class SheetCaptureTest {

    private static final int W = 420;
    private static final int H = 220;
    private static final int SHEET = 64;   // 2x2 quadrants of 32px

    /** Quadrant colours, indexed the way {@link ImageRegion#cell} counts: row-major from the top-left. */
    private static final int[][] QUADRANT = {
            {255, 0, 0}, {0, 255, 0},
            {0, 0, 255}, {255, 255, 0},
    };

    /** A 64x64 RGBA8 sheet of four flat quadrants — the layout every sampler agrees on. */
    private static byte[] sheet() {
        byte[] rgba = new byte[SHEET * SHEET * 4];
        for (int y = 0; y < SHEET; y++) {
            for (int x = 0; x < SHEET; x++) {
                int[] c = QUADRANT[(y / (SHEET / 2)) * 2 + x / (SHEET / 2)];
                int at = (y * SHEET + x) * 4;
                rgba[at] = (byte) c[0];
                rgba[at + 1] = (byte) c[1];
                rgba[at + 2] = (byte) c[2];
                rgba[at + 3] = (byte) 255;
            }
        }
        return rgba;
    }

    /** Whether the pixel at the centre of {@code box} is {@code expected}, within sampling tolerance. */
    private static void assertCentreIs(BufferedImage img, Rect box, int[] expected, String what) {
        int x = Math.round(box.centreX());
        int y = Math.round(box.centreY());
        assertTrue(x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight(),
                what + ": the node landed outside the capture at " + x + "," + y);
        int rgb = img.getRGB(x, y);
        int[] got = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        for (int i = 0; i < 3; i++) {
            assertEquals(expected[i], got[i], 4,
                    what + ": channel " + i + " at " + x + "," + y
                            + " (got " + got[0] + "," + got[1] + "," + got[2] + ")");
        }
    }

    @Test
    void twoNodesShowTwoQuadrantsOfOneUploadedTexture(@TempDir Path dir) throws Exception {
        Gui gui = new Gui();
        Node topLeft = gui.box().size(Length.dp(80), Length.dp(80));
        Node bottomRight = gui.box().size(Length.dp(80), Length.dp(80));
        gui.root().children(topLeft, bottomRight);

        File out = dir.resolve("sheet.png").toFile();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("sheet", W, H))) {
            harness.settle();

            // One upload. Two regions.
            var texture = harness.app().texture(sheet(), SHEET, SHEET);
            topLeft.image(texture, ImageRegion.cell(0, 2, 2));
            bottomRight.image(texture, ImageRegion.cell(3, 2, 2));
            harness.settle();

            harness.app().controls().capture(out.getAbsolutePath());
            assertTrue(harness.await(() -> out.isFile() && out.length() > 0L, 10_000),
                    "the capture has to reach disk: " + out);

            BufferedImage img = ImageIO.read(out);
            assertCentreIs(img, topLeft.layout().rect(), QUADRANT[0], "cell 0 is the red quadrant");
            assertCentreIs(img, bottomRight.layout().rect(), QUADRANT[3], "cell 3 is the yellow quadrant");
        }
    }

    @Test
    void anImageWithNoRegionShowsTheWholeTexture(@TempDir Path dir) throws Exception {
        Gui gui = new Gui();
        Node whole = gui.box().size(Length.dp(80), Length.dp(80));
        gui.root().children(whole);

        File out = dir.resolve("whole.png").toFile();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("whole", W, H))) {
            harness.settle();
            whole.image(harness.app().texture(sheet(), SHEET, SHEET));
            harness.settle();

            harness.app().controls().capture(out.getAbsolutePath());
            assertTrue(harness.await(() -> out.isFile() && out.length() > 0L, 10_000), "capture: " + out);

            // All four quadrants across one box, so a quarter in from each corner is a different colour — and
            // the centre is none of them, which is what "the whole texture" has to look like.
            BufferedImage img = ImageIO.read(out);
            Rect box = whole.layout().rect();
            assertCentreIs(img, quarter(box, 0, 0), QUADRANT[0], "top-left quarter");
            assertCentreIs(img, quarter(box, 1, 0), QUADRANT[1], "top-right quarter");
            assertCentreIs(img, quarter(box, 0, 1), QUADRANT[2], "bottom-left quarter");
            assertCentreIs(img, quarter(box, 1, 1), QUADRANT[3], "bottom-right quarter");
        }
    }

    /** The {@code (col, row)} quarter of {@code box}, as a rect whose centre is that quarter's centre. */
    private static Rect quarter(Rect box, int col, int row) {
        float w = box.w() / 2f;
        float h = box.h() / 2f;
        return new Rect(box.x() + col * w, box.y() + row * h, w, h);
    }
}
