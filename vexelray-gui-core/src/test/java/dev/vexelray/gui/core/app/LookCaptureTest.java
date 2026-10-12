package dev.vexelray.gui.core.app;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Layers;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.PaintContext;
import dev.vexelray.canvas.shadelet.Shadelet;
import dev.vexelray.canvas.shadelet.StyleLayout;
import dev.vexelray.canvas.shadelet.StyleView;
import dev.vexelray.gui.core.text.TextFaces;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.clamp;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.w;
import static dev.vexelray.ir.Ir.x;
import static dev.vexelray.ir.Ir.xyz;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A look an application composed draws in a headless capture, with each element's own values: the whole path a
 * look takes outside the canvas, from the composed shader the capture builds its pipeline with, through the look
 * table and the frame's records in the tables buffer, to the pixels.
 *
 * <p>Two boxes of one look, differing only in their per-instance colour. Each must come out in its own: a record
 * not uploaded reads as zero, which names the first core look and draws the box in its white vertex colour, and
 * records uploaded at the wrong place give both boxes one colour.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class LookCaptureTest {

    private static final int W = 200;
    private static final int H = 100;

    /** Fills the shape in a colour each element carries in its record. */
    static final Shadelet.Painter TINT = new Shadelet.Painter() {
        @Override
        public String name() {
            return "testTint";
        }

        @Override
        public StyleLayout style() {
            return StyleLayout.of(StyleLayout.perInstance("colour", 4));
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr colour = style.vector("colour");
            Expr cov = clamp(add(x(c.distance()), f(0.5)), f(0.0), f(1.0));
            return Region.of(new Statement.Assign(layer, Layers.premultiplied(xyz(colour), mul(w(colour), cov))));
        }
    };

    /** The core set and the tint, as an application would compose its own. */
    static final class TintShader extends CanvasShader.Composed {
        TintShader() {
            super(CoreShadelets.composition().with(List.of(TINT)));
        }
    }

    @Test
    void twoElementsOfOneLookDrawInTheirOwnValues() {
        Look tinted = Look.of(Look.Use.of(CoreShadelets.BOX.name()), Look.Use.of(TINT.name()));
        byte[] rgba = GuiApp.render(TextFaces.standard(), new TintShader(), W, H, 0f, 0f, 0f, canvas -> {
            canvas.box(20, 20, 60, 60, 8, Color.WHITE, tinted, 1f, 0f, 0f, 1f);
            canvas.box(120, 20, 60, 60, 8, Color.WHITE, tinted, 0f, 0f, 1f, 1f);
        });

        int[] left = pixel(rgba, 50, 50);
        int[] right = pixel(rgba, 150, 50);
        assertTrue(left[0] > 200 && left[1] < 40 && left[2] < 40,
                "the first box carries red in its record: " + java.util.Arrays.toString(left));
        assertTrue(right[0] < 40 && right[1] < 40 && right[2] > 200,
                "the second box carries blue in its record: " + java.util.Arrays.toString(right));
        int[] between = pixel(rgba, 100, 50);
        assertTrue(between[0] < 40 && between[2] < 40,
                "nothing is drawn between the boxes: " + java.util.Arrays.toString(between));
    }

    private static int[] pixel(byte[] rgba, int x, int y) {
        int o = (y * W + x) * 4;
        return new int[]{rgba[o] & 0xFF, rgba[o + 1] & 0xFF, rgba[o + 2] & 0xFF, rgba[o + 3] & 0xFF};
    }
}
