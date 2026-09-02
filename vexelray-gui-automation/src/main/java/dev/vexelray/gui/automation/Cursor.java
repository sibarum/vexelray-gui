package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.input.InputTopics;
import sibarum.atchung.Atchung;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

/**
 * A pointer with a position, which moves.
 *
 * <p>This is the fidelity rule of the whole instrument (docs/automation.md §2), and it is one sentence:
 * <b>the cursor never teleports</b>. Clicking something at {@code (412, 88)} while the pointer rests at
 * {@code (90, 640)} does not publish a click at {@code (412, 88)}. It publishes a path of pointer moves from
 * where the cursor actually is to where the target is, stepped and paced like a hand, and only then the press
 * and the release.
 *
 * <p>That is not realism for its own sake. Enter, leave and hover then fire because they were <em>provoked</em>,
 * not because this class remembered to simulate them — and a bug whose only evidence is a hover fired while
 * crossing an unrelated widget is a bug a teleporting driver cannot reproduce and, worse, reports a clean run
 * for. The evidence exists only if the pointer really travelled.
 *
 * <p><b>Everything goes out on the ordinary Tactroller topic</b> ({@link InputTopics#INPUT}), which is the same
 * path a real device's events take. There is no automation mode for the application to behave differently in,
 * and nothing here can drift out of step with the real one, because there is no other one.
 *
 * <p><b>Deterministic.</b> The same start and the same target produce the same step count at the same cadence
 * and therefore the same events. No jitter and no randomness: an instrument that is not reproducible cannot be
 * used to bisect anything, and "human-like" noise would trade the only property that matters for the appearance
 * of one that does not.
 *
 * <p><b>Paths have side effects, and that is correct.</b> A move can cross an open menu, arm a hover delay, or
 * pass over a control that reacts. A person's pointer does the same. Every step is recorded on the {@code input}
 * lane, so a hover picked up in passing is visible in the correlation log rather than an invisible confound.
 *
 * <p>Not thread-safe, and not meant to be: a pointer is one hand. Drive it from one thread.
 */
public final class Cursor {

    /**
     * The cadence, in milliseconds — 125 Hz, an ordinary mouse's report rate.
     *
     * <p>Real time rather than frames. Hover delays, double-click windows and repeat thresholds are wall-clock
     * contracts; a path stepped per frame would satisfy them on a fast machine and violate them on a slow one,
     * which is the same as not testing them.
     */
    public static final long STEP_MILLIS = 8L;

    /** Hand speed, in pixels per millisecond: about 600px in half a second. */
    public static final double SPEED_PX_PER_MS = 1.2;

    /**
     * The most steps one path may take.
     *
     * <p>Without a cap, a move across a 4K display is four hundred steps and three seconds, and an agent that
     * spends three seconds travelling is an agent nobody waits for. Past this the path covers more ground per
     * step rather than taking longer — still continuous, still crossing everything between the two points, just
     * faster than a hand. The cadence never changes, so the wall-clock contracts above still hold.
     */
    private static final int MAX_STEPS = 64;

    private final Atchung bus;
    private int x;
    private int y;

    /** A cursor resting at the origin. */
    public Cursor(Atchung bus) {
        this(bus, 0, 0);
    }

    /** A cursor resting at {@code (x, y)} — where the pointer already is, if that is known. */
    public Cursor(Atchung bus, int x, int y) {
        this.bus = java.util.Objects.requireNonNull(bus, "bus");
        this.x = x;
        this.y = y;
    }

    /** Where the pointer is now. State, not a parameter: this is what makes the next path start somewhere. */
    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    /**
     * Travel to {@code (tx, ty)}, publishing every intermediate position.
     *
     * <p>Returns when the pointer has arrived. Interrupting the thread stops the path where it got to and
     * leaves the cursor honestly reporting that position — a pointer that claims to be somewhere it never
     * reached would make every later assertion wrong in a way nothing would explain.
     */
    public Cursor moveTo(int tx, int ty) {
        int dx = tx - x;
        int dy = ty - y;
        double distance = Math.hypot(dx, dy);
        if (distance < 1.0) {
            // Already there. Publishing a move to the position the pointer already holds is an edge that never
            // happened, and it would show up in the log as motion.
            return this;
        }
        int steps = steps(distance);
        int fromX = x;
        int fromY = y;
        for (int i = 1; i <= steps; i++) {
            // Interpolated from the origin rather than accumulated, so rounding cannot drift the path off the
            // straight line and the last step lands exactly on the target rather than near it.
            double t = (double) i / steps;
            step(fromX + (int) Math.round(dx * t), fromY + (int) Math.round(dy * t));
            if (!pause()) {
                return this;
            }
        }
        return this;
    }

    /** Press {@code button} where the pointer is. */
    public Cursor press(MouseButton button) {
        publish(new InputEvent.ButtonPressed(button, x, y, 0), "pointer.press", button.name());
        return this;
    }

    /** Release {@code button} where the pointer is. */
    public Cursor release(MouseButton button) {
        publish(new InputEvent.ButtonReleased(button, x, y, 0), "pointer.release", button.name());
        return this;
    }

    /**
     * Travel to {@code (tx, ty)} and click there: the move, then press, then release.
     *
     * <p>The move is the point. A click is a gesture that ends somewhere, and everything the pointer passed
     * over on the way is part of what happened.
     */
    public Cursor click(int tx, int ty) {
        return click(tx, ty, MouseButton.LEFT);
    }

    /** As {@link #click(int, int)}, with the button named. */
    public Cursor click(int tx, int ty, MouseButton button) {
        moveTo(tx, ty);
        press(button);
        pause();
        release(button);
        return this;
    }

    /**
     * Press at the current position, travel to {@code (tx, ty)} with the button held, and release there.
     *
     * <p>A drag is a press, a path and a release, in that order and separated in time — the same three things
     * the framework's own drag detection is watching for (a distance threshold and a hold), which a synthesized
     * press-and-teleport never satisfies.
     */
    public Cursor drag(int tx, int ty, MouseButton button) {
        press(button);
        pause();
        moveTo(tx, ty);
        release(button);
        return this;
    }

    /** Wheel {@code (dx, dy)} notches where the pointer is. */
    public Cursor scroll(double dx, double dy) {
        publish(new InputEvent.Scrolled(dx, dy, x, y, 0), "pointer.scroll", dx + "," + dy);
        return this;
    }

    /** How many steps a path of {@code distance} pixels takes. See {@link #MAX_STEPS}. */
    private static int steps(double distance) {
        int wanted = (int) Math.ceil(distance / (SPEED_PX_PER_MS * STEP_MILLIS));
        return Math.max(1, Math.min(MAX_STEPS, wanted));
    }

    /** One position on the path: move there, say so, and remember it. */
    private void step(int nx, int ny) {
        int dx = nx - x;
        int dy = ny - y;
        x = nx;
        y = ny;
        // The deltas are real deltas. A backend that reports relative motion is what a captured pointer reads
        // during a drag, and zeroes there would be a lie the framework is entitled to believe.
        publish(new InputEvent.PointerMoved(nx, ny, dx, dy, 0), "pointer.move", nx + "," + ny);
    }

    /** Publish one edge on the ordinary input topic, and record it. */
    private void publish(InputEvent event, String kind, String detail) {
        bus.publish(InputTopics.INPUT, event);
        if (Probe.ON) {
            Probe.mark(Lane.INPUT, kind, detail);
        }
    }

    /**
     * Hold the cadence. Returns false if the thread was interrupted, which stops the path.
     *
     * <p>The flag is restored rather than swallowed: a cancelled run has to be able to unwind, and an
     * automation driver that quietly eats an interrupt is one that cannot be stopped.
     */
    private static boolean pause() {
        try {
            Thread.sleep(STEP_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
