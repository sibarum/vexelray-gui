package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The visual-transform layer of architecture.md §7 — {@code OPACITY} and {@code TRANSLATE_X/Y} — asserted where
 * it actually has to be true: in the vertices the renderer emits.
 *
 * <p>Both are <b>inherited</b>, opacity multiplicatively and translation additively, so what a node draws at is
 * the accumulation of every one on the path from the root. That makes the interesting failures structural rather
 * than arithmetic: an alpha
 * that is not restored when the walk leaves a faded subtree silently fades everything drawn afterwards, and a
 * colour that skips the fade — the chrome the tree never declared, a scrollbar or a shadow — stays solid over a
 * page that has gone. Both are invisible in a screenshot of the endpoints and obvious mid-transition, which is
 * exactly the kind of bug a crossfade would ship with.
 *
 * <p>No layout runs here. The renderer reads computed geometry off {@link RetainedNode} and resolves nothing of
 * its own, so a hand-placed tree is the whole input — and hand-placing it is also the point: opacity must not
 * reach layout at all, and a test that needed a layout pass to fade a node would not notice if it had.
 */
class TransformTest {

    private static final Color RED = Color.rgba(1f, 0f, 0f, 1f);
    private static final Color GREEN = Color.rgba(0f, 1f, 0f, 1f);
    private static final Color BLUE = Color.rgba(0f, 0f, 1f, 1f);

    /** A 100x100 box at the origin with a background, and whatever else the caller sets. */
    private static RetainedNode box(Color background) {
        RetainedNode n = new RetainedNode(nextId++);
        n.set(PropKey.BACKGROUND, background);
        n.w = 100f;
        n.h = 100f;
        return n;
    }

    private static long nextId = 1;

    private static RetainedNode child(RetainedNode parent, RetainedNode c) {
        c.parent = parent;
        parent.children.add(c);
        return c;
    }

    /** Emit {@code root} and hand back the raw vertex data — no window, no device, no atlas. */
    private static float[] emit(RetainedNode root) {
        Canvas canvas = new Canvas(200, 200).begin();
        // A null face is safe for boxes: it is consulted only by the text path, and none of these nodes has any.
        TreeRenderer.emit(root, canvas, new TextLayout[]{null});
        return java.util.Arrays.copyOf(canvas.toVertexArray(), canvas.vertexCount() * CanvasVertex.FLOATS_PER_VERTEX);
    }

    /**
     * The alpha every vertex whose RGB matches {@code colour} was emitted with. Empty when the colour was never
     * drawn at all — which is how "the subtree was skipped" is distinguished from "it was drawn transparent".
     */
    private static java.util.List<Float> alphasOf(float[] data, Color colour) {
        java.util.List<Float> out = new java.util.ArrayList<>();
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + rgba] == colour.r() && data[v + rgba + 1] == colour.g() && data[v + rgba + 2] == colour.b()) {
                out.add(data[v + rgba + 3]);
            }
        }
        return out;
    }

    /** The one alpha {@code colour} was drawn at; fails if it was never drawn, or drawn at two different alphas. */
    private static float alphaOf(float[] data, Color colour) {
        java.util.List<Float> alphas = alphasOf(data, colour);
        assertTrue(!alphas.isEmpty(), "expected " + colour + " to be drawn at all");
        assertEquals(1, alphas.stream().distinct().count(), "expected one alpha for " + colour + ", got " + alphas);
        return alphas.get(0);
    }

    @Test
    void aNodeWithNoOpacityIsUntouched() {
        RetainedNode root = box(RED);
        assertEquals(1f, alphaOf(emit(root), RED), 1e-6f, "the default is opaque, and costs no multiply");
    }

    @Test
    void opacityAppliesToTheNodeAndItsSubtree() {
        RetainedNode root = box(RED);
        RetainedNode page = child(root, box(GREEN));
        page.set(PropKey.OPACITY, 0.5f);
        child(page, box(BLUE));

        float[] data = emit(root);
        assertEquals(1f, alphaOf(data, RED), 1e-6f, "the parent is not affected by what its child declared");
        assertEquals(0.5f, alphaOf(data, GREEN), 1e-6f);
        assertEquals(0.5f, alphaOf(data, BLUE), 1e-6f, "and it reaches a descendant that declared nothing");
    }

    @Test
    void nestedOpacitiesMultiply() {
        RetainedNode root = box(RED);
        RetainedNode outer = child(root, box(GREEN));
        outer.set(PropKey.OPACITY, 0.5f);
        RetainedNode inner = child(outer, box(BLUE));
        inner.set(PropKey.OPACITY, 0.5f);

        assertEquals(0.25f, alphaOf(emit(root), BLUE), 1e-6f,
                "a nested fade composes with the one it is inside, rather than replacing it");
    }

    @Test
    void opacityMultipliesIntoTheColoursOwnAlpha() {
        RetainedNode root = box(RED);
        RetainedNode page = child(root, box(Color.rgba(0f, 1f, 0f, 0.4f)));
        page.set(PropKey.OPACITY, 0.5f);

        assertEquals(0.2f, alphaOf(emit(root), GREEN), 1e-6f,
                "a colour that was already translucent gets fainter, not reset to the transform's value");
    }

    @Test
    void zeroSkipsTheSubtreeEntirely() {
        RetainedNode root = box(RED);
        RetainedNode page = child(root, box(GREEN));
        page.set(PropKey.OPACITY, 0f);
        child(page, box(BLUE));

        float[] data = emit(root);
        assertTrue(alphasOf(data, GREEN).isEmpty(), "a fully faded node emits no vertices at all");
        assertTrue(alphasOf(data, BLUE).isEmpty(), "nor does anything under it — the walk stops");
        assertEquals(1f, alphaOf(data, RED), 1e-6f, "and the rest of the tree is unaffected");
    }

    /**
     * The failure that a crossfade would otherwise ship with: the walk restores the inherited alpha on its way
     * out, so a sibling drawn <em>after</em> a faded one is not dimmed by it. A single missing restore turns a
     * fading page into a fading window, and only for the nodes that happen to come later in paint order.
     */
    @Test
    void theInheritedAlphaIsRestoredOnTheWayOut() {
        RetainedNode root = box(RED);
        RetainedNode faded = child(root, box(GREEN));
        faded.set(PropKey.OPACITY, 0.25f);
        child(root, box(BLUE));   // a later sibling, drawn on top and after the faded one

        float[] data = emit(root);
        assertEquals(0.25f, alphaOf(data, GREEN), 1e-6f);
        assertEquals(1f, alphaOf(data, BLUE), 1e-6f, "the sibling after a faded subtree draws at full strength");
    }

    /** Every emitted vertex's x, in screen pixels — read back through the NDC mapping the canvas applied. */
    private static java.util.List<Float> xsOf(float[] data, Color colour, int width) {
        java.util.List<Float> out = new java.util.ArrayList<>();
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + rgba] == colour.r() && data[v + rgba + 1] == colour.g() && data[v + rgba + 2] == colour.b()) {
                out.add((data[v] + 1f) * 0.5f * width);   // NDC back to px
            }
        }
        return out;
    }

    /**
     * {@code TRANSLATE} moves what is drawn and nothing else — the second visual transform of §7, and the one
     * that makes a transition read as motion rather than as a slideshow.
     *
     * <p>In multiples of the node's own em, because of when it has to resolve: layout does not re-run for a
     * purely visual prop, so a {@code Length} here would animate against px baked whenever layout last happened
     * to run. The em is already on the node, so the renderer scales by it the way it already scales the lit bevel
     * and the caret width.
     */
    @Test
    void translateMovesTheNodeAndItsSubtree() {
        RetainedNode root = box(RED);
        RetainedNode moved = child(root, box(GREEN));
        moved.emPx = 20f;
        moved.set(PropKey.TRANSLATE_X, 2f);      // 2 em at 20px = 40px right
        RetainedNode inner = child(moved, box(BLUE));

        // Measured against the untranslated parent rather than in absolute pixels: every shape's quad extends
        // the same AA padding past its rect, so a difference is exact where a position would carry it.
        float[] before = emit(root);
        float redAt = xsOf(before, RED, 200).get(0);

        assertEquals(40f, xsOf(before, GREEN, 200).get(0) - redAt, 0.01f, "the node that declared it moves");
        assertEquals(40f, xsOf(before, BLUE, 200).get(0) - redAt, 0.01f,
                "and so does a child that declared nothing, by exactly as much — it is carried, not offset");

        // And it composes: a nested translate adds to the one it is inside.
        inner.emPx = 10f;
        inner.set(PropKey.TRANSLATE_X, 1f);      // a further 1 em at 10px = 10px
        float[] nested = emit(root);
        assertEquals(50f, xsOf(nested, BLUE, 200).get(0) - xsOf(nested, RED, 200).get(0), 0.01f);
        assertEquals(40f, xsOf(nested, GREEN, 200).get(0) - xsOf(nested, RED, 200).get(0), 0.01f,
                "while the parent it is nested in is unaffected by what its child said");
    }

    /** A translated node is skipped by nothing and moves nothing: the transform never reaches the layout. */
    @Test
    void translateDoesNotTouchTheLaidOutBox() {
        RetainedNode root = box(RED);
        RetainedNode moved = child(root, box(GREEN));
        moved.emPx = 20f;
        moved.set(PropKey.TRANSLATE_X, 3f);
        emit(root);

        assertEquals(0f, moved.x, 0.01f, "the box layout computed is untouched — which is what the pointer, the "
                + "read model and every consumer but the renderer still see");
        assertEquals(100f, moved.w, 0.01f);
    }

    /**
     * Chrome the tree never declared has to fade with the node it belongs to. The shadow under an elevated box is
     * the framework's own furniture, drawn from the theme rather than from a prop — so it is precisely the colour
     * most likely to be left behind, and a solid shadow under a page that has faded out is very visible.
     */
    @Test
    void theFrameworksOwnChromeFadesToo() {
        RetainedNode root = box(RED);
        RetainedNode card = child(root, box(GREEN));
        card.elevationPx = 8f;
        card.set(PropKey.OPACITY, 0.5f);

        float[] data = emit(root);
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        boolean sawShadow = false;
        for (int v = 0; v + stride <= data.length; v += stride) {
            boolean isShadow = data[v + CanvasVertex.OFF_KIND / Float.BYTES] == CanvasVertex.KIND_SHADOW;
            if (isShadow) {
                sawShadow = true;
                assertTrue(data[v + rgba + 3] <= 0.5f + 1e-6f,
                        "the shadow under a half-faded card must be at most half as strong as it was");
            }
        }
        assertTrue(sawShadow, "the card is elevated, so a shadow should have been emitted at all");
    }
}
