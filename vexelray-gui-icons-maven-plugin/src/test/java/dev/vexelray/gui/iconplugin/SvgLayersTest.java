package dev.vexelray.gui.iconplugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A drawing comes apart into its colours in the order it paints them, and the two palettes must agree on shapes. */
class SvgLayersTest {

    /** A page with a border, a disc on it, a mark on the disc, a band in the disc's colour, and a word on the band. */
    private static final String PAGE = """
            <svg width="64" height="64" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
            <defs><clipPath id="c"><rect x="8" y="4" width="48" height="56"/></clipPath></defs>
            <rect x="8" y="4" width="48" height="56" fill="#032024" stroke="#16606a" stroke-width="1.1"/>
            <g fill="#2ED4EA"><circle cx="32" cy="28" r="12"/></g>
            <path d="M28 28 H36" fill="none" stroke="#011316" stroke-width="3"/>
            <rect x="8" y="44" width="48" height="16" fill="#2ed4ea" clip-path="url(#c)"/>
            <rect x="8" y="46" width="48" height="4" fill="#2ed4ea"/>
            <text x="32" y="56" fill="#011316">JAVA</text>
            </svg>
            """;

    @Test
    void eachRunOfOneColourIsALayerBackToFront() {
        List<SvgLayers.Layer> layers = SvgLayers.split(PAGE);
        // The page's fill, its border, the disc (its colour inherited from its group), the mark, then the two bands in
        // the disc's colour as one. The word is not painted.
        assertEquals(List.of("#032024", "#16606a", "#2ed4ea", "#011316", "#2ed4ea"),
                layers.stream().map(SvgLayers.Layer::color).toList());
        for (SvgLayers.Layer layer : layers) {
            assertFalse(layer.svg().contains("JAVA"), "text is left out");
            assertTrue(layer.svg().contains("clipPath"), "definitions stay, so clips still clip");
        }
    }

    @Test
    void aLayerIsItsOwnPaintsInBlackAndNothingElse() {
        String border = SvgLayers.split(PAGE).get(1).svg();
        assertEquals(1, count(border, "stroke=\"#000000\""));
        assertEquals(0, count(border, "fill=\"#000000\""));
        String bands = SvgLayers.split(PAGE).get(4).svg();
        assertEquals(2, count(bands, "fill=\"#000000\""));
    }

    @Test
    void theSilhouetteIsEveryPaint() {
        String all = SvgLayers.silhouette(PAGE);
        assertEquals(6, count(all, "\"#000000\""));
        assertFalse(all.contains("JAVA"));
    }

    @Test
    void aLightDrawingMustBeTheDarkOnesShapes() {
        // The canvas numbers clips across a whole board, so the light drawing calls its clip by another name.
        String light = PAGE.replace("#032024", "#f4fbfc").replace("#2ED4EA", "#1d8c9a").replace("#2ed4ea", "#1d8c9a")
                .replace("id=\"c\"", "id=\"c16\"").replace("url(#c)", "url(#c16)");
        LayeredMojo.Icon icon = LayeredMojo.icon("page", PAGE, light);
        assertEquals("#f4fbfc", icon.light().getFirst());
        assertEquals(icon.masks().size(), icon.dark().size());
        String moved = light.replace("r=\"12\"", "r=\"13\"");
        assertThrows(IllegalArgumentException.class, () -> LayeredMojo.icon("page", PAGE, moved));
    }

    @Test
    void aPaintThatIsNotOneColourIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> SvgLayers.split(PAGE.replace("fill=\"#032024\"", "fill=\"url(#g)\"")));
        assertThrows(IllegalArgumentException.class,
                () -> SvgLayers.split(PAGE.replace("fill=\"#032024\"", "fill=\"#032024\" opacity=\".5\"")));
    }

    private static int count(String s, String part) {
        return s.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
