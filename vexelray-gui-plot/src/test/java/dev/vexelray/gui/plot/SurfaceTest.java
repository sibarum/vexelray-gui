package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three units a surface needs that a curve did not: the cell an expression is enclosed over, the volume on
 * show, and the camera it is seen from.
 *
 * <p>The claim being pinned throughout is that <b>nothing about soundness changed</b> when the domain gained an
 * axis. An enclosure over a cell contains every value the expression takes in that cell, poles are still found
 * by the arithmetic rather than by a search, and one variable is still exactly the degenerate case of two.
 */
class SurfaceTest {

    private static final Expr X = new Expr.Param("x");
    private static final Expr Y = new Expr.Param("y");

    private static Interval unit(double lo, double hi) {
        return Interval.of(lo, hi);
    }

    /** The endpoints of a bounded enclosure, as numbers — scale is {@code BigDecimal}'s business, not a test's. */
    private static void assertEncloses(double lo, double hi, Enclosure enclosure) {
        Interval bounded = assertInstanceOf(Interval.class, enclosure);
        assertEquals(lo, bounded.lo().doubleValue(), 1e-12);
        assertEquals(hi, bounded.hi().doubleValue(), 1e-12);
    }

    /** A column binds every name, which is what makes {@code enclose(Interval)} mean what it always did. */
    @Test
    void oneVariableIsTheDegenerateCaseOfTwo() {
        Expr f = new Expr.Add(X, X);
        assertEquals(f.enclose(unit(1, 2)), f.enclose(Cell.column(unit(1, 2))));
        assertEncloses(2, 4, f.enclose(unit(1, 2)));
    }

    /** The saddle. Each axis is bound separately, and mixing them up would show. */
    @Test
    void eachAxisIsBoundByItsOwnName() {
        Expr saddle = new Expr.Sub(new Expr.Power(X, new Expr.Const(2.0)),
                                   new Expr.Power(Y, new Expr.Const(2.0)));
        // x ∈ [2, 3] → x² ∈ [4, 9]; y ∈ [0, 1] → y² ∈ [0, 1]; the difference is [3, 9].
        assertEncloses(3, 9, saddle.enclose(Cell.of("x", unit(2, 3), "y", unit(0, 1))));
        // Swap the bindings and the answer is the negative of it — proof the names are doing the work.
        assertEncloses(-9, -3, saddle.enclose(Cell.of("x", unit(0, 1), "y", unit(2, 3))));
    }

    /** A pole over a patch falls out of the arithmetic exactly as a pole over a column does. */
    @Test
    void aPoleOverAPatchIsStillFoundByTheDivision() {
        Expr f = new Expr.Div(new Expr.Const(1.0), new Expr.Add(X, Y));
        assertSame(Enclosure.UNBOUNDED, f.enclose(Cell.of("x", unit(-1, 1), "y", unit(-1, 1))),
                "x + y reaches zero in this cell, so the quotient leaves every bound inside it");
        assertTrue(f.enclose(Cell.of("x", unit(1, 2), "y", unit(1, 2))) instanceof Interval,
                "and away from the line x = −y it is an ordinary number");
    }

    /** An expression asked about an axis its cell does not bind is a pairing mistake, and says so. */
    @Test
    void anUnboundAxisIsAMistakeRatherThanAGuess() {
        Expr f = new Expr.Add(X, new Expr.Param("z"));
        Cell cell = Cell.of("x", unit(0, 1), "y", unit(0, 1));
        assertThrows(IllegalArgumentException.class, () -> f.enclose(cell));
        assertThrows(IllegalArgumentException.class, () -> Cell.of("x", unit(0, 1), "x", unit(0, 1)));
    }

    /** Cells tile the floor with no seam: one cell's edge is exactly its neighbour's, bit for bit. */
    @Test
    void cellsTileTheFloorWithoutASeam() {
        double u = 0.125;
        Interval left = Volume.span(3, u);
        Interval right = Volume.span(4, u);
        assertEquals(left.hi(), right.lo(), "the shared edge must be one number, not two that agree");
        assertEquals(BigDecimal.valueOf(0.375), left.lo());
        assertEquals(BigDecimal.valueOf(0.5), left.hi());
    }

    /** A volume with no extent on any axis is rejected where it is made. */
    @Test
    void anEmptyVolumeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Volume(0, 0, -1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Volume(-1, 1, 0, 0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Volume(-1, 1, -1, 1, 5, 5));
        assertThrows(IllegalArgumentException.class, () -> new Volume(-1, Double.NaN, -1, 1, -1, 1));
    }

    /** Height runs downward, the same way {@link Frame#fractionOf} does, so one sink reads alike in both. */
    @Test
    void theFractionRunsDownwardLikeAFrames() {
        Volume v = Volume.about(10, 4);
        assertEquals(0, v.fractionOf(4), 1e-12, "the top of the volume is the top of the picture");
        assertEquals(1, v.fractionOf(-4), 1e-12);
        assertEquals(0.5, v.fractionOf(0), 1e-12);
        assertEquals(0, v.zAt(v.fractionOf(0)), 1e-12, "and the conversion inverts");
    }

    /** The same three answers a column gives, classified against a bare extent instead of a rectangle. */
    @Test
    void aCellClassifiesWithTheSameSpansAColumnDoes() {
        assertEquals(new Span.Curve(0.25, 0.75), Span.of(Interval.of(-1, 1), -2, 2));
        assertEquals(Span.FILL, Span.of(Enclosure.UNBOUNDED, -2, 2));
        assertEquals(Span.BLANK, Span.of(Enclosure.UNDEFINED, -2, 2));
        assertEquals(Span.BLANK, Span.of(Interval.of(8, 9), -2, 2), "wholly above the extent is nothing to draw");
        // And a frame's y range is exactly that extent, so the two entry points cannot drift apart.
        assertEquals(Span.of(Interval.of(-1, 1), new Frame(-1, 1, -2, 2)), Span.of(Interval.of(-1, 1), -2, 2));
    }

    /** Turning the camera does not move a point's height on screen unless the point has height. */
    @Test
    void theCameraProjectsTheFloorFlatAndHeightUpward() {
        Camera c = Camera.DEFAULT;
        Camera.Point origin = c.project(0, 0, 0);
        assertEquals(0, origin.u(), 1e-12);
        assertEquals(0, origin.v(), 1e-12);
        assertTrue(c.project(0, 0, 0.5).v() > 0, "up is up");
        assertTrue(c.project(0.5, -0.5, 0).v() < c.project(-0.5, 0.5, 0).v(),
                "the near corner of the floor sits below the far one");
    }

    /** The pitch is clamped away from both degenerate ends by the constructor, so a gesture need not check. */
    @Test
    void thePitchCannotBeWoundFlatOrOverhead() {
        assertEquals(Camera.MIN_PITCH, Camera.DEFAULT.turned(0, -10).pitch(), 1e-12);
        assertEquals(Camera.MAX_PITCH, Camera.DEFAULT.turned(0, 10).pitch(), 1e-12);
        assertNotEquals(0.0, Camera.DEFAULT.turned(0, -10).reach()[1],
                "a floor seen edge-on would have no height at all, which is why it is refused");
    }

    /**
     * The view direction is a unit vector, it points downward into the scene, and it is the axis
     * {@link Camera#depthKey} measures along — which is what lets a consumer use one of them for angle and the
     * other for order without the two disagreeing.
     */
    @Test
    void theViewDirectionIsOneVectorForTheWholePicture() {
        Camera c = Camera.DEFAULT;
        double[] v = c.viewDirection();
        assertEquals(1, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-12, "a direction is a unit vector");
        assertTrue(v[2] < 0, "the eye is above the floor, so it looks down");
        // depth along the view direction, for a point on the floor, is the depth key -- one axis, two readings.
        assertEquals(c.depthKey(0.3, -0.2) * Math.cos(c.pitch()),
                0.3 * v[0] + -0.2 * v[1], 1e-12);
        // Straight down at the top of the pitch range: nothing but z.
        double[] overhead = new Camera(0, 10).viewDirection();
        assertEquals(0, overhead[0], 1e-2);
        assertTrue(overhead[2] < -0.99);
    }

    /**
     * The painting order depends on the floor and not on height — which is the whole reason the projection has
     * no perspective, and the assumption the surface renderer's occlusion rests on.
     */
    @Test
    void theDepthOrderIsAFunctionOfTheFloorAlone() {
        Camera c = Camera.DEFAULT;
        assertEquals(c.depthKey(1, 1), c.depthKey(1, 1), 1e-12);
        assertTrue(c.depthKey(1, 1) > c.depthKey(-1, -1), "the far corner of the floor is further away");
        Camera turned = c.turned(Math.PI, 0);
        assertTrue(turned.depthKey(1, 1) < turned.depthKey(-1, -1), "and turning it round swaps them");
    }

    /** The framing policy measures a surface's height the same way it measures a curve's. */
    @Test
    void aSaddleIsFramedAroundWhatItActuallyDoes() {
        Expr saddle = new Expr.Sub(new Expr.Power(X, new Expr.Const(2.0)),
                                   new Expr.Power(Y, new Expr.Const(2.0)));
        Volume v = Framing.automatic(saddle, "x", "y");
        assertEquals(-10, v.xLo(), 1e-9, "the floor is not chosen, exactly as the x window of a curve is not");
        assertEquals(10, v.yHi(), 1e-9);
        assertTrue(v.zHi() > 20 && v.zLo() < -20, "a saddle over [-10,10]² reaches ±100; the fit must show it");
        assertEquals(v.zHi(), -v.zLo(), 1e-9, "and it is symmetric, so its frame should be");
    }
}
