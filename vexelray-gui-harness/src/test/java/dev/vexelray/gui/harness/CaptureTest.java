package dev.vexelray.gui.harness;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
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
 * A screenshot has to be of the window, taken from the tree that is on it.
 *
 * <p>The failure this exists to catch is not "no file appeared" — that one announces itself. It is a capture
 * that writes a plausible PNG of the clear colour, because it rendered a tree that was never laid out, or
 * bound the wrong descriptor set, or drew at the wrong extent. Every one of those produces a file of the right
 * size full of the right background, and a troubleshooting instrument that lies that convincingly is worse than
 * one that fails. So this asserts the <em>content</em>: a colour that exists nowhere but in the tree.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class CaptureTest {

    /** Nothing in the theme is this colour, so finding it proves the tree was drawn rather than the clear. */
    private static final Color MARKER = Color.rgba(1f, 0f, 1f, 1f);
    private static final int W = 400;
    private static final int H = 300;

    @Test
    void aCaptureIsThisWindowsTreeAtThisWindowsSize(@TempDir Path dir) throws Exception {
        Gui gui = new Gui();
        Node marker = gui.box().size(Length.dp(200), Length.dp(120)).background(MARKER);
        gui.root().append(marker);

        File out = dir.resolve("shot.png").toFile();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("capture", W, H))) {
            harness.settle();

            // Asynchronous by contract: the call returns and the work happens on the main thread in a frame.
            harness.app().controls().capture(out.getAbsolutePath());
            assertTrue(harness.await(() -> out.isFile() && out.length() > 0L, 5_000),
                    "the capture has to reach disk: " + out);

            // Against the viewport the tree was laid out for, not the size the window was asked for: those
            // differ by the frame and by display density, and it is the former that the picture must match or
            // the geometry in a snapshot will not line up with the pixels beside it.
            BufferedImage img = ImageIO.read(out);
            var viewport = gui.viewport().value();
            assertEquals(viewport.width(), img.getWidth(),
                    "captured at the extent the tree was laid out for, not some fixed size");
            assertEquals(viewport.height(), img.getHeight());
            assertTrue(hasMarker(img),
                    "the tree has to be in the picture - a PNG of the clear colour is the failure this catches");
        }
    }

    /** Whether any pixel is the marker, allowing for the 8-bit round trip through the render target. */
    private static boolean hasMarker(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 200 && g < 60 && b > 200) {
                    return true;
                }
            }
        }
        return false;
    }
}
