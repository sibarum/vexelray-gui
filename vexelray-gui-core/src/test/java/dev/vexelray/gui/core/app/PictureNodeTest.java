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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drawing on a node ({@code PropKey.PICTURE}) — asserted in the vertices, because that is where the two claims
 * that make it usable have to be true: a picture is drawn <b>in the node's box</b>, and it cannot escape it.
 *
 * <p>The second is not a nicety. A picture is authored geometry with no measure pass behind it: an application
 * computes marks from data, and the numbers it computes are as right as its arithmetic. Everywhere else in the
 * framework a box's contents were laid out by the framework, so "it stays inside" is an invariant of the layout;
 * here it is a clip, and a clip that is missing looks like nothing at all until the day the data is wider than
 * the axis.
 *
 * <p>No layout runs. The renderer reads computed geometry off {@link RetainedNode} and resolves nothing of its
 * own, so a hand-placed tree is the whole input — and it is also how "not layout-affecting" gets asserted at all.
 */
class PictureNodeTest {

    private static final Color MARK = Color.rgba(1f, 0f, 0f, 1f);
    private static final Color PANEL = Color.rgba(0f, 0f, 1f, 1f);

    private static long nextId = 1;

    /** A 100x50 box at {@code (x, y)} carrying a background, and whatever else the caller sets. */
    private static RetainedNode box(float x, float y) {
        RetainedNode n = new RetainedNode(nextId++, NodeKind.BOX);
        n.set(PropKey.BACKGROUND, PANEL);
        n.x = x;
        n.y = y;
        n.w = 100f;
        n.h = 50f;
        return n;
    }

    private static RetainedNode child(RetainedNode parent, RetainedNode c) {
        c.parent = parent;
        parent.children.add(c);
        return c;
    }

    private static float[] emit(RetainedNode root) {
        Canvas canvas = new Canvas(400, 200).begin();
        TreeRenderer.emit(root, canvas, new TextLayout[]{null});
        return Arrays.copyOf(canvas.toVertexArray(), canvas.vertexCount() * CanvasVertex.FLOATS_PER_VERTEX);
    }

    /** One float of every vertex drawn in {@code colour}, at {@code offset} floats into the vertex. */
    private static List<Float> of(float[] data, Color colour, int offset) {
        List<Float> out = new ArrayList<>();
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + rgba] == colour.r() && data[v + rgba + 1] == colour.g()
                    && data[v + rgba + 2] == colour.b()) {
                out.add(data[v + offset]);
            }
        }
        return out;
    }

    /**
     * The leftmost screen x a {@code colour} was drawn at — raw px, as the clip SDF sees them.
     *
     * <p>Every quad extends the same antialiasing pad past its rect, so these are read as <em>differences</em>
     * against the panel that was drawn from the same box: a difference cancels the pad exactly, where an absolute
     * position would carry it and pin a constant that belongs to the engine.
     */
    private static float leftOf(float[] data, Color colour) {
        return min(of(data, colour, CanvasVertex.OFF_CLIPRS / Float.BYTES));
    }

    private static float topOf(float[] data, Color colour) {
        return min(of(data, colour, CanvasVertex.OFF_CLIPRS / Float.BYTES + 1));
    }

    /** The clip box stamped on the first mark vertex: {@code {cx, cy, halfW, halfH}}. */
    private static float[] clipOf(float[] data) {
        int c = CanvasVertex.OFF_CLIPBOX / Float.BYTES;
        return new float[]{of(data, MARK, c).get(0), of(data, MARK, c + 1).get(0),
                of(data, MARK, c + 2).get(0), of(data, MARK, c + 3).get(0)};
    }

    private static Picture dot(double x, double y) {
        return new Sketch().fill(x, y, 4, 4, MARK).picture();
    }

    @Test
    void aMarkAtTheOriginIsDrawnAtTheBoxsTopLeftCorner() {
        // The whole of the coordinate contract: (0, 0) is the box, not the window. An application draws against
        // the size it measured and never learns where the box ended up.
        RetainedNode n = box(120f, 60f);
        n.set(PropKey.PICTURE, dot(0, 0));

        float[] data = emit(n);
        assertEquals(0f, leftOf(data, MARK) - leftOf(data, PANEL), 0.01f,
                "the mark starts where the box does, whatever the box's own position is");
        assertEquals(0f, topOf(data, MARK) - topOf(data, PANEL), 0.01f);
    }

    @Test
    void aDrawingIsClippedToTheBoxItIsIn() {
        // A mark computed off the end of an axis is trimmed rather than painted over the neighbouring panel.
        RetainedNode n = box(0f, 0f);
        n.set(PropKey.PICTURE, dot(0, 0));

        float[] clip = clipOf(emit(n));
        assertEquals(50f, clip[0], 0.01f, "clip centre x = the box's own centre");
        assertEquals(25f, clip[1], 0.01f);
        assertEquals(50f, clip[2], 0.01f, "and its half-extents are the box's");
        assertEquals(25f, clip[3], 0.01f);
    }

    @Test
    void theClipTravelsWithTheNodeUnderASubtreeTranslate() {
        // The failure this exists to catch: a canvas clip is in screen coordinates and a node's box is not, so a
        // clip pushed at the box's own numbers stays where the node *would* have been. Mid-slide, that shears the
        // drawing off against a rectangle nothing is being drawn in — invisible at both endpoints of the
        // transition, and obvious for the 160ms in between.
        RetainedNode root = box(0f, 0f);
        RetainedNode panel = child(root, box(0f, 0f));
        panel.emPx = 20f;
        panel.set(PropKey.TRANSLATE_X, 2f);      // 2 em at 20px = 40px right
        panel.set(PropKey.PICTURE, dot(0, 0));

        float[] data = emit(root);
        assertEquals(40f, leftOf(data, MARK) - leftOf(data, PANEL), 0.01f, "the mark travelled with its node");
        assertEquals(90f, clipOf(data)[0], 0.01f, "and so did the rectangle it is clipped against");
    }

    @Test
    void everyMarkFadesWithTheSubtreeItIsIn() {
        RetainedNode root = box(0f, 0f);
        RetainedNode panel = child(root, box(0f, 0f));
        panel.set(PropKey.OPACITY, 0.5f);
        panel.set(PropKey.PICTURE, dot(0, 0));

        List<Float> alphas = of(emit(root), MARK, CanvasVertex.OFF_COLOR / Float.BYTES + 3);
        assertTrue(!alphas.isEmpty(), "the mark should have been drawn at all");
        assertEquals(List.of(0.5f), alphas.stream().distinct().toList(),
                "a picture in a fading subtree fades with it, like every other colour the renderer paints");
    }

    @Test
    void anEmptyOrAbsentPictureCostsNothingAndPushesNoClip() {
        // Worth pinning because the renderer runs this branch for every node in the tree, every frame.
        RetainedNode bare = box(0f, 0f);
        RetainedNode empty = box(0f, 0f);
        empty.set(PropKey.PICTURE, Picture.EMPTY);

        assertEquals(vertexCount(emit(bare)), vertexCount(emit(empty)),
                "an empty picture draws exactly what the box would have drawn anyway");
    }

    @Test
    void aDrawingDoesNotTouchTheLaidOutBox() {
        // Not layout-affecting, by the same argument as an image: a drawing is a box that paints, so it takes its
        // size from the box it already is and putting one on a node moves nothing.
        RetainedNode n = box(10f, 10f);
        n.set(PropKey.PICTURE, new Sketch().fill(0, 0, 4000, 4000, MARK).picture());
        emit(n);

        assertEquals(100f, n.w, 0.01f, "a mark far wider than the box does not widen it — it is clipped");
        assertEquals(50f, n.h, 0.01f);
        assertTrue(!PropKey.PICTURE.layoutAffecting(), "and changing one must not retrigger layout");
    }

    @Test
    void aDrawingGoesOverTheBackgroundAndUnderTheBorder() {
        // Paint order is submission order, so this is asserted as the order the three colours appear in the one
        // buffer: an application's marks belong inside the frame the node draws around them.
        RetainedNode n = box(0f, 0f);
        n.borderPx = 2f;
        n.set(PropKey.BORDER_COLOR, Color.rgba(0f, 1f, 0f, 1f));
        n.set(PropKey.PICTURE, dot(0, 0));

        float[] data = emit(n);
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        StringBuilder order = new StringBuilder();
        char last = 0;
        for (int v = 0; v + stride <= data.length; v += stride) {
            char c = data[v + rgba] == 1f ? 'm' : data[v + rgba + 1] == 1f ? 'b' : 'p';
            if (c != last) {
                order.append(c);
                last = c;
            }
        }
        assertEquals("pmb", order.toString(), "panel, then the drawing, then the border framing it");
    }

    private static int vertexCount(float[] data) {
        return data.length / CanvasVertex.FLOATS_PER_VERTEX;
    }

    private static float min(List<Float> values) {
        float m = Float.POSITIVE_INFINITY;
        for (float v : values) {
            m = Math.min(m, v);
        }
        return m;
    }
}
