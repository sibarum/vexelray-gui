package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.spi.Seams;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tree's clock for a test that steps time itself: each {@link Ramp} the test drives is given a {@link Timing} of its
 * own, and the clock installed on the tree runs a timing by handing its progress and done to that ramp. A widget is
 * handed a timing, as a view hands it one, and the test still decides every sample.
 */
final class TestClocks {

    private static final Map<Gui, Map<Timing, Ramp>> DRIVEN = new WeakHashMap<>();
    private static final AtomicLong NEXT = new AtomicLong();

    private TestClocks() {
    }

    /** A timing in {@code h}'s tree that {@code ramp} drives. */
    static Timing drive(HeadlessGui h, Ramp ramp) {
        Map<Timing, Ramp> ramps;
        synchronized (DRIVEN) {
            ramps = DRIVEN.computeIfAbsent(h.gui, gui -> {
                Map<Timing, Ramp> fresh = new ConcurrentHashMap<>();
                Seams.of(gui).clock((timing, progress, done) -> fresh.get(timing).run(progress, done));
                return fresh;
            });
        }
        Timing timing = Timing.millis(NEXT.incrementAndGet(), Timing.Curve.LINEAR);
        ramps.put(timing, ramp);
        return timing;
    }
}
