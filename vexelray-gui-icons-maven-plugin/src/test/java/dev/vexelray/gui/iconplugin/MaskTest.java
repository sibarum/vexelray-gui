package dev.vexelray.gui.iconplugin;

import dev.vexelray.canvas.DistanceField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each kind of art comes out as the same shape: a disc in the middle of the square. */
class MaskTest {

    private static final int FINE = 64;

    @TempDir
    Path dir;

    @Test
    void anAlphaMaskIsItsOpaquePartWhateverItsColour() {
        byte[] mask = Mask.fromRgba(disc(16, 0x00000000, 0xFF2040C0), 16, 16, FINE, Mask.Coverage.ALPHA, false);
        assertDisc(mask);
    }

    @Test
    void blackOnWhiteIsItsDarkPart() {
        byte[] mask = Mask.fromRgba(disc(16, 0xFFFFFFFF, 0xFF000000), 16, 16, FINE, Mask.Coverage.LUMINANCE, false);
        assertDisc(mask);
    }

    @Test
    void autoTellsTheTwoApart() {
        assertDisc(Mask.fromRgba(disc(16, 0x00FFFFFF, 0xFFFFFFFF), 16, 16, FINE, Mask.Coverage.AUTO, false));
        assertDisc(Mask.fromRgba(disc(16, 0xFFFFFFFF, 0xFF000000), 16, 16, FINE, Mask.Coverage.AUTO, false));
    }

    @Test
    void invertTakesTheOtherPart() {
        byte[] mask = Mask.fromRgba(disc(16, 0xFF000000, 0xFFFFFFFF), 16, 16, FINE, Mask.Coverage.LUMINANCE, true);
        assertDisc(mask);
    }

    @Test
    void aTransparentBackgroundUnderBlackOnWhiteIsBackground() {
        byte[] mask = Mask.fromRgba(disc(16, 0x00000000, 0xFF000000), 16, 16, FINE, Mask.Coverage.LUMINANCE, false);
        assertDisc(mask);
    }

    @Test
    void aWideRasterIsFittedAndCentredWithItsAspectKept() {
        // 32 x 16, all shape: fitted, it is a band across the middle half of the square.
        byte[] rgba = new byte[32 * 16 * 4];
        for (int i = 0; i < 32 * 16; i++) {
            rgba[i * 4 + 3] = (byte) 255;
        }
        byte[] mask = Mask.fromRgba(rgba, 32, 16, FINE, Mask.Coverage.ALPHA, false);
        assertTrue(inside(mask, FINE / 2, FINE / 2));
        assertTrue(inside(mask, 1, FINE / 2));
        assertTrue(!inside(mask, FINE / 2, 4), "above the band is outside");
        assertTrue(!inside(mask, FINE / 2, FINE - 4), "below the band is outside");
    }

    @Test
    void anUpscaledRasterHasSmoothEdgesNotItsPixelStaircase() {
        // A 4 x 4 checker corner read at 64: bilinear between pixels, so the edge is a diagonal blend, not a step.
        byte[] mask = Mask.fromRgba(disc(8, 0x00000000, 0xFFFFFFFF), 8, 8, FINE, Mask.Coverage.ALPHA, false);
        int partial = 0;
        for (int i = 3; i < mask.length; i += 4) {
            int a = mask[i] & 0xFF;
            if (a > 10 && a < 245) {
                partial++;
            }
        }
        assertTrue(partial > FINE, "the edge is a blend " + partial + " pixels long");
    }

    @Test
    void aCoverageNameIsChecked() {
        assertEquals(Mask.Coverage.LUMINANCE, Mask.Coverage.parse(" Luminance "));
        assertEquals(Mask.Coverage.AUTO, Mask.Coverage.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Mask.Coverage.parse("brightness"));
    }

    // --- through imagelib: the files a build reads ------------------------------------------------------------

    @Test
    void anSvgFileIsRasterisedAtTheMasksSize() throws IOException {
        Path svg = dir.resolve("disc.svg");
        Files.writeString(svg, """
                <svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg">
                <circle cx="8" cy="8" r="5" fill="#e9ecf2"/>
                </svg>
                """, StandardCharsets.UTF_8);
        assertDisc(Mask.read(svg, FINE, Mask.Coverage.AUTO, false));
    }

    @Test
    void aPngFileOfEitherKindIsRead() throws IOException {
        Path blackOnWhite = png("bw.png", 0xFFFFFFFF, 0xFF000000);
        Path alpha = png("alpha.png", 0x00000000, 0xFF3080FF);
        assertDisc(Mask.read(blackOnWhite, FINE, Mask.Coverage.AUTO, false));
        assertDisc(Mask.read(alpha, FINE, Mask.Coverage.AUTO, false));
    }

    @Test
    void aFieldMadeFromRasterArtHasItsEdgeAtTheDiscsRadius() throws IOException {
        Path art = png("bw.png", 0xFFFFFFFF, 0xFF000000);
        DistanceField f = DistanceField.of(Mask.read(art, DistanceField.maskSide(48), Mask.Coverage.AUTO, false), 48, 12);
        int side = f.side();
        int centre = f.rgba()[(side / 2 * side + side / 2) * 4] & 0xFF;
        int corner = f.rgba()[0] & 0xFF;
        assertTrue(centre > 200, "the middle is well inside: " + centre);
        assertTrue(corner < 40, "the corner is well outside: " + corner);
    }

    private Path png(String name, int background, int ink) throws IOException {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(new Color(background, true));
        g.fillRect(0, 0, 32, 32);
        g.setColor(new Color(ink, true));
        g.fillOval(6, 6, 20, 20);
        g.dispose();
        Path p = dir.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    /** A disc of {@code ink} on {@code background}, {@code n} pixels square, radius a third of it. */
    private static byte[] disc(int n, int background, int ink) {
        byte[] rgba = new byte[n * n * 4];
        float c = n / 2f;
        float r = n / 3f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = x + 0.5f - c;
                float dy = y + 0.5f - c;
                int argb = dx * dx + dy * dy <= r * r ? ink : background;
                int o = (y * n + x) * 4;
                rgba[o] = (byte) (argb >>> 16);
                rgba[o + 1] = (byte) (argb >>> 8);
                rgba[o + 2] = (byte) argb;
                rgba[o + 3] = (byte) (argb >>> 24);
            }
        }
        return rgba;
    }

    private static void assertDisc(byte[] mask) {
        assertEquals(FINE * FINE * 4, mask.length);
        assertTrue(inside(mask, FINE / 2, FINE / 2), "the middle is shape");
        assertTrue(!inside(mask, 2, 2), "a corner is not");
        assertTrue(!inside(mask, FINE - 3, FINE / 2), "the right edge is not");
    }

    private static boolean inside(byte[] mask, int x, int y) {
        return (mask[(y * FINE + x) * 4 + 3] & 0xFF) >= 128;
    }
}
