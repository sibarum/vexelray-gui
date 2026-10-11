package dev.vexelray.gui.core.input;

/**
 * The application carrying the pointer along with something it is animating, until the hand takes it back.
 *
 * <pre>{@code
 * PointerRide ride = gui.ridePointer();
 * ramp.run(p -> { Point at = cardAt(p); card.floatAt(at); if (!ride.to(at.x() + grabX, at.y() + grabY)) ... },
 *          ride::end);
 * }</pre>
 *
 * <p>The case it exists for: a drag is cancelled, the card flies back to where it was picked up, and the hand was
 * on it. A pointer left where the cancel happened is now holding nothing, pointing at a place the card no longer
 * is; a pointer that rides home on the card, at the point it was grabbed, ends exactly where the drag began —
 * which is what undoing a gesture should mean. It moves a step per frame, alongside the card, so the eye follows
 * one motion; there is no frame in which it is in one place and then another.
 *
 * <h2>The hand wins</h2>
 *
 * <p>The user's own motion always overrides. Each step checks where the pointer actually is against where the ride
 * put it; once they disagree by more than the ride could account for, the user has moved the mouse, and every
 * later step is refused — {@link #to} answers false, and the caller lets its animation finish without the
 * pointer. Taking the pointer back from a user who has started moving it would be the worst kind of jump: one
 * that fights them.
 *
 * <p>Safe from any thread: a ride is stepped by whatever clock drives the animation. Where the platform cannot move
 * the pointer, every step is accepted and nothing moves.
 */
public final class PointerRide {

    /** How far the pointer may be from every recent step before it is the user's rather than the ride's, px. */
    private static final float SLACK_PX = 3f;

    private final InputDispatcher input;
    private final PointerWarp warp;
    /** The last few places the ride put the pointer, oldest first, and where it started: a poll lags a step. */
    private final float[] recentX = new float[4];
    private final float[] recentY = new float[4];
    private int count;
    private volatile boolean yielded;
    private volatile boolean ended;

    PointerRide(InputDispatcher input, PointerWarp warp, float startX, float startY) {
        this.input = input;
        this.warp = warp;
        remember(startX, startY);
    }

    /**
     * Put the pointer at {@code (x, y)}, in client-space px — unless the user has taken it back.
     *
     * @return false once the hand has moved the pointer itself, and for every step after that
     */
    public synchronized boolean to(float x, float y) {
        if (yielded || ended) {
            return false;
        }
        if (!near(input.pointerX(), input.pointerY())) {
            yielded = true;   // moved by the hand: from here on it is theirs
            return false;
        }
        remember(x, y);
        warp.to(x, y);
        return true;
    }

    /** Whether the user took the pointer back before the ride ended. */
    public boolean yielded() {
        return yielded;
    }

    /** The animation is over; nothing more will be asked of the pointer. */
    public void end() {
        ended = true;
    }

    private boolean near(float px, float py) {
        int n = Math.min(count, recentX.length);
        for (int i = 0; i < n; i++) {
            if (Math.abs(px - recentX[i]) <= SLACK_PX && Math.abs(py - recentY[i]) <= SLACK_PX) {
                return true;
            }
        }
        // Not at any of the last few steps — but possibly between two of them, a poll taken mid-step.
        for (int i = 1; i < n; i++) {
            if (between(px, py, recentX[i - 1], recentY[i - 1], recentX[i], recentY[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean between(float px, float py, float ax, float ay, float bx, float by) {
        float minX = Math.min(ax, bx) - SLACK_PX;
        float maxX = Math.max(ax, bx) + SLACK_PX;
        float minY = Math.min(ay, by) - SLACK_PX;
        float maxY = Math.max(ay, by) + SLACK_PX;
        return px >= minX && px <= maxX && py >= minY && py <= maxY;
    }

    private void remember(float x, float y) {
        if (count < recentX.length) {
            recentX[count] = x;
            recentY[count] = y;
        } else {
            System.arraycopy(recentX, 1, recentX, 0, recentX.length - 1);
            System.arraycopy(recentY, 1, recentY, 0, recentY.length - 1);
            recentX[recentX.length - 1] = x;
            recentY[recentY.length - 1] = y;
        }
        count++;
    }
}
