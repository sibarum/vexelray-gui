package dev.vexelray.gui.plot;

import java.math.BigDecimal;

/**
 * The box of plot space a surface viewport is showing: {@code x ∈ [xLo, xHi]}, {@code y ∈ [yLo, yHi]},
 * {@code z ∈ [zLo, zHi]}. {@link Frame}, with a third axis and the same job — the first type above the
 * arithmetic that knows there is a picture, carrying the conversions that everything above it needs and nothing
 * below it should have.
 *
 * <h2>Cells tile the floor exactly</h2>
 * {@link #cellAt} builds the region for grid square {@code (ix, iy)} at a given cell size from those indices
 * <b>and nothing else</b>, which is the same rule {@link Frame#column} follows and for the same two reasons.
 * Adjacent cells share an edge exactly, so the grid covers the floor with no strip of domain nobody evaluated;
 * and a cell has an <em>identity</em> — "the cell of size {@code u} at {@code (ix, iy)}" — so an enclosure
 * cached against it stays a true statement about the cell it is later looked up for.
 *
 * <h2>Fractions run downward</h2>
 * {@link #fractionOf} answers 0 at {@code zHi} and 1 at {@code zLo}, matching {@link Frame#fractionOf} so that
 * one {@link Span.Sink} implementation reads the same in both. Height is height whichever axis it is measured
 * along.
 *
 * @param xLo left edge of the floor, in plot units
 * @param xHi right edge of the floor (strictly greater than {@code xLo})
 * @param yLo near edge of the floor
 * @param yHi far edge of the floor (strictly greater than {@code yLo})
 * @param zLo bottom of the visible height
 * @param zHi top of the visible height (strictly greater than {@code zLo})
 */
public record Volume(double xLo, double xHi, double yLo, double yHi, double zLo, double zHi) {

    public Volume {
        require(xLo, "xLo");
        require(xHi, "xHi");
        require(yLo, "yLo");
        require(yHi, "yHi");
        require(zLo, "zLo");
        require(zHi, "zHi");
        // Zero extent on any axis leaves a conversion below dividing by zero, so it is rejected where it is
        // made rather than producing infinities somewhere downstream. Frame's reasoning, one axis wider.
        empty(xLo, xHi, "x");
        empty(yLo, yHi, "y");
        empty(zLo, zHi, "z");
    }

    /** A volume centred on the origin: a square floor {@code half} either way, {@code halfZ} above and below. */
    public static Volume about(double half, double halfZ) {
        return new Volume(-half, half, -half, half, -halfZ, halfZ);
    }

    public double xWidth() {
        return xHi - xLo;
    }

    public double yDepth() {
        return yHi - yLo;
    }

    public double zHeight() {
        return zHi - zLo;
    }

    /**
     * The region of the domain covered by grid square {@code (ix, iy)} when cells are {@code u} across, bound
     * to the two axes by name. Computed from the indices, so two renders that ask about the same cell get the
     * same region and a cache between them is answering about the cell being drawn.
     */
    public static Cell cellAt(long ix, long iy, double u, String xName, String yName) {
        return Cell.of(xName, span(ix, u), yName, span(iy, u));
    }

    /** The interval {@code [n·u, (n+1)·u]} — one axis of {@link #cellAt}, and where its exactness comes from. */
    public static Interval span(long index, double u) {
        return new Interval(BigDecimal.valueOf(index * u), BigDecimal.valueOf((index + 1) * u));
    }

    /** Where {@code z} falls down the visible height: 0 at the top, 1 at the bottom, unclamped outside. */
    public double fractionOf(double z) {
        return (zHi - z) / zHeight();
    }

    /** The {@code z} at {@code fraction} of the way down — {@link #fractionOf} inverted. */
    public double zAt(double fraction) {
        return zHi - fraction * zHeight();
    }

    /** This volume with a different height — what a re-framing of z alone produces. */
    public Volume withZ(double lo, double hi) {
        return new Volume(xLo, xHi, yLo, yHi, lo, hi);
    }

    /**
     * This volume's floor scaled about the origin, its height left alone. The one domain transform a surface
     * offers: widening the window is a question about the expression, while moving the picture is a question
     * for the camera, and keeping them apart is what lets the cell grid stay centred and therefore cacheable.
     */
    public Volume scaledFloor(double factor) {
        return new Volume(xLo * factor, xHi * factor, yLo * factor, yHi * factor, zLo, zHi);
    }

    private static void require(double v, String name) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite, was " + v);
        }
    }

    private static void empty(double lo, double hi, String axis) {
        if (!(hi > lo)) {
            throw new IllegalArgumentException("empty " + axis + " range: [" + lo + ", " + hi + "]");
        }
    }
}
