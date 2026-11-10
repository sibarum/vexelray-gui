package dev.vexelray.gui.core.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A float with a keep-clear rectangle: where it asked to be when that fits, otherwise the rectangle's other side at
 * the same distance, and never over the rectangle while any side has room.
 */
class KeepClearTest {

    private static final LayoutContext CTX = new LayoutContext(16f, 1f, 1f, 800f, 600f);
    private static final float W = 800f;
    private static final float H = 600f;

    private static KeepClear around(float x, float y, float w, float h) {
        return new KeepClear(Length.dp(x), Length.dp(y), Length.dp(w), Length.dp(h));
    }

    private static boolean covers(float[] at, float w, float h, float kx, float ky, float kw, float kh) {
        return at[0] < kx + kw && at[0] + w > kx && at[1] < ky + kh && at[1] + h > ky;
    }

    @Test
    void whereItAskedWhenThatFits() {
        float[] at = around(100, 100, 40, 30).place(100, 136, 120, 28, W, H, CTX);
        assertArrayEquals(new float[]{100, 136}, at, 0.01f);
    }

    @Test
    void slidInsideTheParentAlongTheAxisThatDoesNotCover() {
        // A control at the right edge: the bubble slides left, still below it.
        float[] at = around(760, 100, 40, 30).place(760, 136, 120, 28, W, H, CTX);
        assertArrayEquals(new float[]{680, 136}, at, 0.01f);
    }

    @Test
    void belowWithNoRoomGoesAboveAtTheSameGap() {
        // A control in a bottom bar: below is off the window, and the old clamp would put the bubble over it.
        float[] at = around(760, 560, 32, 32).place(760, 598, 120, 28, W, H, CTX);
        assertArrayEquals(new float[]{680, 560 - 6 - 28}, at, 0.01f);
        assertFalse(covers(at, 120, 28, 760, 560, 32, 32));
    }

    @Test
    void aboveWithNoRoomGoesBelow() {
        float[] at = around(10, 0, 40, 20).place(10, -40, 100, 30, W, H, CTX);
        assertArrayEquals(new float[]{10, 30}, at, 0.01f);
    }

    @Test
    void neitherAboveNorBelowGoesBeside() {
        // A control as tall as the window: the bubble goes to its right, at the gap it asked for below.
        float[] at = around(100, 0, 40, 600).place(100, 606, 120, 28, W, H, CTX);
        assertArrayEquals(new float[]{146, 572}, at, 0.01f);
        assertFalse(covers(at, 120, 28, 100, 0, 40, 600));
    }

    @Test
    void noSideHasRoomTakesTheRoomiest() {
        // The window less the control is smaller than the bubble every way: the most room is above.
        float[] at = around(0, 500, 800, 60).place(0, 566, 800, 520, W, H, CTX);
        assertArrayEquals(new float[]{0, 0}, at, 0.01f);
    }
}
