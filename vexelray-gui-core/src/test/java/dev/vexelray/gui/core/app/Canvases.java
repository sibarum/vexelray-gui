package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.LookTable;

import java.util.Arrays;

/**
 * Canvases for tests that emit a tree with no window: each with a look table of the core set, as every window, its
 * offscreen capture and the headless capture give theirs, since the renderer draws a node's box by a look.
 */
final class Canvases {

    private Canvases() {
    }

    /** A begun canvas {@code w x h}, its look table the core set's. */
    static Canvas core(int w, int h) {
        return new Canvas(w, h).looks(CoreLooks.table(CoreShadelets.composition())).begin();
    }

    /** The element record vertex {@code vertex} of {@code data} names. */
    static int element(float[] data, int vertex) {
        return (int) data[vertex * CanvasVertex.FLOATS_PER_VERTEX + CanvasVertex.OFF_ELEMENT / Float.BYTES];
    }

    /**
     * The first {@code floats} per-instance values of the element vertex {@code vertex} names, in look order: a node
     * box's border, fill and shadow, as many of them as it has. None for an element whose look carries none.
     */
    static float[] instance(Canvas canvas, int vertex, int floats) {
        int element = element(canvas.toVertexArray(), vertex);
        if (element < Composition.RECORDS_BASE) {
            return new float[0];
        }
        int at = element - Composition.RECORDS_BASE + 1;   // past the record's look
        return Arrays.copyOfRange(canvas.records(), at, at + floats);
    }
}
