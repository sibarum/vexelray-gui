package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.spi.Asked;
import dev.vexelray.gui.core.spi.Clock;
import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A part's asked half as the host runs it (docs/plans/frame-contract.md): asked once per frame for the tree clock's
 * present time, its offsets drawn and inherited, and the loop parked until the earliest time it says it is due.
 */
class AskedTest {

    /** A tree clock a test steps by hand; Timings land at once. */
    private static final class Stepped implements Clock {
        long nanos = 1_000L;

        @Override
        public void run(Timing timing, java.util.function.DoubleConsumer progress, Runnable done) {
            progress.accept(1d);
            done.run();
        }

        @Override
        public long nanos() {
            return nanos;
        }
    }

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static RetainedNode frame(Gui gui) {
        return Seams.of(gui).frame(400f, 300f, AskedTest::noText);
    }

    private static RetainedNode find(RetainedNode from, long id) {
        if (from.id == id) {
            return from;
        }
        for (RetainedNode c : from.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Node row(Gui gui) {
        return gui.box().width(Length.dp(40)).height(Length.dp(20));
    }

    @Test
    void aPartIsAskedForThePresentTimeOnEveryFrame() {
        Stepped clock = new Stepped();
        List<Long> askedAt = new ArrayList<>();
        try (Gui gui = new Gui(Atchung.create())) {
            Seams.of(gui).clock(clock);
            gui.root().children(row(gui));
            Seams.of(gui).ask((frame, out) -> {
                askedAt.add(frame.presentNanos());
                return Frame.NEVER;
            });
            frame(gui);
            clock.nanos = 5_000L;
            frame(gui);
            assertEquals(List.of(1_000L, 5_000L), askedAt);
        }
    }

    @Test
    void anOffsetMovesTheNodeAndItsSubtreeAndSurvivesALayout() {
        Stepped clock = new Stepped();
        try (Gui gui = new Gui(Atchung.create())) {
            Seams.of(gui).clock(clock);
            Node child = row(gui);
            Node strip = gui.column().children(child);
            gui.root().children(strip);
            float restX = find(frame(gui), child.id()).x;
            float[] dx = {0f};
            Seams.of(gui).ask((frame, out) -> {
                out.offset(strip, dx[0], 0f);
                return Frame.NEVER;
            });

            dx[0] = -30f;
            assertEquals(restX - 30f, find(frame(gui), child.id()).x, "carried by its parent's offset");
            assertTrue(gui.takeDrawNeeded(), "a changed offset is drawn");

            strip.children(child, row(gui));   // a layout: the offset is put back on what it lays out
            assertEquals(restX - 30f, find(frame(gui), child.id()).x);

            dx[0] = 0f;
            assertEquals(restX, find(frame(gui), child.id()).x, "zero is where layout put it");
        }
    }

    @Test
    void theLoopParksUntilTheEarliestPartIsDue() {
        Stepped clock = new Stepped();
        try (Gui gui = new Gui(Atchung.create())) {
            Seams.of(gui).clock(clock);
            gui.root().children(row(gui));
            long[] due = {Frame.NEVER};
            Asked part = (frame, out) -> due[0];
            Seams.of(gui).ask(part);

            frame(gui);
            assertEquals(Long.MAX_VALUE, gui.nanosUntilDue(), "at rest: no deadline of its own");

            due[0] = clock.nanos + 1_000_000_000L;
            frame(gui);
            assertEquals(1_000_000_000L, gui.nanosUntilDue(), "due in a second: park a second");

            clock.nanos += 400_000_000L;
            assertEquals(600_000_000L, gui.nanosUntilDue(), "measured on the tree's clock");

            due[0] = clock.nanos;
            frame(gui);
            assertEquals(0L, gui.nanosUntilDue(), "due now: the next frame at once");
            assertTrue(gui.frameOwed(), "and the frame is owed");

            Seams.of(gui).unask(part);
            frame(gui);
            assertEquals(Long.MAX_VALUE, gui.nanosUntilDue(), "no longer asked, no longer due");
        }
    }
}
