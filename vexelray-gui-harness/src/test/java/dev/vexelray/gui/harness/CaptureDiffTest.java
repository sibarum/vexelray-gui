package dev.vexelray.gui.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The comparison on images built in memory: no device, no window, just pixels with known differences. */
class CaptureDiffTest {

    private static BufferedImage flat(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    @Test
    void identicalImagesAreTheSame() {
        CaptureDiff.Result r = CaptureDiff.compare(flat(8, 6, 0xFF336699), flat(8, 6, 0xFF336699), 0);
        assertTrue(r.same());
        assertEquals(0, r.differing());
        assertEquals(0, r.maxDelta());
        assertEquals("same", r.describe());
    }

    @Test
    void onePixelWithinToleranceIsTheSame() {
        BufferedImage b = flat(8, 6, 0xFF336699);
        b.setRGB(3, 2, 0xFF33669C);   // blue off by 3
        CaptureDiff.Result r = CaptureDiff.compare(flat(8, 6, 0xFF336699), b, 3);
        assertTrue(r.same());
        assertEquals(3, r.maxDelta(), "the worst channel is still reported");
    }

    @Test
    void onePixelBeyondToleranceIsCountedAndBoxed() {
        BufferedImage b = flat(8, 6, 0xFF336699);
        b.setRGB(5, 4, 0xFF3A6699);   // red off by 7
        CaptureDiff.Result r = CaptureDiff.compare(flat(8, 6, 0xFF336699), b, 3);
        assertFalse(r.same());
        assertEquals(1, r.differing());
        assertEquals(7, r.maxDelta());
        assertEquals(5, r.minX());
        assertEquals(4, r.minY());
        assertEquals(5, r.maxX());
        assertEquals(4, r.maxY());

        BufferedImage diff = CaptureDiff.diffImage(flat(8, 6, 0xFF336699), b, 3);
        assertEquals(0xFFFF00FF, diff.getRGB(5, 4), "the differing pixel is marked");
        assertEquals(0xFF112233, diff.getRGB(0, 0), "the rest is the baseline dimmed to a third");
    }

    @Test
    void alphaCountsAsAChannel() {
        CaptureDiff.Result r = CaptureDiff.compare(flat(2, 2, 0xFF000000), flat(2, 2, 0x80000000), 0);
        assertEquals(4, r.differing());
        assertEquals(0x7F, r.maxDelta());
    }

    @Test
    void differentSizesFailWithoutComparing() {
        CaptureDiff.Result r = CaptureDiff.compare(flat(8, 6, 0xFF000000), flat(8, 7, 0xFF000000), 255);
        assertFalse(r.same(), "no tolerance excuses a size mismatch");
        assertNotNull(r.sizeMismatch());
        assertTrue(r.describe().contains("8x6") && r.describe().contains("8x7"), r.describe());
    }

    @Test
    void filesThatDifferLeaveADiffImageAndFilesThatMatchDoNot(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.png");
        Path b = dir.resolve("b.png");
        Path diff = dir.resolve("b.diff.png");
        ImageIO.write(flat(4, 4, 0xFF808080), "png", a.toFile());
        ImageIO.write(flat(4, 4, 0xFF808080), "png", b.toFile());
        assertTrue(CaptureDiff.compare(a, b, 0, diff).same());
        assertFalse(Files.exists(diff));

        BufferedImage off = flat(4, 4, 0xFF808080);
        off.setRGB(1, 1, 0xFF000000);
        ImageIO.write(off, "png", b.toFile());
        CaptureDiff.Result r = CaptureDiff.compare(a, b, 0, diff);
        assertEquals(1, r.differing());
        assertEquals(0xFFFF00FF, ImageIO.read(diff.toFile()).getRGB(1, 1));
    }

    @Test
    void namesSortWithTheirNumbersAsNumbers() {
        assertTrue(CaptureDiff.natural("chapter-2.png", "chapter-10.png") < 0);
        assertTrue(CaptureDiff.natural("chapter-10.png", "chapter-9.png") > 0);
        assertEquals(0, CaptureDiff.natural("chapter-3.png", "chapter-3.png"));
    }
}
