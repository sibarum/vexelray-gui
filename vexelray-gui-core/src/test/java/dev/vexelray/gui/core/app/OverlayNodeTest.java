package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A decoration on a node ({@code PropKey.OVERLAY}) — asserted in the vertices, because the single claim that
 * makes it usable is about <b>paint order</b>, and paint order is not visible anywhere else.
 *
 * <p>A decoration that a child can cover has failed at the one job it has. {@code PICTURE} deliberately goes
 * under the border (an application's marks belong inside the frame the node draws around them) and children go
 * over everything the node draws itself, so an overlay emitted anywhere but last is silently wrong — and wrong
 * only for nodes that happen to have content on top of it, which is most of the ones worth decorating.
 */
class OverlayNodeTest {

    private static final Color MARK = Color.rgba(1f, 0f, 0f, 1f);      // the overlay
    private static final Color PANEL = Color.rgba(0f, 0f, 1f, 1f);     // the node's own background
    private static final Color CHILD = Color.rgba(0f, 1f, 0f, 1f);     // a child drawn over that background

    private static long nextId = 1;

    private static RetainedNode box(Color bg) {
        RetainedNode n = new RetainedNode(nextId++, NodeKind.BOX);
        n.set(PropKey.BACKGROUND, bg);
        n.w = 100f;
        n.h = 50f;
        return n;
    }

    private static float[] emit(RetainedNode root) {
        Canvas canvas = new Canvas(400, 200).begin();
        TreeRenderer.emit(root, canvas, new TextLayout[]{null});
        return Arrays.copyOf(canvas.toVertexArray(), canvas.vertexCount() * CanvasVertex.FLOATS_PER_VERTEX);
    }

    /** The order the three colours first appear in the vertex buffer — submission order is paint order. */
    private static String order(float[] data) {
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        StringBuilder out = new StringBuilder();
        char last = 0;
        for (int v = 0; v + stride <= data.length; v += stride) {
            char c = data[v + rgba] == 1f ? 'o' : data[v + rgba + 1] == 1f ? 'c' : 'p';
            if (c != last) {
                out.append(c);
                last = c;
            }
        }
        return out.toString();
    }

    private static Picture dot() {
        return new Sketch().fill(0, 0, 4, 4, MARK).picture();
    }

    /** The whole point: it is painted, and it is painted after everything the subtree drew. */
    @Test
    void anOverlayIsDrawnOverTheNodeAndOverItsChildren() {
        RetainedNode n = box(PANEL);
        RetainedNode child = box(CHILD);
        child.parent = n;
        n.children.add(child);
        n.set(PropKey.OVERLAY, dot());

        assertEquals("pco", order(emit(n)),
                "background, then the child, then the decoration — a cue a label can cover is no cue");
    }

    /** The other half of the pair: content still goes under the border and the text, exactly as it did. */
    @Test
    void aPictureAndAnOverlayLandOnOppositeSidesOfTheSubtree() {
        RetainedNode n = box(PANEL);
        RetainedNode child = box(CHILD);
        child.parent = n;
        n.children.add(child);
        n.set(PropKey.PICTURE, dot());
        n.set(PropKey.OVERLAY, dot());

        assertEquals("poco", order(emit(n)),
                "the same value in the two slots brackets the subtree, which is the whole difference");
    }

    /** A floating child is the framework's overlay primitive; a decoration still goes over one. */
    @Test
    void anOverlayIsDrawnOverAFloatingChildToo() {
        RetainedNode n = box(PANEL);
        RetainedNode floater = box(CHILD);
        floater.parent = n;
        floater.set(PropKey.FLOAT_X, dev.vexelray.gui.core.layout.Length.ZERO);
        n.children.add(floater);
        n.set(PropKey.OVERLAY, dot());

        assertEquals("pco", order(emit(n)));
    }

    /** Same clip and same frame as a picture: {@code (0, 0)} is the box, and nothing escapes it. */
    @Test
    void anOverlayIsClippedToTheBoxAndDrawnInIt() {
        RetainedNode n = box(PANEL);
        n.x = 120f;
        n.y = 60f;
        n.set(PropKey.OVERLAY, new Sketch().fill(0, 0, 4000, 4000, MARK).picture());

        float[] data = emit(n);
        int c = CanvasVertex.OFF_CLIPBOX / Float.BYTES;
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + rgba] == 1f) {
                assertEquals(170f, data[v + c], 0.01f, "clipped to the box's own centre");
                assertEquals(85f, data[v + c + 1], 0.01f);
                assertEquals(50f, data[v + c + 2], 0.01f);
                assertEquals(25f, data[v + c + 3], 0.01f);
                assertEquals(100f, n.w, 0.01f, "and a mark wider than the box does not widen it");
                return;
            }
        }
        throw new AssertionError("the overlay was never drawn");
    }

    /** It fades with the subtree it decorates, like every other colour the renderer emits. */
    @Test
    void anOverlayFadesWithItsNode() {
        RetainedNode n = box(PANEL);
        n.set(PropKey.OPACITY, 0.5f);
        n.set(PropKey.OVERLAY, dot());

        float[] data = emit(n);
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        boolean seen = false;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + rgba] == 1f) {
                assertEquals(0.5f, data[v + rgba + 3], 0.001f);
                seen = true;
            }
        }
        assertTrue(seen, "the overlay should have been drawn at all");
    }

    /** Not layout-affecting, so putting one on a node never reflows anything. */
    @Test
    void anOverlayIsNotALayoutInput() {
        assertTrue(!PropKey.OVERLAY.layoutAffecting());
        assertTrue(!PropKey.OVERLAY.geometryAffecting());
    }
}
