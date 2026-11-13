package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.spi.Clock;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A progress bar draws the part done without laying anything out, holds reports too small to see, sweeps while the size
 * is unknown, stands still under reduced motion, and asks for no frame it does not need.
 */
class ProgressTest {

    /** A tree clock the test steps by hand; motions it is asked to run land at once. */
    private static final class Stepped implements Clock {
        long now = 1_000_000_000L;

        @Override
        public void run(Timing timing, DoubleConsumer progress, Runnable done) {
            progress.accept(1.0);
            done.run();
        }

        @Override
        public long nanos() {
            return now;
        }
    }

    private static Progress mount(HeadlessGui h) {
        Progress p = new Progress(h.gui);
        p.node().width(Length.dp(400));
        h.gui.root().children(p.node());
        h.frame().frame();
        return p;
    }

    private static RetainedNode fill(HeadlessGui h, Progress p) {
        return h.retained(p.node()).children.get(0);
    }

    /** Where the fill's leading end is drawn, as a fraction of the track. */
    private static float drawnEnd(HeadlessGui h, Progress p) {
        RetainedNode f = fill(h, p);
        Rect r = p.node().layout().rect();
        return (f.x + f.translateX() * f.emPx + f.drawnX + f.w - r.x()) / r.w();
    }

    @Test
    void aValueIsDrawnWhereItLandsWithoutLayingTheFillOut() {
        try (HeadlessGui h = new HeadlessGui()) {
            Progress p = mount(h);
            assertEquals(0f, drawnEnd(h, p), 1e-3f, "a new bar shows nothing done");
            float laidX = fill(h, p).x;
            p.show(0.25);
            h.frame().frame();
            assertEquals(laidX, fill(h, p).x, 1e-3f, "the layout leaves the fill where it was");
            assertEquals(0.25f, drawnEnd(h, p), 1e-3f);
            p.show(7, 7);
            h.frame();
            assertEquals(1f, drawnEnd(h, p), 1e-3f);
        }
    }

    @Test
    void aReportTooSmallToSeeIsHeldAndTheEndsNeverAre() {
        try (HeadlessGui h = new HeadlessGui()) {
            Progress p = mount(h);
            p.show(0.5);
            h.frame();
            float before = fill(h, p).translateX();
            p.show(0.5 + 0.4 / 400.0);    // 0.4 px on a 400 px track
            h.frame();
            assertEquals(before, fill(h, p).translateX(), "held: under half a pixel");
            assertEquals(0.501, p.fraction(), 1e-6, "but it is still the value");
            p.show(0.5 + 2.0 / 400.0);
            h.frame();
            assertNotEquals(before, fill(h, p).translateX(), "two pixels are drawn");
            p.show(1.0 - 0.1 / 400.0);
            p.show(1.0);
            h.frame();
            assertEquals(1f, drawnEnd(h, p), 1e-4f, "the end is drawn exactly");
        }
    }

    @Test
    void anUnknownTotalSweepsAcrossAndRoundAgain() {
        try (HeadlessGui h = new HeadlessGui()) {
            Stepped clock = new Stepped();
            Seams.of(h.gui).clock(clock);
            Progress p = mount(h);
            p.show(3, 0);
            assertTrue(p.isIndeterminate());
            assertTrue(Double.isNaN(p.fraction()));
            h.frame().frame();
            float w = p.node().layout().rect().w();
            float first = fill(h, p).drawnX;
            assertEquals(-w * Progress.SWEEP, first, 0.5f, "the crossing starts unseen, off the left");
            assertTrue(h.gui.nanosUntilDue() <= Progress.PACE_NANOS, "and asks to be drawn again");
            float last = first;
            boolean moved = false;
            for (int i = 1; i <= 10; i++) {
                clock.now += Progress.PERIOD_NANOS / 20;
                h.frame();
                float x = fill(h, p).drawnX;
                assertTrue(x >= -w * Progress.SWEEP - 0.5f && x <= w + 0.5f, "within the crossing: " + x);
                assertTrue(x >= last, "half a period only moves right");
                moved |= x > last;
                last = x;
            }
            assertTrue(moved);
            assertEquals(Progress.sweepAt(0, w), Progress.sweepAt(Progress.PERIOD_NANOS, w), 1e-3f, "round again");
        }
    }

    @Test
    void aValueEndsTheSweepAndPutsTheFillBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            Seams.of(h.gui).clock(new Stepped());
            Progress p = mount(h);
            p.indeterminate();
            h.frame().frame();
            assertNotEquals(0f, fill(h, p).drawnX);
            p.show(0.6);
            h.frame().frame();
            assertFalse(p.isIndeterminate());
            assertEquals(0f, fill(h, p).drawnX);
            assertEquals(1f, fill(h, p).drawnScale);
            assertEquals(0.6f, drawnEnd(h, p), 1e-3f);
            assertEquals(Long.MAX_VALUE, h.gui.nanosUntilDue(), "a determinate bar at rest asks for nothing");
        }
    }

    @Test
    void reducedMotionWashesTheTrackAndStandsStill() {
        try (HeadlessGui h = new HeadlessGui()) {
            Stepped clock = new Stepped();
            Seams.of(h.gui).clock(clock);
            h.gui.reducedMotion(true);
            Progress p = mount(h);
            p.indeterminate();
            h.frame().frame();
            RetainedNode f = fill(h, p);
            float w = p.node().layout().rect().w();
            assertEquals(1f / Progress.SWEEP, f.drawnScale, 1e-4f, "scaled to the whole track");
            assertEquals((w - w * Progress.SWEEP) / 2f, f.drawnX, 0.5f, "about its centre");
            assertEquals(Progress.WASH, f.drawnOpacity, 1e-4f);
            assertEquals(Long.MAX_VALUE, h.gui.nanosUntilDue(), "and asks for no frame");
            float x = f.drawnX;
            clock.now += Progress.PERIOD_NANOS / 3;
            h.frame();
            assertEquals(x, fill(h, p).drawnX);
        }
    }

    private static RetainedNode glint(HeadlessGui h, Progress p) {
        return fill(h, p).children.get(0);
    }

    @Test
    void aStateRecoloursTheFillAndLeavesTheValue() {
        try (HeadlessGui h = new HeadlessGui()) {
            Progress p = mount(h);
            p.show(0.4).state(Progress.State.FAILED);
            h.frame().frame();
            assertEquals(Progress.State.FAILED, p.state());
            assertEquals(Role.DANGER, fill(h, p).background());
            assertEquals(0.4f, drawnEnd(h, p), 1e-3f, "it failed where it got to");
            p.state(Progress.State.PAUSED);
            h.frame();
            assertEquals(Role.CAUTION, fill(h, p).background());
            p.state(null);
            h.frame();
            assertEquals(Progress.State.IDLE, p.state(), "null is idle");
            assertEquals(Role.ACCENT, fill(h, p).background());
        }
    }

    @Test
    void aRunningBarGlintsAcrossTheWorkDoneThenRests() {
        try (HeadlessGui h = new HeadlessGui()) {
            Stepped clock = new Stepped();
            Seams.of(h.gui).clock(clock);
            Progress p = mount(h);
            p.show(0.5).state(Progress.State.RUNNING);
            h.frame().frame();
            float w = p.node().layout().rect().w();
            assertTrue(glint(h, p).visible(), "shown while running");
            assertEquals(0.5f * w - w * Progress.GLINT, glint(h, p).drawnX, 0.5f,
                    "starts just before the part done shows");
            assertTrue(h.gui.nanosUntilDue() <= Progress.PACE_NANOS);
            clock.now += Progress.GLINT_CROSS_NANOS / 2;
            h.frame();
            float mid = glint(h, p).drawnX;
            assertTrue(mid > 0.5f * w - w * Progress.GLINT && mid < w, "crossing: " + mid);
            clock.now += Progress.GLINT_CROSS_NANOS / 2 + 1;
            h.frame().frame();
            assertEquals(w, glint(h, p).drawnX, 0.5f, "resting past the end");
            long due = h.gui.nanosUntilDue();
            assertTrue(due > Progress.GLINT_REST_NANOS / 2 && due <= Progress.GLINT_REST_NANOS,
                    "and asks nothing until the next crossing: " + due);
            p.state(Progress.State.IDLE);
            clock.now += 1_000_000_000L;
            h.frame().frame();
            assertFalse(glint(h, p).visible());
            assertEquals(Long.MAX_VALUE, h.gui.nanosUntilDue(), "idle asks for nothing");
        }
    }

    @Test
    void aPausedSweepStandsStillAsAWash() {
        try (HeadlessGui h = new HeadlessGui()) {
            Stepped clock = new Stepped();
            Seams.of(h.gui).clock(clock);
            Progress p = mount(h);
            p.indeterminate().state(Progress.State.PAUSED);
            h.frame().frame();
            clock.now += 1_000_000_000L;
            h.frame().frame();
            assertEquals(1f / Progress.SWEEP, fill(h, p).drawnScale, 1e-4f);
            assertEquals(Progress.WASH, fill(h, p).drawnOpacity, 1e-4f);
            assertEquals(Long.MAX_VALUE, h.gui.nanosUntilDue());
            p.state(Progress.State.RUNNING);
            h.frame();
            assertEquals(1f, fill(h, p).drawnScale, "running again, it sweeps");
            assertTrue(h.gui.nanosUntilDue() <= Progress.PACE_NANOS);
        }
    }

    @Test
    void reducedMotionRunsWithNoGlint() {
        try (HeadlessGui h = new HeadlessGui()) {
            Stepped clock = new Stepped();
            Seams.of(h.gui).clock(clock);
            h.gui.reducedMotion(true);
            Progress p = mount(h);
            p.show(0.5).state(Progress.State.RUNNING);
            h.frame().frame();
            clock.now += 1_000_000_000L;
            h.frame().frame();
            assertEquals(p.node().layout().rect().w(), glint(h, p).drawnX, 0.5f, "parked past the end");
            assertEquals(Long.MAX_VALUE, h.gui.nanosUntilDue());
        }
    }

    @Test
    void aSweepOutOfTheTreeAsksForNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Seams.of(h.gui).clock(new Stepped());
            Progress p = mount(h);
            p.indeterminate();
            h.frame().frame();
            assertTrue(h.gui.nanosUntilDue() <= Progress.PACE_NANOS);
            h.gui.root().arrange();
            h.frame().frame();
            assertEquals(Long.MAX_VALUE, h.gui.nanosUntilDue());
        }
    }
}
