package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.spi.Clock;
import dev.vexelray.gui.core.spi.Seams;

import java.util.function.DoubleConsumer;

/**
 * Where a widget's motion gets its time, inside the widget library: drive {@code progress} from 0 to 1, then call
 * {@code done}. A view never writes one; it hands a widget a {@link Timing}, and the widget makes its ramp with
 * {@link #of}, which runs on the tree's {@link Clock} (vexelray-framework's threading.md, T7.2).
 *
 * <p>A widget with no timing does whatever it did before there was motion, which is what makes every animation here
 * opt-in, and makes the reduced-motion path the clock's business.
 */
@FunctionalInterface
interface Ramp {

    /**
     * Drive {@code progress} over [0,1] and call {@code done} once at the end. See {@link Clock#run} for the two
     * endpoints and why each is delivered exactly.
     */
    void run(DoubleConsumer progress, Runnable done);

    /**
     * The ramp {@code timing} makes in {@code gui}'s tree, or null for none: run on the tree's clock, asked for when the
     * ramp runs, so a clock installed after the widget was built still drives it. With no clock the motion lands at
     * once: the last value, then done.
     */
    static Ramp of(Gui gui, Timing timing) {
        if (timing == null) {
            return null;
        }
        return (progress, done) -> {
            Clock clock = Seams.of(gui).clock();
            if (clock == null) {
                progress.accept(1.0);
                done.run();
            } else {
                clock.run(timing, progress, done);
            }
        };
    }
}
