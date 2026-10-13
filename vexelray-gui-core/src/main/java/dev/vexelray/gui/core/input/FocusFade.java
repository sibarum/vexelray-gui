package dev.vexelray.gui.core.input;

/**
 * The focus ring, arriving and leaving rather than appearing and vanishing.
 *
 * <p>Two rings at most: the one fading in round the node that has focus, and the one fading out round the node
 * that just lost it. A third move while both are mid-fade drops the older leaver, which is the one nobody is
 * looking at any more. Coming straight back to the node that is fading out picks its ring up where it had got to,
 * so Tab and Shift+Tab in quick succession never blink a ring off and on.
 *
 * <p>A fixed duration, not a half-life as {@code ScrollGlide} uses: a ring has an end state it must reach exactly
 * (gone, or whole), and nothing adds to a fade that is already running the way a second wheel notch adds to a
 * glide. Linear in time underneath and eased on the way out to the renderer, so the start and the end are gentle
 * and a reversal mid-fade carries on from the same opacity.
 *
 * <p>GUI thread only: moved by the dispatcher when focus moves, stepped at the top of each frame.
 */
final class FocusFade {

    /** The most time one step will account for, so a frame after a long stall catches up rather than jumping. */
    private static final long MAX_STEP_NANOS = 50_000_000L;

    /** How much time the first step of a fade accounts for: one frame at 120 Hz. */
    private static final long FIRST_STEP_NANOS = 8_333_333L;

    private long durationNanos;
    private float shown = 1f;
    private long leavingId = -1L;
    private FocusCause leavingCause = FocusCause.PROGRAM;
    private float leaving;
    /** When the last step ran, or 0 when nothing is fading. */
    private long last;

    /** How long a ring takes to fade fully in or out; zero shows and hides it at once. */
    void duration(long nanos) {
        this.durationNanos = Math.max(0L, nanos);
        if (durationNanos == 0L) {
            shown = 1f;
            leavingId = -1L;
            leavingCause = FocusCause.PROGRAM;
            leaving = 0f;
            last = 0L;
        }
    }

    /** Focus moved from {@code from} (reached by {@code fromCause}) to {@code to}. */
    void moved(long from, FocusCause fromCause, long to) {
        if (durationNanos == 0L) {
            return;
        }
        float resumed = to != -1L && to == leavingId ? leaving : 0f;
        if (from != -1L) {
            leavingId = from;
            leavingCause = fromCause;
            leaving = shown;
        } else if (to == leavingId) {
            leavingId = -1L;
            leaving = 0f;
        }
        shown = resumed;
    }

    /**
     * Advance to {@code now}. True when anything changed — the ring has to be drawn again, and the frame after
     * this one is owed too until both fades are done.
     */
    boolean step(long now) {
        if (!fading()) {
            last = 0L;
            return false;
        }
        long dt = last == 0L ? FIRST_STEP_NANOS : Math.min(MAX_STEP_NANOS, Math.max(0L, now - last));
        last = now;
        float by = (float) dt / durationNanos;
        shown = Math.min(1f, shown + by);
        leaving = Math.max(0f, leaving - by);
        if (leaving == 0f) {
            leavingId = -1L;
        }
        return true;
    }

    private boolean fading() {
        return shown < 1f || leavingId != -1L;
    }

    /** The focus as it stands, with this fade's opacities in it. */
    FocusState applyTo(long nodeId, FocusCause cause, boolean windowActive) {
        return new FocusState(nodeId, cause, windowActive, ease(shown), leavingId, leavingCause, ease(leaving));
    }

    /** Smoothstep: gentle at both ends, and the same opacity for the same progress whichever way it is going. */
    private static float ease(float t) {
        return t * t * (3f - 2f * t);
    }
}
