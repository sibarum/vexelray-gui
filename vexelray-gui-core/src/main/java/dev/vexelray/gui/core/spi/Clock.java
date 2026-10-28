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
}
