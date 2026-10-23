package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.FocusState;
import dev.vexelray.gui.core.layout.LayoutContext;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Finish;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.PaintMotion;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.gui.core.text.TextMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simplified graphics draws flat with the looks that already exist: a lit fill plain, no shadow, letterpress once.
 * Decided before the look is chosen, so the static look cache is never asked for a lit or shadowed look while the
 * mode is on, and needs no mode in its key.
 */
class SimplifiedLooksTest {

    @Test
    void aLitShadowedBoxAsksForThePlainUnshadowedLook() {
        int fill = TreeRenderer.fillDrawn(TreeRenderer.LIT_FILL, true);
        boolean shadow = TreeRenderer.shadowDrawn(true, true);
        assertEquals(TreeRenderer.PLAIN_FILL, fill);
        assertSame(TreeRenderer.boxLook(true, TreeRenderer.PLAIN_FILL, false), TreeRenderer.boxLook(true, fill, shadow),
                "one of the twelve looks, the same one a plain box with no elevation draws with");
        assertNotSame(TreeRenderer.boxLook(true, TreeRenderer.LIT_FILL, true), TreeRenderer.boxLook(true, fill, shadow));
    }

    @Test
    void outOfTheModeEveryPartIsAsAskedForAndNoFillStaysNone() {
        assertEquals(TreeRenderer.LIT_FILL, TreeRenderer.fillDrawn(TreeRenderer.LIT_FILL, false));
        assertEquals(TreeRenderer.NO_FILL, TreeRenderer.fillDrawn(TreeRenderer.NO_FILL, true));
        assertTrue(TreeRenderer.shadowDrawn(true, false));
        assertTrue(TreeRenderer.letterpressDrawn(true, false));
        assertEquals(false, TreeRenderer.letterpressDrawn(true, true));
    }

    @Test
    void anElevatedLitNodeWritesAShorterRecordInSimplifiedGraphics() {
        // Full: fill colour 4 + bevel + gradient, shadow colour 4 + lift. Flat: the fill colour alone.
        assertEquals(7, records(box(), false) - records(box(), true),
                "the bevel, the gradient and the whole shadow are left out of the record");
    }

    @Test
    void aShadowOnlyNodeDrawsNothingInSimplifiedGraphics() {
        RetainedNode n = box();
        n.set(PropKey.BACKGROUND, null);
        n.set(PropKey.BORDER_COLOR, Paint.of(Color.rgba(0f, 0f, 1f, 1f)));
        // A border with no width is not drawn, so the elevation's shadow is all the box would carry.
        assertTrue(vertices(n, false) > 0, "the shadow is drawn");
        assertEquals(0, vertices(n, true), "and with it gone, nothing is left to draw");
    }

    @Test
    void letterpressedTextIsDrawnOnceInSimplifiedGraphics() {
        int full = vertices(text(), false);
        int flat = vertices(text(), true);
        assertTrue(flat > 0, "the text is still drawn");
        assertEquals(full, 3 * flat, "three passes pressed, one flat: " + full + " vs " + flat);
    }

    private static RetainedNode box() {
        RetainedNode n = new RetainedNode(1);
        n.x = 10f;
        n.y = 10f;
        n.w = 100f;
        n.h = 40f;
        n.cornerPx = 4f;
        n.cornerBottomPx = 4f;
        n.elevationPx = 6f;
        n.set(PropKey.BACKGROUND, Paint.of(Color.rgba(1f, 0f, 0f, 1f)));
        n.set(PropKey.LIT, Finish.of(true));
        return n;
    }

    private static RetainedNode text() {
        RetainedNode n = new RetainedNode(2);
        n.x = 10f;
        n.y = 10f;
        n.w = 200f;
        n.h = 24f;
        n.viewX = 10f;
        n.viewY = 10f;
        n.viewW = 200f;
        n.viewH = 24f;
        n.textSizePx = 16f;
        n.set(PropKey.TEXT, "Hi");
        n.set(PropKey.TEXT_COLOR, Paint.of(Color.rgba(1f, 1f, 1f, 1f)));
        n.set(PropKey.TEXT_SUNKEN, Finish.of(true));
        n.textMetrics = new TextMetrics(List.of(
                new TextMetrics.VisualLine(0, 2, 12f, 16f, new float[]{12f, 22f, 30f}, 0)));
        return n;
    }

    private static Canvas emit(RetainedNode n, boolean simplified) {
        Canvas canvas = Canvases.core(400, 200);
        TreeRenderer.emit(n, canvas, TextFaces.standard(), Theme.DARK, FocusState.NONE, LayoutContext.of(400f, 200f),
                PaintMotion.NONE, simplified);
        return canvas;
    }

    private static int records(RetainedNode n, boolean simplified) {
        return emit(n, simplified).recordCount();
    }

    private static int vertices(RetainedNode n, boolean simplified) {
        return emit(n, simplified).toVertexArray().length / CanvasVertex.FLOATS_PER_VERTEX;
    }
}
