package dev.vexelray.gui.iconplugin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A square with a disc above it, the disc lifted: the shape is the square alone, the colour has both. */
class ArtPairTest {

    static final byte[] ART = ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
            + "<rect x=\"20\" y=\"40\" width=\"60\" height=\"50\" fill=\"#3080ff\"/>"
            + "<g id=\"orb\"><circle cx=\"50\" cy=\"20\" r=\"12\" fill=\"#ffb040\"/></g></svg>")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void theShapeLeavesTheLiftedPartOutAndTheColourKeepsIt() throws Exception {
        ArtPair pair = ArtPair.bake(ART, 40, 0.5f, new int[]{50, 100}, "orb");
        int fside = pair.fieldSide();
        assertEquals(80, fside, "40 texels and 20 each side");

        ArtPair.Lift lift = pair.lift();
        assertNotNull(lift);
        assertEquals(0.50, lift.x(), 0.01, "the disc's centre across");
        assertEquals(0.20, lift.y(), 0.01, "and down");
        assertEquals(0.12, lift.radius(), 0.01, "and its radius");

        ArtPair.Atlas a = pair.atlases().get(1);
        assertEquals(100, a.size());
        // In the field: alpha is the far distance, the median the near one; both above one half inside.
        int square = texel(a, 20 + 20, 20 + 26, 3);        // the square's middle, in field texels
        int disc = texel(a, 20 + 20, 20 + 8, 3);           // where the disc was
        assertTrue(square > 128, "the square is inside the shape: " + square);
        assertTrue(disc < 128, "the lifted disc is not: " + disc);
        assertEquals(255, texel(a, 20 + 20, 20 + 26, 0), "deep inside, the near channels are saturated");

        // In the colour: the disc is there, premultiplied and opaque.
        int discAlpha = texel(a, a.colourX() + 50, a.colourY() + 20, 3);
        int discRed = texel(a, a.colourX() + 50, a.colourY() + 20, 0);
        assertEquals(255, discAlpha, "the colour keeps what was lifted");
        assertEquals(0xff, discRed, 2);
        assertEquals(0, texel(a, a.colourX() + 5, a.colourY() + 5, 3), "and is clear where the drawing is");
        assertTrue(pair.descriptor().contains("lift=0.5"), pair.descriptor());
    }

    @Test
    void aLiftThatIsNotThereIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ArtPair.bake(ART, 40, 0.5f, new int[]{50}, "moon"));
    }

    private static int texel(ArtPair.Atlas a, int x, int y, int c) {
        return a.rgba()[(y * a.width() + x) * 4 + c] & 0xFF;
    }
}
