package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node drawn out from under its ancestors' clips ({@code PropKey.UNCLIP}) — asserted in the vertices, because
 * every claim it makes is about <b>which mask</b> a draw carries and <b>when</b> it is submitted, and neither is
 * visible anywhere else.
 *
 * <p>The arrangement throughout is the one the feature exists for: a name wider than the column that clips it,
 * with a sibling column to its right drawn over where the rest of the name would have been.
 */
class UnclipNodeTest {

    private static final Color COLUMN = Color.rgba(0f, 0f, 1f, 1f);    // the clipping column's own surface
    private static final Color NAME = Color.rgba(1f, 0f, 0f, 1f);      // the name, wider than its column
    private static final Color NEXT = Color.rgba(0f, 1f, 0f, 1f);      // the column beside it

    private static long nextId = 1;

    private static RetainedNode box(Color bg, float x, float w) {
        RetainedNode n = new RetainedNode(nextId++);
        n.set(PropKey.BACKGROUND, bg);
        n.x = x;
        n.y = 0f;
        n.w = w;
        n.h = 20f;
        n.emPx = 16f;
        return n;
    }

    private record Row(RetainedNode root, RetainedNode column, RetainedNode name, RetainedNode next) { }

    /** A 100px column that clips a 250px name, and a column beside it from 100 to 300. */
    private static Row row() {
        RetainedNode root = box(null, 0f, 300f);
        RetainedNode column = box(COLUMN, 0f, 100f);
        column.set(PropKey.CLIP, true);
        RetainedNode name = box(NAME, 0f, 250f);
        RetainedNode next = box(NEXT, 100f, 200f);
        adopt(root, column);
        adopt(column, name);
        adopt(root, next);
        return new Row(root, column, name, next);
    }

    private static void adopt(RetainedNode parent, RetainedNode child) {
        child.parent = parent;
        parent.children.add(child);
    }

    private static float[] emit(RetainedNode root) {
        Canvas canvas = Canvases.core(400, 200);
        TreeRenderer.emit(root, canvas, dev.vexelray.gui.core.text.TextFaces.standard());
        return Arrays.copyOf(canvas.toVertexArray(), canvas.vertexCount() * CanvasVertex.FLOATS_PER_VERTEX);
    }

    /**
     * One draw of one colour: the run of consecutive vertices in it, with the alpha it carried and the mask it was
     * drawn through, as the canvas stamps it — a centre and half-extents.
     */
    private record Draw(Color colour, float alpha, float clipX, float clipY, float clipHalfWidth, float clipHalfHeight) {

        /** Whether this draw's mask lets any of it land inside {@code (l, t)–(r, b)}. */
        boolean reaches(float l, float t, float r, float b) {
            return clipX - clipHalfWidth < r && clipX + clipHalfWidth > l
                    && clipY - clipHalfHeight < b && clipY + clipHalfHeight > t;
        }

        float clipLeft() {
            return clipX - clipHalfWidth;
        }

        float clipRight() {
            return clipX + clipHalfWidth;
        }
    }

    /** Whether a draw could reach into the column, where the name is already on the screen. */
    private static boolean intoColumn(Draw d) {
        return d.reaches(0f, 0f, 100f, 20f);
    }

    private static List<Draw> draws(float[] data) {
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int rgba = CanvasVertex.OFF_COLOR / Float.BYTES;
        int clip = CanvasVertex.OFF_CLIPBOX / Float.BYTES;
        List<Draw> out = new ArrayList<>();
        Draw last = null;
        for (int v = 0; v + stride <= data.length; v += stride) {
            Color c = Color.rgba(data[v + rgba], data[v + rgba + 1], data[v + rgba + 2], 1f);
            Draw d = new Draw(c, data[v + rgba + 3], data[v + clip], data[v + clip + 1], data[v + clip + 2],
                    data[v + clip + 3]);
            if (!d.equals(last)) {   // a new colour, mask or opacity is a new draw, even of the same node
                out.add(d);
                last = d;
            }
        }
        return out;
    }

    private static List<Draw> of(List<Draw> draws, Color colour) {
        return draws.stream().filter(d -> d.colour().equals(colour)).toList();
    }

    @Test
    void withoutItANameIsDrawnOnceAndCutByItsColumn() {
        Row row = row();

        List<Draw> name = of(draws(emit(row.root())), NAME);

        assertEquals(1, name.size(), "drawn where it is, and nowhere else");
        assertEquals(50f, name.get(0).clipHalfWidth(), 0.01f, "inside its column's 100px mask");
    }

    /**
     * The whole point: the part the column cut off is drawn, after the column beside it that covered it — out from
     * under the column's mask, the rest of the way to the name's end.
     */
    @Test
    void anUnclippedNameIsDrawnAgainOverItsNeighbour() {
        Row row = row();
        row.name().set(PropKey.UNCLIP, 1f);

        List<Draw> all = draws(emit(row.root()));
        List<Draw> name = of(all, NAME);

        assertEquals(50f, name.get(0).clipHalfWidth(), 0.01f, "the ordinary draw is still clipped as before");
        Draw overhang = name.stream().skip(1).filter(d -> d.reaches(100f, 0f, 250f, 20f)).findFirst().orElseThrow();
        assertTrue(overhang.clipLeft() <= 100f && overhang.clipRight() >= 250f,
                "drawn again over the whole of what the column cut off");
        assertTrue(all.lastIndexOf(overhang) > all.indexOf(of(all, NEXT).get(0)),
                "and after the column beside it, so it is on top of what covered the rest of it");
    }

    /** The plate is the surface the name sat on, carried out with it — not a colour of the renderer's choosing. */
    @Test
    void thePlateIsTheSurfaceTheNodeSatOn() {
        Row row = row();
        row.name().set(PropKey.UNCLIP, 1f);

        List<Draw> all = draws(emit(row.root()));
        List<Draw> column = of(all, COLUMN);

        int lastName = all.lastIndexOf(of(all, NAME).get(of(all, NAME).size() - 1));
        assertTrue(column.size() >= 2, "the column's surface is drawn again, as the plate");
        Draw plate = column.get(column.size() - 1);
        assertTrue(all.lastIndexOf(plate) < lastName, "under the name");
        assertTrue(all.lastIndexOf(plate) > all.indexOf(of(all, NEXT).get(0)), "and over the neighbour");
        assertTrue(plate.reaches(100f, 0f, 250f, 20f), "and out from under the clip like the name");
    }

    /** It fades in by its own amount, on top of whatever opacity the node inherited. */
    @Test
    void theSecondDrawFadesByTheAmountTimesWhatItInherited() {
        Row row = row();
        row.column().set(PropKey.OPACITY, 0.5f);
        row.name().set(PropKey.UNCLIP, 0.5f);

        List<Draw> name = of(draws(emit(row.root())), NAME);

        assertEquals(0.5f, name.get(0).alpha(), 0.001f, "the ordinary draw at what it inherited");
        assertEquals(0.25f, name.get(1).alpha(), 0.001f, "the unclipped one at that, times its own amount");
    }

    /** Drawn under no ancestor's clip, but its own clip still holds for its own children. */
    @Test
    void itsOwnClipStillHoldsForItsChildren() {
        Row row = row();
        row.name().set(PropKey.CLIP, true);
        RetainedNode inner = box(NEXT, 0f, 400f);
        adopt(row.name(), inner);
        row.name().set(PropKey.UNCLIP, 1f);

        List<Draw> innerDraws = of(draws(emit(row.root())), NEXT);
        Draw last = innerDraws.get(innerDraws.size() - 1);

        assertEquals(250f, last.clipRight(), 0.01f, "the lifted name still masks its own child to its box");
    }

    @Test
    void itIsNotALayoutInput() {
        assertFalse(PropKey.UNCLIP.layoutAffecting());
        assertFalse(PropKey.UNCLIP.geometryAffecting());
    }

    /**
     * Where the name was already on the screen, the unclip draws neither the name nor the plate's fill — at any
     * amount. The finished unclip is the same pixels there as the ordinary draw, so the exact fade there is no
     * change; drawing the plate over the name and the name over that, each at the fade's opacity, dimmed it at
     * the start of every fade, and drawing the name over itself thickened it.
     */
    @Test
    void nothingIsDrawnOverTheNameWhereItWasAlreadySeen() {
        for (float amount : new float[] {0.25f, 0.5f, 1f}) {
            Row row = row();
            row.name().set(PropKey.UNCLIP, amount);

            List<Draw> all = draws(emit(row.root()));
            List<Draw> name = of(all, NAME);
            List<Draw> column = of(all, COLUMN);

            assertTrue(name.stream().skip(1).noneMatch(UnclipNodeTest::intoColumn),
                    "at " + amount + ", no lifted draw of the name reaches into its column");
            assertTrue(column.stream().skip(1).noneMatch(UnclipNodeTest::intoColumn),
                    "and no plate fill does either");
            assertTrue(name.stream().skip(1).anyMatch(d -> d.reaches(100f, 0f, 250f, 20f)),
                    "while the overhang is drawn");
        }
    }

    /** The plate's edge is still drawn round its whole outline, the part over the column included. */
    @Test
    void thePlatesEdgeStillGoesRoundTheWholeName() {
        Row row = row();
        row.name().set(PropKey.UNCLIP, 0.5f);

        List<Draw> all = draws(emit(row.root()));
        int afterNeighbour = all.indexOf(of(all, NEXT).get(0));
        boolean edgeInColumn = all.stream().skip(afterNeighbour + 1)
                .filter(d -> !d.colour().equals(NAME) && !d.colour().equals(COLUMN) && !d.colour().equals(NEXT))
                .anyMatch(d -> d.clipX() == 50f && d.clipHalfWidth() == 50f);
        assertTrue(edgeInColumn, "something of the plate's own is drawn through the column's mask: its edge");
    }
}
