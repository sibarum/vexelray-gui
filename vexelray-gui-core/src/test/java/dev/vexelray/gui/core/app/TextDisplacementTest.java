package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.text.TextMetrics;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A text node that a {@link dev.vexelray.gui.core.layout.LayoutMotion} has displaced draws <b>all</b> of itself
 * in the displaced place — the glyphs and everything positioned with them, not only the box.
 *
 * <p>The bug this exists for: a node's {@link TextMetrics} are baked by the compute phase, which runs before the
 * frame's displacement is applied, so every coordinate in them is in the node's <em>settled</em> frame while
 * {@code x} and {@code viewX} are in its <em>drawn</em> one. Drawing the two together left a sliding row's fill
 * moving and its text standing still. It is invisible at both ends of a transition and obvious in the middle,
 * and worse than merely wrong: only nodes a motion source has enrolled were affected, so a list mid-slide tore
 * into the moving half and the still half.
 *
 * <p>Asserted on the caret rather than on glyphs because a caret is a plain quad — it needs no atlas, and this
 * test needs no font to be true. Every other metric-positioned mark (selection, span backgrounds, underlines,
 * line numbers, the glyph runs themselves) is drawn from the same coordinates under the same transform.
 */
class TextDisplacementTest {

    private static final Color INK = Color.rgba(1f, 0f, 0f, 1f);
    private static final Color BOX = Color.rgba(0f, 0f, 1f, 1f);

    /** Where the metrics say the one visual line is. Arbitrary, and deliberately not the box's own origin. */
    private static final float LINE_X = 12f;
    private static final float LINE_TOP = 30f;

    private static long nextId = 1;

    /**
     * A text node with hand-baked metrics: one empty visual line, and a caret on it. Empty text on purpose —
     * the glyph loop is then a no-op, so no atlas is consulted and the caret is the only thing drawn.
     */
    private static RetainedNode caretNode(float x, float y) {
        RetainedNode n = new RetainedNode(nextId++);
        n.x = x;
        n.y = y;
        n.w = 200f;
        n.h = 24f;
        n.viewX = x;
        n.viewY = y;
        n.viewW = 200f;
        n.viewH = 24f;
        n.textSizePx = 16f;
        n.set(PropKey.TEXT, "");
        n.set(PropKey.TEXT_COLOR, INK);
        n.set(PropKey.CARET, 0);
        n.set(PropKey.CARET_ON, true);
        n.textMetrics = new TextMetrics(List.of(
                new TextMetrics.VisualLine(0, 0, LINE_TOP, 16f, new float[]{LINE_X}, 0)));
        return n;
    }

    /** Record where the node was settled, as {@code Displacement.settle} would have. */
    private static void settled(RetainedNode n) {
        n.layoutX = n.x;
        n.layoutY = n.y;
        n.layoutViewX = n.viewX;
        n.layoutViewY = n.viewY;
        n.layoutRectKnown = true;
    }

    /** Move the node the way {@code Displacement.displace} does: the drawn box, leaving the record behind. */
    private static void displace(RetainedNode n, float dx, float dy) {
        n.x = n.layoutX + dx;
        n.y = n.layoutY + dy;
        n.viewX = n.layoutViewX + dx;
        n.viewY = n.layoutViewY + dy;
    }

    private static float[] emit(RetainedNode root) {
        Canvas canvas = new Canvas(400, 400).begin();
        // A null face is safe: with empty text the glyph path never runs, and the caret is a plain quad.
        TreeRenderer.emit(root, canvas, new TextLayout[]{null});
        return java.util.Arrays.copyOf(canvas.toVertexArray(),
                canvas.vertexCount() * CanvasVertex.FLOATS_PER_VERTEX);
    }

    /** The top-left corner of everything drawn in {@code colour} — the caret's origin, here. */
    private static float[] originOf(float[] data, Color colour) {
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int pos = CanvasVertex.OFF_POS / Float.BYTES;
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + rgba] == colour.r() && data[v + rgba + 1] == colour.g()
                    && data[v + rgba + 2] == colour.b()) {
                minX = Math.min(minX, data[v + pos]);
                minY = Math.min(minY, data[v + pos + 1]);
            }
        }
        assertTrue(Float.isFinite(minX), "expected " + colour + " to be drawn at all");
        return new float[]{minX, minY};
    }

    /**
     * The canvas emits clip space, and a quad carries a little antialiasing padding, so distances are asserted
     * rather than positions: the padding is the same in both pictures and cancels, which makes the assertion
     * about the displacement and nothing else.
     */
    private static float px(float ndcDistance) {
        return ndcDistance * CANVAS / 2f;
    }

    private static final float CANVAS = 400f;

    /** Where the caret is drawn when the node is settled and standing still — the reference every case is against. */
    private static float[] atRest() {
        RetainedNode n = caretNode(0f, 0f);
        settled(n);
        return originOf(emit(n), INK);
    }

    @Test
    void aDisplacedNodeCarriesItsTextWithIt() {
        float[] rest = atRest();

        RetainedNode n = caretNode(0f, 0f);
        settled(n);
        displace(n, 7f, 40f);
        float[] moved = originOf(emit(n), INK);

        assertEquals(7f, px(moved[0] - rest[0]), 0.05f, "the text moved with the box, not without it");
        assertEquals(40f, px(moved[1] - rest[1]), 0.05f);
    }

    /**
     * A node that has never been settled has no recorded position, so there is no displacement to derive — and
     * deriving one from a zeroed field would throw its text across the window on the first frame it appears.
     */
    @Test
    void aNodeThatWasNeverSettledIsNotDisplaced() {
        float[] rest = atRest();

        RetainedNode n = caretNode(80f, 90f);   // laid out somewhere, never recorded
        float[] at = originOf(emit(n), INK);

        assertEquals(0f, px(at[0] - rest[0]), 0.05f, "an arrival is not a journey, for text either");
        assertEquals(0f, px(at[1] - rest[1]), 0.05f);
    }

    /** And the box travels the same distance, which is the agreement the whole fix is about. */
    @Test
    void theBoxAndTheTextTravelTogether() {
        RetainedNode n = caretNode(0f, 0f);
        n.set(PropKey.BACKGROUND, BOX);
        settled(n);
        float[] restBox = originOf(emit(n), BOX);
        float[] restInk = originOf(emit(n), INK);

        displace(n, 7f, 40f);
        float[] movedBox = originOf(emit(n), BOX);
        float[] movedInk = originOf(emit(n), INK);

        assertEquals(px(movedBox[0] - restBox[0]), px(movedInk[0] - restInk[0]), 0.05f);
        assertEquals(px(movedBox[1] - restBox[1]), px(movedInk[1] - restInk[1]), 0.05f);
    }
}
