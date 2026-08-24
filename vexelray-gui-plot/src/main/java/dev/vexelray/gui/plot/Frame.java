package dev.vexelray.gui.plot;

import java.math.BigDecimal;

/**
 * The rectangle of plot space a viewport is showing: {@code x ∈ [xLo, xHi]}, {@code y ∈ [yLo, yHi]}. Where the
 * enclosure algebra knows only numbers, this is the first type that knows there is a picture.
 *
 * <p>It carries the two conversions everything above it needs and nobody below it should have: which
 * <b>column</b> of x a pixel column covers, and where a y value falls as a <b>fraction</b> of the frame's height.
 * Both are here rather than in a renderer because they are the definition of the frame, not a detail of drawing —
 * a second renderer must agree with the first about which x a pixel means, and it will if it never computes that
 * itself.
 *
 * <h2>Columns tile the width exactly</h2>
 * {@link #column} returns {@code [xLo + i·w, xLo + (i+1)·w]}, both endpoints computed the same way, so column
 * {@code i}'s right edge <em>is</em> column {@code i+1}'s left edge and the union of all columns is the whole
 * width with no seam. That matters for soundness rather than tidiness: a gap between columns is a strip of x the
 * plot never evaluated and would silently draw nothing for.
 *
 * <h2>Fractions run downward</h2>
 * {@link #fractionOf} answers 0 at {@code yHi} and 1 at {@code yLo} — screen order, not plot order. The flip
 * belongs here, once, so that no consumer has to remember which way up its own coordinates are.
 *
 * @param xLo left edge, in plot units
 * @param xHi right edge, in plot units (strictly greater than {@code xLo})
 * @param yLo bottom edge, in plot units
 * @param yHi top edge, in plot units (strictly greater than {@code yLo})
 */
public record Frame(double xLo, double xHi, double yLo, double yHi) {

    public Frame {
        require(xLo, "xLo");
        require(xHi, "xHi");
        require(yLo, "yLo");
        require(yHi, "yHi");
        // A frame of zero width or height has no pixels to divide and no scale to map through: every
        // conversion below it would be a division by zero, so it is rejected where it is made rather than
        // producing infinities somewhere downstream.
        if (!(xHi > xLo)) {
            throw new IllegalArgumentException("empty x range: [" + xLo + ", " + xHi + "]");
        }
        if (!(yHi > yLo)) {
            throw new IllegalArgumentException("empty y range: [" + yLo + ", " + yHi + "]");
        }
    }

    /** A frame centred on the origin, {@code halfWidth} either side and {@code halfHeight} above and below. */
    public static Frame about(double halfWidth, double halfHeight) {
        return new Frame(-halfWidth, halfWidth, -halfHeight, halfHeight);
    }

    public double width() {
        return xHi - xLo;
    }

    public double height() {
        return yHi - yLo;
    }

    /** The plot-space width of one column when the viewport is divided into {@code columns} of them. */
    public double columnWidth(int columns) {
        return width() / columns;
    }

    /**
     * The x-interval covered by column {@code index} of {@code columns} — the thing an {@link Expr} is enclosed
     * over. Endpoints are shared with the neighbouring columns, so the columns tile the width without a seam.
     */
    public Interval column(int index, int columns) {
        if (columns <= 0) {
            throw new IllegalArgumentException("a frame is divided into at least one column");
        }
        double w = columnWidth(columns);
        // Both edges from the same expression: column i's right edge is bit-for-bit column i+1's left edge.
        return new Interval(BigDecimal.valueOf(xLo + index * w), BigDecimal.valueOf(xLo + (index + 1) * w));
    }

    /** Where {@code y} falls down the frame: 0 at the top edge, 1 at the bottom, unclamped outside. */
    public double fractionOf(double y) {
        return (yHi - y) / height();
    }

    /** The y value at {@code fraction} of the way down the frame — {@link #fractionOf} inverted. */
    public double yAt(double fraction) {
        return yHi - fraction * height();
    }

    /** The x value at {@code fraction} of the way across the frame, left to right. */
    public double xAt(double fraction) {
        return xLo + fraction * width();
    }

    /** This frame with a different vertical extent — what a re-framing of y alone produces. */
    public Frame withY(double lo, double hi) {
        return new Frame(xLo, xHi, lo, hi);
    }

    /** This frame moved by {@code (dx, dy)} in plot units: a pan. */
    public Frame panned(double dx, double dy) {
        return new Frame(xLo + dx, xHi + dx, yLo + dy, yHi + dy);
    }

    /**
     * This frame scaled about a fixed point given in frame fractions — a zoom that keeps whatever is under the
     * pointer under the pointer. {@code factor} below 1 narrows the window (zooming in).
     *
     * @param factor    multiplier on both extents
     * @param atX       the fixed point's horizontal position, 0 at the left edge and 1 at the right
     * @param atY       the fixed point's vertical position, 0 at the top edge and 1 at the bottom
     */
    public Frame zoomed(double factor, double atX, double atY) {
        double fx = xAt(atX);
        double fy = yAt(atY);
        return new Frame(fx - (fx - xLo) * factor, fx + (xHi - fx) * factor,
                         fy - (fy - yLo) * factor, fy + (yHi - fy) * factor);
    }

    private static void require(double v, String name) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite, was " + v);
        }
    }
}
