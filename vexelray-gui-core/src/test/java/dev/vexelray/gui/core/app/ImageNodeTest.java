package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.SampledImage;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * An image on a node, and which part of its texture the node samples ({@code PropKey.IMAGE_REGION}) — asserted in
 * the vertices, because the claim that makes a sprite sheet worth having lives there: <b>a region changes four
 * texture coordinates and nothing else.</b>
 *
 * <p>Not the geometry, so a node does not move when its frame advances. Not the bound handle, so a sheet's cells
 * stay in one run and one draw — which is the whole reason an animation costs nothing on the GPU here. Both are
 * invisible in a screenshot and obvious in a vertex buffer, so this is where they are checked.
 *
 * <p>The UVs are read as bounds rather than exact numbers on purpose. {@code Canvas} extrapolates them past the
 * box by its AA pad, so the emitted extremes are not the region's own — but the <em>centre</em> of the extremes is
 * (the extrapolation is symmetric), and so is the <em>ratio</em> of two spans on one box. Both are pad-free, so
 * these assertions pin the region without restating a private constant of the engine.
 */
class ImageNodeTest {

    private static final int W = 400;
    private static final int H = 200;

    private static long nextId = 1;

    /** A handle the renderer only passes through — the model describes a tree, not a GPU. */
    private record FakeImage(long id) implements SampledImage {
        @Override
        public long descriptorSet() {
            return id;
        }

        @Override
        public long descriptorSetLayout() {
            return id;
        }

        /** No device: these tests describe a tree, and nothing here binds anything. */
        @Override
        public dev.vexelray.vulkan.vk.VulkanDevice device() {
            return null;
        }
    }

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** A 100x50 box at {@code (x, y)} with no background, so the only vertices emitted are the image's. */
    private static RetainedNode box(float x, float y) {
        RetainedNode n = new RetainedNode(nextId++);
        n.x = x;
        n.y = y;
        n.w = 100f;
        n.h = 50f;
        return n;
    }

    private static Canvas draw(RetainedNode root) {
        Canvas canvas = new Canvas(W, H).begin();
        TreeRenderer.emit(root, canvas, new TextLayout[]{null});
        return canvas;
    }

    private static float[] vertices(Canvas canvas) {
        return Arrays.copyOf(canvas.toVertexArray(), canvas.vertexCount() * CanvasVertex.FLOATS_PER_VERTEX);
    }

    /** One image node's vertices, as {@code {uMin, vMin, uMax, vMax}}. */
    private static float[] uvBounds(float[] data) {
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int uv = CanvasVertex.OFF_UV / Float.BYTES;
        int kind = CanvasVertex.OFF_KIND / Float.BYTES;
        float uMin = Float.POSITIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY;
        float uMax = Float.NEGATIVE_INFINITY;
        float vMax = Float.NEGATIVE_INFINITY;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + kind] != CanvasVertex.KIND_IMAGE) {
                continue;
            }
            uMin = Math.min(uMin, data[v + uv]);
            uMax = Math.max(uMax, data[v + uv]);
            vMin = Math.min(vMin, data[v + uv + 1]);
            vMax = Math.max(vMax, data[v + uv + 1]);
        }
        return new float[]{uMin, vMin, uMax, vMax};
    }

    /** Every image vertex's screen position, in submission order. */
    private static float[] positions(float[] data) {
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int pos = CanvasVertex.OFF_POS / Float.BYTES;
        int kind = CanvasVertex.OFF_KIND / Float.BYTES;
        float[] out = new float[data.length / stride * 2];
        int at = 0;
        for (int v = 0; v + stride <= data.length; v += stride) {
            if (data[v + kind] != CanvasVertex.KIND_IMAGE) {
                continue;
            }
            out[at++] = data[v + pos];
            out[at++] = data[v + pos + 1];
        }
        return Arrays.copyOf(out, at);
    }

    private static float[] uvOf(ImageRegion region) {
        RetainedNode n = box(0f, 0f);
        n.set(PropKey.IMAGE, new FakeImage(1));
        if (region != null) {
            n.set(PropKey.IMAGE_REGION, region);
        }
        return uvBounds(vertices(draw(n)));
    }

    private static float centre(float min, float max) {
        return (min + max) / 2f;
    }

    // --- the coordinates ----------------------------------------------------------------------------------

    @Test
    void anImageWithNoRegionSamplesTheWholeTexture() {
        float[] uv = uvOf(null);

        assertEquals(0.5f, centre(uv[0], uv[2]), 1e-5f, "centred on the middle of the texture");
        assertEquals(0.5f, centre(uv[1], uv[3]), 1e-5f);
    }

    @Test
    void aCellIsCentredOnItsOwnPartOfTheSheet() {
        float[] topRight = uvOf(ImageRegion.cell(1, 2, 2));
        float[] bottomLeft = uvOf(ImageRegion.cell(2, 2, 2));

        assertEquals(0.75f, centre(topRight[0], topRight[2]), 1e-5f, "cell 1 of a 2x2 sheet is the right half");
        assertEquals(0.25f, centre(topRight[1], topRight[3]), 1e-5f, "...and the top half");
        assertEquals(0.25f, centre(bottomLeft[0], bottomLeft[2]), 1e-5f, "cell 2 is the left half");
        assertEquals(0.75f, centre(bottomLeft[1], bottomLeft[3]), 1e-5f, "...and the bottom half");
    }

    @Test
    void aHalfWideCellCoversHalfAsManyTextureCoordinates() {
        float[] whole = uvOf(ImageRegion.WHOLE);
        float[] half = uvOf(ImageRegion.cell(0, 2, 1));

        assertEquals(2f, (whole[2] - whole[0]) / (half[2] - half[0]), 1e-4f,
                "half the sheet must span half the coordinates, pad and all");
        assertEquals(1f, (whole[3] - whole[1]) / (half[3] - half[1]), 1e-4f,
                "and the axis that was not divided must not have moved");
    }

    // --- what a region must not change --------------------------------------------------------------------

    @Test
    void aRegionMovesNoGeometry() {
        RetainedNode whole = box(30f, 20f);
        whole.set(PropKey.IMAGE, new FakeImage(1));
        RetainedNode celled = box(30f, 20f);
        celled.set(PropKey.IMAGE, new FakeImage(1));
        celled.set(PropKey.IMAGE_REGION, ImageRegion.cell(3, 4, 4));

        assertArrayEquals(positions(vertices(draw(whole))), positions(vertices(draw(celled))), 0f,
                "advancing a frame must not nudge the node it is drawn in");
    }

    @Test
    void twoCellsOfOneSheetAreOneRunAndOneDraw() {
        SampledImage sheet = new FakeImage(7);
        RetainedNode root = box(0f, 0f);
        for (int cell = 0; cell < 2; cell++) {
            RetainedNode child = box(cell * 100f, 0f);
            child.set(PropKey.IMAGE, sheet);
            child.set(PropKey.IMAGE_REGION, ImageRegion.cell(cell, 2, 1));
            child.parent = root;
            root.children.add(child);
        }

        Canvas canvas = draw(root);
        List<Canvas.Run> runs = canvas.runs();

        assertEquals(1, runs.size(),
                "the bound handle never changed, so a second cell costs no rebind and no second draw");
        assertSame(sheet, runs.get(0).image());
        assertEquals(12, runs.get(0).vertexCount(), "two quads, six vertices each");
    }

    @Test
    void twoDifferentTexturesDoCostARun() {
        RetainedNode root = box(0f, 0f);
        for (int i = 0; i < 2; i++) {
            RetainedNode child = box(i * 100f, 0f);
            child.set(PropKey.IMAGE, new FakeImage(i + 1));
            child.parent = root;
            root.children.add(child);
        }

        assertEquals(2, draw(root).runs().size(),
                "the counterpart claim: it is the handle that splits a run, so a sheet is worth packing");
    }

    // --- the prop pair ------------------------------------------------------------------------------------

    @Test
    void settingAnImageWithNoRegionClearsAStaleOne() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node node = gui.box();
            gui.root().children(node);
            node.image(new FakeImage(1), ImageRegion.cell(2, 2, 2));
            assertNotNull(find(gui.frame(W, H, ImageNodeTest::noText), node.id()).imageRegion());

            node.image(new FakeImage(2));

            assertNull(find(gui.frame(W, H, ImageNodeTest::noText), node.id()).imageRegion(),
                    "a node must never be left sampling last texture's cell of a new one");
        }
    }

    @Test
    void clearingTheImageClearsTheRegionWithIt() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node node = gui.box();
            gui.root().children(node);
            node.image(new FakeImage(1), ImageRegion.cell(1, 2, 2));

            node.image(null, ImageRegion.cell(3, 2, 2));

            RetainedNode retained = find(gui.frame(W, H, ImageNodeTest::noText), node.id());
            assertNull(retained.image());
            assertNull(retained.imageRegion(), "a region of nothing is not a thing a node can hold");
        }
    }

    private static RetainedNode find(RetainedNode root, long id) {
        if (root.id == id) {
            return root;
        }
        for (RetainedNode child : root.children) {
            RetainedNode hit = find(child, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
