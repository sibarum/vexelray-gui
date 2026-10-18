package dev.vexelray.gui.core.input;

import sibarum.tactroller.api.GestureEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of the touchpad's gestures are commands: turns the continuous stream tactroller recognizes
 * ({@link GestureEvent}) into the {@link Gesture}s a claim is made on — swipes, taps and double taps.
 *
 * <p><b>A swipe fires as it happens, not when the fingers lift.</b> Once the fingers have travelled
 * {@link #SWIPE_MM} along one axis, and at least twice as far along it as across it, the swipe is done and the
 * command runs — the next picture is already coming while the hand is still moving. A short, fast flick that lifts
 * before going that far is a swipe too: {@link #FLICK_MM} at {@link #FLICK_MM_PER_S}. One swipe per gesture,
 * however far it goes, and only from fingers moving together: a pinch is never a swipe.
 *
 * <p><b>A tap is reported at once, and a second within {@link #DOUBLE_TAP_MS} with the same fingers is also a
 * double tap.</b> The single tap is not held back to see whether a second follows: that would delay every tap for
 * the sake of the few places that bind both, and a place that binds a double tap usually binds nothing to the tap.
 *
 * <p>Not thread-safe: one per window, fed on the GUI thread in the order the gestures happened.
 */
public final class GestureTriggers {

    /** How far the fingers travel along one axis before a swipe is decided, in millimetres on the pad. */
    public static final double SWIPE_MM = 12.0;
    /** The least travel a flick needs, in millimetres, and how fast it must be leaving, in millimetres a second. */
    public static final double FLICK_MM = 4.0;
    public static final double FLICK_MM_PER_S = 120.0;
    /** How far the travel along the swipe must exceed the travel across it. */
    private static final double DOMINANCE = 2.0;
    /** The longest from one tap to the next that is still a double tap. */
    public static final long DOUBLE_TAP_MS = 350L;

    // The pan in flight: how far its fingers have gone, and whether it has already swiped.
    private boolean panning;
    private double travelX;
    private double travelY;
    private boolean swiped;

    // The last tap, for a double.
    private int tapFingers;
    private long tapNanos;

    /** The commands in {@code event}, in order; usually none. */
    public List<Gesture> feed(GestureEvent event) {
        List<Gesture> out = new ArrayList<>(2);
        switch (event) {
            case GestureEvent.GestureStarted s -> {
                panning = s.kind() == GestureEvent.Kind.PAN && s.fingers() <= Gesture.MAX_FINGERS;
                travelX = travelY = 0;
                swiped = false;
            }
            case GestureEvent.GestureUpdated u -> {
                if (panning && !swiped) {
                    travelX += u.dx();
                    travelY += u.dy();
                    Gesture.Motion m = direction(travelX, travelY, SWIPE_MM);
                    if (m != null) {
                        swiped = true;
                        out.add(Gesture.swipe(u.fingers(), m));
                    }
                }
            }
            case GestureEvent.GestureEnded e -> {
                if (panning && !swiped) {
                    Gesture.Motion m = direction(travelX, travelY, FLICK_MM);
                    if (m != null && along(m, e.velocityX(), e.velocityY()) >= FLICK_MM_PER_S) {
                        out.add(Gesture.swipe(e.fingers(), m));
                    }
                }
                panning = false;
            }
            case GestureEvent.Tapped t -> {
                if (t.fingers() <= Gesture.MAX_FINGERS) {
                    out.add(Gesture.tap(t.fingers()));
                    if (t.fingers() == tapFingers && t.timestampNanos() - tapNanos <= DOUBLE_TAP_MS * 1_000_000L) {
                        out.add(Gesture.doubleTap(t.fingers()));
                        tapFingers = 0;   // a third tap starts another pair, not a second double
                    } else {
                        tapFingers = t.fingers();
                        tapNanos = t.timestampNanos();
                    }
                }
            }
        }
        return out;
    }

    /** The way {@code (x, y)} goes, if it goes at least {@code least} along one axis and mostly that way. */
    private static Gesture.Motion direction(double x, double y, double least) {
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        if (ax >= least && ax >= DOMINANCE * ay) {
            return x < 0 ? Gesture.Motion.SWIPE_LEFT : Gesture.Motion.SWIPE_RIGHT;
        }
        if (ay >= least && ay >= DOMINANCE * ax) {
            return y < 0 ? Gesture.Motion.SWIPE_UP : Gesture.Motion.SWIPE_DOWN;
        }
        return null;
    }

    /** The speed of {@code (vx, vy)} in direction {@code m}: positive when it is going that way. */
    private static double along(Gesture.Motion m, double vx, double vy) {
        return switch (m) {
            case SWIPE_LEFT -> -vx;
            case SWIPE_RIGHT -> vx;
            case SWIPE_UP -> -vy;
            case SWIPE_DOWN -> vy;
            default -> 0;
        };
    }
}
