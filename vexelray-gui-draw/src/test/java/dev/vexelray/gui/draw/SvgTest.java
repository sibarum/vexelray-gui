package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file target. Asserted as text, because the output <em>is</em> text and a reader of the file is the second
 * consumer this whole module exists for: if the elements are not what a browser and a stylesheet expect, nothing
 * downstream can tell.
 */
class SvgTest {

    private static final Color INK = Color.rgb(0.1f, 0.2f, 0.3f);   // #1a334d, checked below
    private static final String HEX = "#1a334d";

    private static String svg(Picture picture) {
        StringBuilder out = new StringBuilder();
        picture.emitTo(new SvgSink(out));
        return out.toString();
    }

    @Test
    void aSquareFillIsARectAndAColourIsAPresentationAttribute() {
        // A presentation attribute rather than a style: in CSS it loses to every stylesheet rule, so a host page
        // can restyle the picture by class while a file opened on its own still looks like it did on screen.
        assertEquals("<rect x=\"0\" y=\"0\" width=\"10\" height=\"4\" fill=\"" + HEX + "\"/>\n",
                svg(new Sketch().fill(0, 0, 10, 4, INK).picture()));
    }

    @Test
    void aRoundedFillCarriesOneRadiusAndATwoRadiusBoxBecomesAPath() {
        assertEquals("<rect x=\"0\" y=\"0\" width=\"10\" height=\"4\" rx=\"1.5\" fill=\"" + HEX + "\"/>\n",
                svg(new Sketch().fill(0, 0, 10, 4, 1.5, INK).picture()));
        // A tab — (r, 0) — is the one shape <rect> cannot describe, since it has a single rx. Corners are arcs of
        // their own radius, clamped to half the smaller side exactly as the canvas clamps them, and a corner of
        // no radius adds no command because the edge before it already ended there.
        assertEquals("<path d=\"M2 0L8 0A2 2 0 0 1 10 2L10 4L0 4L0 2A2 2 0 0 1 2 0Z\" fill=\"" + HEX + "\"/>\n",
                svg(new Sketch().fill(0, 0, 10, 4, 9, 0, INK).picture()));
    }

    @Test
    void aBoxWithNothingStraightLeftIsWrittenAsACircle() {
        // Same pixels as <rect rx="r">, but a plot's markers are circles and someone opening the file to restyle
        // them should find the element they went looking for.
        assertEquals("<circle cx=\"10\" cy=\"20\" r=\"4\" fill=\"" + HEX + "\"/>\n",
                svg(new Sketch().circle(10, 20, 4, INK).picture()));
        // And a ring too — after the inset, which is what moves its radius as well as its box.
        assertEquals("<circle cx=\"10\" cy=\"20\" r=\"3.5\" fill=\"none\" stroke=\"" + HEX
                        + "\" stroke-width=\"1\"/>\n",
                svg(new Sketch().ring(10, 20, 4, 1, INK).picture()));
    }

    @Test
    void anOutlineIsInsetByHalfItsWidthSoTheTwoTargetsDrawTheSameRing() {
        // SVG strokes straddle the path; the canvas's hug the inside edge of the box. Without the inset the same
        // numbers would describe a ring half a stroke wider here than on screen.
        assertEquals("<rect x=\"1\" y=\"1\" width=\"8\" height=\"2\" fill=\"none\" stroke=\"" + HEX
                        + "\" stroke-width=\"2\"/>\n",
                svg(new Sketch().outline(0, 0, 10, 4, 2, INK).picture()));
        // And the radius comes in with it, or the corner would bulge past the box the outline is describing.
        assertEquals("<rect x=\"1\" y=\"1\" width=\"8\" height=\"2\" rx=\"2\" fill=\"none\" stroke=\"" + HEX
                        + "\" stroke-width=\"2\"/>\n",
                svg(new Sketch().outline(0, 0, 10, 4, 3, 2, INK).picture()));
    }

    @Test
    void aLineIsRoundCappedBecauseTheCanvasesAre() {
        assertEquals("<line x1=\"0\" y1=\"0\" x2=\"10\" y2=\"4.5\" stroke=\"" + HEX
                        + "\" stroke-width=\"1.5\" stroke-linecap=\"round\"/>\n",
                svg(new Sketch().line(0, 0, 10, 4.5, 1.5, INK).picture()));
    }

    @Test
    void textIsAnchoredOnItsBaselineAndItsContentIsEscaped() {
        // SVG's <text y> is the baseline, which is the anchor a picture states, so the number goes straight on.
        // Quotes are escaped in element content too, where XML does not require it: one escaper serves content
        // and attributes, and over-escaping reads back identically while under-escaping breaks the document.
        assertEquals("<text x=\"2\" y=\"14\" font-size=\"12\" fill=\"" + HEX
                        + "\">x &lt; y &amp; &quot;z&quot;</text>\n",
                svg(new Sketch().text("x < y & \"z\"", 2, 14, 12, INK).picture()));
    }

    @Test
    void aTagBecomesACssClass() {
        // The reason a tag is carried at all: the semantics the application knew survive into a file read by
        // something that never saw the application.
        assertEquals("<line x1=\"0\" y1=\"0\" x2=\"0\" y2=\"9\" stroke=\"" + HEX
                        + "\" stroke-width=\"1\" stroke-linecap=\"round\" class=\"axis\"/>\n",
                svg(new Sketch().tag("axis").line(0, 0, 0, 9, 1, INK).picture()));
    }

    @Test
    void transparencyIsItsOwnAttributeSoAStylesheetCanKeepIt() {
        // A wash is geometry-adjacent — an enclosure band means "somewhere in here" — so overriding the colour
        // must not make it opaque.
        Color band = Color.rgba(0.1f, 0.2f, 0.3f, 0.25f);
        assertEquals("<rect x=\"0\" y=\"0\" width=\"4\" height=\"4\" fill=\"" + HEX + "\" fill-opacity=\"0.25\"/>\n",
                svg(new Sketch().fill(0, 0, 4, 4, band).picture()));
    }

    @Test
    void anUnpaintedMarkIsWrittenAsNoneRatherThanDropped() {
        // The one deliberate difference between the targets: the canvas cannot draw an unpainted shape, while an
        // unpainted element with a class is exactly what a host stylesheet is for.
        assertEquals("<rect x=\"0\" y=\"0\" width=\"4\" height=\"4\" fill=\"none\" class=\"band\"/>\n",
                svg(new Sketch().tag("band").fill(0, 0, 4, 4, null).picture()));
    }

    @Test
    void numbersAreWrittenForAReaderInAnyLocale() {
        // A decimal comma is a parse error in SVG, and 1.0E-4 is not a length. Three decimals, trailing zeros
        // trimmed, and a whole number with no point at all.
        assertEquals("0", SvgSink.num(0));
        assertEquals("-7", SvgSink.num(-7));
        assertEquals("0.5", SvgSink.num(0.5));
        assertEquals("0.333", SvgSink.num(1.0 / 3));
        assertEquals("0", SvgSink.num(1e-9), "below the printed precision, which is a pixel position of nothing");
        assertEquals("1234.5", SvgSink.num(1234.50));
    }

    @Test
    void aDocumentOpensAtTheSizeItWasDrawnAtAndScalesFromThere() {
        String doc = Svg.document(new Sketch().tag("curve").line(0, 0, 100, 50, 2, INK).picture(), 200, 120);
        assertEquals("""
                <svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120" \
                font-family="system-ui, sans-serif">
                  <line x1="0" y1="0" x2="100" y2="50" stroke=\"""" + HEX + """
                " stroke-width="2" stroke-linecap="round" class="curve"/>
                </svg>
                """, doc);
    }

    @Test
    void theFontFamilyIsDeclaredOnceOnTheRoot() {
        // One inherited declaration a stylesheet can override in one place, rather than the same attribute on
        // every label. The atlas face a picture was drawn with is a GPU artefact and cannot travel; a family
        // name is the most a file can honestly carry.
        String doc = Svg.document(new Sketch().text("t", 0, 10, 10, INK).picture(), 20, 20, "Iosevka, monospace");
        assertTrue(doc.contains("font-family=\"Iosevka, monospace\""));
        assertFalse(doc.contains("<text x=\"0\" y=\"10\" font-family"),
                "a run states its size, never its family");
    }

    @Test
    void anEmptyPictureIsStillAValidDocument() {
        assertEquals("""
                <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 10 10" \
                font-family="system-ui, sans-serif">
                </svg>
                """, Svg.document(Picture.EMPTY, 10, 10));
    }
}
