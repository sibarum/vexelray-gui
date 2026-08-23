package dev.vexelray.gui.typeset;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.AtlasData;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P4 gate (docs/typeset.md §10): the draw list becomes a subtree of floating nodes, the container bounds it
 * exactly, node counts stay within budget, and a headless frame renders.
 *
 * <p>These run against a <b>real {@link Gui}</b> with no window and no Vulkan — the payoff of the framework
 * building its tree through mutations rather than through a renderer. What is asserted is the geometry core
 * actually computed, read back off the retained tree, not what the projection intended.
 */
class ProjectionTest {

    private static final Profile P = Profile.math();
    private static final AtlasData ATLAS = AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json");
    private static final Typeset ENGINE = new Typeset(ATLAS, P, FaceKeys.single());

    /**
     * A text measurer that answers with a number no real glyph could produce. The projection sets an explicit
     * width and height on every node it creates, so nothing should ever consult this — and if something starts
     * to, the geometry assertions below break loudly instead of the block quietly acquiring a dependency on the
     * application having supplied a measurer that agrees with the atlas.
     */
    private static final TextMeasurer NEVER_CALLED = (node, axis, px) -> axis == Axis.HORIZONTAL ? 9999f : 9999f;

    // --- the gate ------------------------------------------------------------------------------------------------

    @Test
    void theContainerExactlyBoundsTheProjection() {
        try (Harness h = new Harness()) {
            TypesetBlock block = h.show(Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b")));
            RetainedNode container = h.frameAndFind(block);
            List<RetainedNode> drawn = container.children;
            assertTrue(drawn.size() >= 3, "a fraction projects a numerator, a bar and a denominator");

            double top = Double.POSITIVE_INFINITY;
            double bottom = Double.NEGATIVE_INFINITY;
            for (RetainedNode c : drawn) {
                assertTrue(c.x >= container.x - 1e-4 && c.x + c.w <= container.x + container.w + 1e-4,
                        "every projected node is inside the container horizontally");
                assertTrue(c.y >= container.y - 1e-4 && c.y + c.h <= container.y + container.h + 1e-4,
                        "and vertically");
                top = Math.min(top, c.y);
                bottom = Math.max(bottom, c.y + c.h);
            }

            // Vertically the container is tight to the projection: slack there would be a block reserving height
            // it never draws in, which is the one thing an atom in flex layout must not do.
            assertEquals(container.y, top, 1e-4, "the container's top edge is the topmost box");
            assertEquals(container.y + container.h, bottom, 1e-4, "and its bottom the lowest");

            // Horizontally the bound is the block's advance, which may legitimately exceed what is drawn — see
            // theInkBoxWouldNotHaveBeenALegalContainer for the case that separates the two.
            assertEquals(block.placed().width(), container.w, 1e-3,
                    "and its width is the block's advance, not the extent of its marks");
        }
    }

    @Test
    void theInkBoxWouldNotHaveBeenALegalContainer() {
        // The finding P4 exists to catch, kept as a test so the reasoning cannot be lost. Sizing the container to
        // the draw list's ink — what §8 originally specified — puts a glyph's box top above the container, and
        // core clamps a floating child back inside (FlexLayout.placeFloating), so the whole block would slide
        // down by the difference rather than fail visibly.
        try (Harness h = new Harness()) {
            TypesetBlock block = h.show(Recipes.script(P, Recipes.variable("x"), Recipes.number("2"), null));
            Placed ink = block.placed();
            Placed.Glyphs highest = null;
            for (Placed.Draw d : ink.draws()) {
                if (d instanceof Placed.Glyphs g && (highest == null || g.y() < highest.y())) {
                    highest = g;
                }
            }
            assertNotNull(highest);

            double boxTopUnderInkSizing = ink.ascent() + highest.y() - ENGINE.ascenderOf(highest.face()) * highest.size();
            assertTrue(boxTopUnderInkSizing < 0,
                    "a glyph's box top sits above an ink-sized container, at " + boxTopUnderInkSizing + "px");

            // And what shipped instead: the line-box union vertically, the block's advance horizontally.
            RetainedNode container = h.frameAndFind(block);
            assertEquals(ink.width(), container.w, 1e-3, "the width is the block's advance");
            assertTrue(container.h > ink.height(),
                    "and only the height grows, by the leading a line box carries and ink does not");

            // The other half of the same story, and the reason the width is not the drawn union either: x²
            // reserves scriptGapAfter past its exponent and draws nothing in it. Clipping to the marks would
            // delete that trailing advance and let a neighbour sit too close.
            double drawnRight = 0;
            for (Placed.Draw d : ink.draws()) {
                if (d instanceof Placed.Glyphs g) {
                    drawnRight = Math.max(drawnRight, g.x() + ENGINE.advanceOf(g.face(), g.text()) * g.size());
                }
            }
            assertEquals(P.metrics().scriptGapAfter() * 16.0, container.w - drawnRight, 1e-3,
                    "the container is exactly one scriptGapAfter wider than anything it draws");
        }
    }

    @Test
    void aBlockIsOneNodePerDrawAndNothingElse() {
        try (Harness h = new Harness()) {
            Box matrix = Recipes.matrix(P, List.of(
                    List.of(Recipes.variable("a"), Recipes.variable("b")),
                    List.of(Recipes.variable("c"), Recipes.variable("d"))), "[", "]");
            TypesetBlock block = h.show(matrix);
            RetainedNode container = h.frameAndFind(block);

            assertEquals(block.placed().draws().size(), container.children.size(),
                    "the budget is one node per draw — no wrappers, no spacers, no grouping boxes");
            for (RetainedNode c : container.children) {
                assertTrue(c.children.isEmpty(), "and each is a leaf");
            }
        }
    }

    @Test
    void aFrameRendersHeadlessAndEveryNodeIsLaidOut() {
        try (Harness h = new Harness()) {
            TypesetBlock block = h.show(Recipes.radical(P, Recipes.variable("x"), Recipes.number("3")));
            assertNotNull(h.gui.frame(Harness.W, Harness.H, NEVER_CALLED), "a frame renders with no window");
            RetainedNode container = h.find(h.gui.frame(Harness.W, Harness.H, NEVER_CALLED), block);

            assertTrue(container.w > 0 && container.h > 0, "the block occupies real space");
            for (RetainedNode c : container.children) {
                assertTrue(h.gui.layoutSnapshot().node(c.id).present(),
                        "every projected node reaches the layout read-model");
                assertTrue(c.w > 0 && c.h > 0, "with a real box: " + c.kind);
            }
        }
    }

    // --- rebuilds ------------------------------------------------------------------------------------------------

    @Test
    void changingContentReplacesTheSubtreeWithNothingLeftBehind() {
        try (Harness h = new Harness()) {
            TypesetBlock block = h.show(Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b")));
            int before = h.frameAndFind(block).children.size();

            block.content(Recipes.variable("x"));
            RetainedNode after = h.frameAndFind(block);

            assertTrue(before > 1, "the fraction projected several nodes");
            assertEquals(1, after.children.size(), "and a lone run leaves exactly one — the rest are gone");
            assertEquals(block.placed().draws().size(), after.children.size());
        }
    }

    @Test
    void densityIsAPureScaleAndZoomIsNot() {
        // The §4.3 story, end to end. Density never reaches the solve — the block emits dp and core multiplies it
        // in afterwards — so doubling it doubles every coordinate exactly. Zoom is inside the basis the tone map
        // solves against, so doubling it re-solves against a floor that has not moved, and a block deep enough to
        // be compressed does not simply double. That difference is the mechanism working, not an inconsistency.
        try (Harness h = new Harness()) {
            Box deep = Recipes.script(P, Recipes.variable("x"),
                    Recipes.script(P, Recipes.variable("y"),
                            Recipes.script(P, Recipes.variable("z"), Recipes.number("2"), null), null), null);
            TypesetBlock block = h.show(deep);
            float base = h.frameAndFind(block).w;

            h.gui.dpi(2f);
            assertEquals(2 * base, h.frameAndFind(block).w, 1e-3,
                    "density is applied once, by dp, after the solve");

            h.gui.dpi(1f);
            h.gui.zoom(2f);
            float zoomed = h.frameAndFind(block).w;
            assertTrue(zoomed > base, "zooming in makes the block bigger");
            assertTrue(Math.abs(zoomed - 2 * base) > 1e-3,
                    "but not by exactly two: the legibility floor is physical, so the map re-solves");
        }
    }

    @Test
    void aFlatBlockScalesTheSameWayUnderBoth() {
        // The control for the test above: with nothing nested there is no range to compress, so the tone map is
        // the identity and zoom and density agree. If they ever disagreed here, one of them would be applied
        // twice — which is exactly what emitting em instead of dp would do.
        try (Harness h = new Harness()) {
            TypesetBlock block = h.show(Recipes.variable("x"));
            float base = h.frameAndFind(block).w;

            h.gui.zoom(2f);
            float zoomed = h.frameAndFind(block).w;
            h.gui.zoom(1f);
            h.gui.dpi(2f);
            float dense = h.frameAndFind(block).w;

            assertEquals(2 * base, zoomed, 1e-3, "zoom doubles a flat block");
            assertEquals(2 * base, dense, 1e-3, "and so does density");
        }
    }

    @Test
    void aBlockIsPointerTransparentAndNeverStealsAHover() {
        try (Harness h = new Harness()) {
            TypesetBlock block = h.show(Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b")));
            for (RetainedNode c : h.frameAndFind(block).children) {
                assertTrue(c.hitInert(), "a read-only block's glyphs are not pointer targets");
            }
        }
    }

    // --- harness -------------------------------------------------------------------------------------------------

    /** A headless GUI: no window, no Vulkan, handlers inline, and a text measurer that must never be consulted. */
    private static final class Harness implements AutoCloseable {

        static final float W = 800f;
        static final float H = 600f;

        final Atchung bus = Atchung.create();
        final Gui gui = new Gui(bus, Runnable::run);
        private final List<TypesetBlock> blocks = new ArrayList<>();

        TypesetBlock show(Box content) {
            TypesetBlock block = new TypesetBlock(gui, ENGINE, content);
            blocks.add(block);
            gui.root().append(block.node());
            return block;
        }

        RetainedNode frameAndFind(TypesetBlock block) {
            return find(gui.frame(W, H, NEVER_CALLED), block);
        }

        RetainedNode find(RetainedNode root, TypesetBlock block) {
            RetainedNode found = search(root, block.node().id());
            assertNotNull(found, "the block's container is in the retained tree");
            assertEquals(NodeKind.BOX, found.kind);
            return found;
        }

        private static RetainedNode search(RetainedNode n, long id) {
            if (n == null) {
                return null;
            }
            if (n.id == id) {
                return n;
            }
            for (RetainedNode c : n.children) {
                RetainedNode hit = search(c, id);
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        }

        @Override
        public void close() {
            for (TypesetBlock b : blocks) {
                b.close();
            }
            gui.close();
        }
    }
}
