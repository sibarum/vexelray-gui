package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The screen target, measured on a real {@link Canvas}: what a picture costs, and that it costs nothing else.
 *
 * <p>There is no GPU here and none is needed — a canvas is a vertex array builder, so the buffer it produces is
 * the whole observable result of drawing.
 */
class CanvasSinkTest {

    private static final Color INK = Color.rgb(0.1f, 0.2f, 0.3f);

    /** Every canvas primitive is one quad, and a quad is two triangles. */
    private static final int PER_MARK = 6;

    private static Canvas canvas() {
        return new Canvas(200, 100).begin();
    }

    @Test
    void aPictureCostsSixVerticesAMarkAndNothingElse() {
        Canvas c = canvas();
        new Sketch()
                .fill(0, 0, 20, 10, INK)
                .outline(0, 0, 20, 10, 1, INK)
                .line(0, 0, 20, 10, 2, INK)
                .circle(50, 50, 4, INK)
                .picture()
                .emitTo(new CanvasSink(c, null));
        assertEquals(4 * PER_MARK, c.vertexCount(),
                "four marks, four quads — the alphabet is the canvas's own primitives, so nothing is emulated");
    }

    @Test
    void aPictureStaysInTheOneBatchTheRestOfTheUiIsIn() {
        // The claim that makes a picture cheap: it is not a second pass, a second draw call or a texture. A run
        // boundary only opens for an image, so a drawing of shapes and lines is still exactly one run — which is
        // the single draw call the canvas always was.
        Canvas c = canvas();
        c.fillRoundRect(0, 0, 200, 100, 4, INK);   // the panel the drawing sits on
        new Sketch().line(0, 0, 20, 10, 1, INK).picture().emitTo(new CanvasSink(c, null));
        List<Canvas.Run> runs = c.runs();
        assertEquals(1, runs.size());
        assertEquals(2 * PER_MARK, runs.get(0).vertexCount());
        assertEquals(null, runs.get(0).image(), "no image was bound, so the binding layer binds its placeholder");
    }

    @Test
    void everyColourGoesThroughTheSubtreeOpacity() {
        // A picture inside a fading subtree fades with it — the same treatment the tree renderer gives every
        // colour it paints, applied here because the sink is the only place a picture's colours are read.
        Canvas c = canvas();
        new Sketch().fill(0, 0, 10, 10, Color.rgba(0.1f, 0.2f, 0.3f, 0.8f)).picture()
                .emitTo(new CanvasSink(c, null, 0.5f));
        float[] v = c.toVertexArray();
        assertEquals(0.4f, v[5], 1e-6f, "0.8 of the mark's own alpha, halved by the subtree");
        assertEquals(0.1f, v[2], 1e-6f, "and the hue is untouched: opacity is not a shade");
    }

    @Test
    void anUnpaintedMarkEmitsNoVerticesAtAll() {
        // Not drawn transparent: the vertices of an invisible shape are still vertices, and a picture is allowed
        // to carry a mark whose colour a host stylesheet is expected to supply (see the SVG side).
        Canvas c = canvas();
        new Sketch().fill(0, 0, 10, 10, null).picture().emitTo(new CanvasSink(c, null));
        assertEquals(0, c.vertexCount());
    }

    @Test
    void textWithNoAtlasResolvedIsSkippedRatherThanFailingTheFrame() {
        // The same degradation the tree renderer already makes for a text node under a null face. Losing a whole
        // window over a missing label is the worse answer.
        Canvas c = canvas();
        new Sketch().fill(0, 0, 10, 10, INK).text("42", 0, 9, 10, INK).picture()
                .emitTo(new CanvasSink(c, null));
        assertEquals(PER_MARK, c.vertexCount(), "the shapes drew; the run did not");
    }

    @Test
    void theVertexStrideIsTheCanvasItsOwn() {
        // A guard on the arithmetic the opacity test does by hand: if the vertex layout grows, that indexing is
        // what breaks, and it should break here rather than in a colour that looks slightly wrong.
        assertEquals(23, CanvasVertex.FLOATS_PER_VERTEX);
    }
}
