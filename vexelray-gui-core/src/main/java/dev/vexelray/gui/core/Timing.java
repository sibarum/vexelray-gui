package dev.vexelray.gui.core;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a widget's motion takes and how it eases: a tempo, stated as data. A widget that animates is handed one
 * ({@code TreeView.motion}, {@code Tabs.slide}, {@code Cues}) and runs it on its tree's clock, which the host installs
 * ({@link dev.vexelray.gui.core.spi.Seams#clock}). With no clock installed, or with motion reduced, it lands at once.
 *
 * <p><b>Why a value and not a function</b> (vexelray-framework's {@code docs/threading.md}, T7.2): the alternative is
 * a function the widget calls with its progress, which is code a view hands the GUI to run in the frame. A tempo is
 * all a view has to say; running time is the host's.
 *
 * @param duration how long, positive
 * @param curve    how it eases
 */
public record Timing(Duration duration, Curve curve) {

    public Timing {
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(curve, "curve");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("a timing takes some time: " + duration);
        }
    }

    /** {@code millis} long, eased by {@code curve}. */
    public static Timing millis(long millis, Curve curve) {
        return new Timing(Duration.ofMillis(millis), curve);
    }

    /** How a motion eases. The clock maps each to its own curve. */
    public enum Curve {
        /** At one speed throughout: a dissolve, which has no place to arrive at. */
        LINEAR,
        /** Fast, then settling: a distance covered, decelerating into where it stops. */
        OUT_CUBIC,
        /** Easing in and out: a move with a start and an end both seen. */
        IN_OUT_CUBIC
    }
}
