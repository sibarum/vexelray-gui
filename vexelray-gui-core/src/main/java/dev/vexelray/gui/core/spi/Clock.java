package dev.vexelray.gui.core.spi;

import dev.vexelray.gui.core.Timing;

import java.util.function.DoubleConsumer;

/**
 * The tree's time, installed by the host ({@link Seams#clock(Clock)}): what runs a widget's {@link Timing}. The widget
 * library is clock-free, and a view states a tempo rather than driving one, so this is the one place a motion meets a
 * clock ({@code vexelray-gui-krono}'s {@code KronoGui} installs itself here when it attaches).
 */
@FunctionalInterface
public interface Clock {

    /**
     * Drive {@code progress} over [0,1] for {@code timing}, then call {@code done} once.
     *
     * <p>Deliver both endpoints exactly, and the last on a frame that is actually presented. A ramp sampled only at
     * frame boundaries is already a frame into its duration by the first of them, so a consumer that never sees 0
     * begins by jumping to wherever that first sample landed; and a consumer that tears down on arrival runs in the
     * same batch as the final sample unless something separates them, so the last thing seen would be the
     * second-to-last sample.
     */
    void run(Timing timing, DoubleConsumer progress, Runnable done);

    /**
     * The tree's present time, in nanoseconds: what a frame is drawn for ({@link dev.vexelray.gui.core.Frame}). Only
     * differences between two readings mean anything. A clock a test or a capture steps by hand answers its own time,
     * so what is drawn depends on the steps and not on how fast the machine ran them; the default is the wall's.
     */
    default long nanos() {
        return System.nanoTime();
    }

    /**
     * The same moment on the machine's monotonic clock, {@code System.nanoTime()}'s: what another process on this
     * machine stamps its own events with ({@link dev.vexelray.gui.core.Frame#monoNanos}). Every clock answers the wall's
     * unless a test steps it by hand; {@link #nanos} may not, since a logical clock counts from its own zero and falls
     * behind after a stall.
     */
    default long monoNanos() {
        return System.nanoTime();
    }
}
