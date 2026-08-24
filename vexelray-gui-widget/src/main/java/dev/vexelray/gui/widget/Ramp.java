package dev.vexelray.gui.widget;

import java.util.function.DoubleConsumer;

/**
 * Where a widget's motion gets its time: drive {@code progress} from 0 to 1, then call {@code done}.
 *
 * <p>Sole purpose is to keep this module clock-free. A widget that animates has to be told <em>when</em>, and the
 * clock enters at the application edge — {@code vexelray-gui-krono} is downstream of here, so naming it would
 * invert the layering. Both types crossing this seam are JDK types, so {@code KronoGui.ramp} satisfies it without
 * either module knowing the other exists:
 *
 * <pre>{@code
 * tabs.transition(Tabs.slide((p, done) -> krono.ramp(ms(200), Ease.LINEAR, p, done)));
 * }</pre>
 *
 * <p>A widget with no ramp installed does whatever it did before there was motion — which is what makes every
 * animation here opt-in, and makes the reduced-motion path simply not calling it.
 */
@FunctionalInterface
public interface Ramp {

    /**
     * Drive {@code progress} over [0,1] and call {@code done} once at the end.
     *
     * <p>Deliver both endpoints exactly, and deliver the last one on a frame that is actually presented. A ramp
     * sampled only at frame boundaries is already a frame into its duration by the first of them, so a consumer
     * that never sees 0 begins by jumping to wherever that first sample landed. And a consumer that tears down on
     * arrival — hides a node, drops an overlay — runs in the same batch as the final sample unless something
     * separates them, so the end value gets written and overwritten before anything reaches the screen, and the
     * last thing seen is the second-to-last sample.
     */
    void run(DoubleConsumer progress, Runnable done);
}
