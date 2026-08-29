package dev.vexelray.gui.demo;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

/**
 * A chart, authored as a {@link Picture} — the reference example of drawing rather than laying out.
 *
 * <p>Everything here is one flat list of marks in the box's own pixels. That is the whole point: the same picture
 * built as nodes would be some two hundred laid-out, hit-testable, reconciled boxes for a figure that
 * participates in no layout and is replaced wholesale whenever the panel resizes. And it contains a <b>diagonal</b>
 * — a node tree is axis-aligned, so before pictures the curve could not be drawn at all.
 *
 * <p>Colours come from the theme by {@link Role}, exactly as they do for a node's background. A picture carries
 * resolved colours rather than roles because a mark is drawn, not declared: the application resolves once, at the
 * point it decides what the mark <em>is</em>.
 *
 * <p>And it is built from a measured size, which is what a picture always is: pixels, authored against the box
 * the layout produced, rebuilt when that box changes. Nothing scales it behind the caller's back.
 */
public final class Chart {

    /** Room for the labels along the left and bottom edges. */
    private static final float PAD_L = 34f;
    private static final float PAD_B = 20f;
    private static final float PAD_T = 12f;
    private static final float PAD_R = 14f;

    private static final int SAMPLES = 96;
    private static final int GRID = 4;

    private Chart() {
    }

    /** The figure at {@code w} x {@code h} px, in {@code theme}'s colours. */
    public static Picture of(float w, float h, Theme theme) {
        float left = PAD_L;
        float right = Math.max(left + 1f, w - PAD_R);
        float top = PAD_T;
        float bottom = Math.max(top + 1f, h - PAD_B);
        float plotW = right - left;
        float plotH = bottom - top;

        Color grid = theme.color(Role.LINE);
        Color axis = theme.color(Role.EDGE);
        Color ink = theme.color(Role.DIM);
        Color curve = theme.color(Role.ACCENT);

        Sketch s = new Sketch();

        // Grid: one hairline per division, on both axes. Axis-aligned, so a node could have drawn these — which
        // is exactly why they used to be nodes.
        s.tag("grid");
        for (int i = 0; i <= GRID; i++) {
            float x = left + plotW * i / GRID;
            float y = top + plotH * i / GRID;
            s.line(x, top, x, bottom, 1, grid);
            s.line(left, y, right, y, 1, grid);
        }

        // The two axes, drawn over the grid so the corner reads as a corner.
        s.tag("axis")
                .line(left, top, left, bottom, 1.5, axis)
                .line(left, bottom, right, bottom, 1.5, axis);

        // The curve, as a polyline: a joined run of lines at whatever angle the data takes. This is the mark the
        // node tree has no way to express.
        double[] xs = new double[SAMPLES];
        double[] ys = new double[SAMPLES];
        int peak = 0;
        double highest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < SAMPLES; i++) {
            double t = (double) i / (SAMPLES - 1);
            double v = value(t);
            xs[i] = left + plotW * t;
            ys[i] = bottom - plotH * v;
            if (v > highest) {
                highest = v;
                peak = i;
            }
        }
        s.tag("curve").polyline(xs, ys, 2, curve);

        // A marker on the maximum: a filled dot with a ring around it, which is the outline primitive doing what
        // a node's border does — one stroke hugging the inside edge, not a fill with a smaller fill on top.
        s.tag("marker")
                .circle(xs[peak], ys[peak], 3.5, curve)
                .ring(xs[peak], ys[peak], 6.5, 1.5, curve);

        // Labels, each on its own baseline. A run, not a paragraph: the caller already knows where it goes.
        s.tag("label")
                .text("1.0", 8, top + 4, 11, ink)
                .text("0.0", 8, bottom + 4, 11, ink)
                .text("t", right - 4, bottom + 15, 11, ink)
                .text("peak", xs[peak] + 10, ys[peak] - 6, 11, ink);

        return s.picture();
    }

    /** Something with a maximum in the interior and no straight parts, so the polyline has work to do. */
    private static double value(double t) {
        return 0.5 + 0.45 * Math.sin(t * Math.PI * 1.6) * Math.exp(-1.1 * t);
    }
}
