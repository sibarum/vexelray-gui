package dev.vexelray.gui.plot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame's two jobs: which column of x a pixel column covers, and where a y falls down the picture. Both are
 * arithmetic, and both are the sort of arithmetic that is wrong by one somewhere and never noticed.
 */
class FrameTest {

    /**
     * The property the whole module leans on. A gap between two adjacent columns is a strip of x that is never
     * evaluated and silently draws as nothing — the exact failure interval arithmetic was adopted to prevent,
     * reintroduced by the tiling rather than by the algebra.
     */
    @Test
    void columnsTileTheWidthWithoutASeam() {
        Frame frame = new Frame(-3, 7, -1, 1);
        int columns = 97;                       // odd and prime: no divisor of the width to hide behind
        for (int i = 0; i < columns - 1; i++) {
            assertEquals(0, frame.column(i, columns).hi().compareTo(frame.column(i + 1, columns).lo()),
                    "column " + i + " must end exactly where column " + (i + 1) + " begins");
        }
        assertEquals(0, frame.column(0, columns).lo().compareTo(java.math.BigDecimal.valueOf(-3.0)),
                "the first column starts at the left edge");
        assertEquals(7.0, frame.column(columns - 1, columns).hi().doubleValue(), 1e-9,
                "and the last one ends at the right edge");
    }

    @Test
    void fractionsRunDownTheScreenAndInvert() {
        Frame frame = new Frame(0, 1, -4, 6);
        assertEquals(0.0, frame.fractionOf(6), 1e-12, "the top edge is the top of the picture");
        assertEquals(1.0, frame.fractionOf(-4), 1e-12, "and the bottom edge is the bottom");
        assertEquals(0.5, frame.fractionOf(1), 1e-12);
        assertEquals(1.0, frame.yAt(frame.fractionOf(1.0)), 1e-12, "yAt inverts fractionOf");
        assertEquals(0.25, frame.xAt(0.25), 1e-12);
    }

    /** A zoom is only a zoom if the thing under the pointer stays under the pointer. */
    @Test
    void zoomingKeepsItsAnchorStill() {
        Frame frame = new Frame(-10, 10, -10, 10);
        double anchorX = frame.xAt(0.3);
        double anchorY = frame.yAt(0.8);
        Frame closer = frame.zoomed(0.5, 0.3, 0.8);
        assertEquals(anchorX, closer.xAt(0.3), 1e-9);
        assertEquals(anchorY, closer.yAt(0.8), 1e-9);
        assertEquals(frame.width() / 2, closer.width(), 1e-9, "a factor of a half halves the window");
    }

    @Test
    void panningMovesBothAxesAndNothingElse() {
        Frame frame = new Frame(-1, 1, -2, 2).panned(0.5, -3);
        assertEquals(-0.5, frame.xLo(), 1e-12);
        assertEquals(1.5, frame.xHi(), 1e-12);
        assertEquals(-5.0, frame.yLo(), 1e-12);
        assertEquals(2.0, frame.width(), 1e-12);
        assertEquals(4.0, frame.height(), 1e-12);
    }

    /**
     * An empty or non-finite frame is rejected where it is made. Every conversion on it divides by its extent,
     * so letting one through would scatter infinities through code that has no way to know where they came from.
     */
    @Test
    void aDegenerateFrameIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Frame(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, 1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, Double.POSITIVE_INFINITY, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, 1, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, 1, 0, 1).column(0, 0));
    }

    @Test
    void aboutTheOriginIsSymmetric() {
        Frame frame = Frame.about(4, 2.5);
        assertTrue(frame.xLo() == -4 && frame.xHi() == 4 && frame.yLo() == -2.5 && frame.yHi() == 2.5);
    }
}
