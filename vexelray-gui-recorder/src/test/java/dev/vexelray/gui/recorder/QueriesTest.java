package dev.vexelray.gui.recorder;

import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each query's record, against rows shaped like the ones the gui demo's loop really writes (the frame span, the
 * window's stages, the presenter's, then frame.present, loop.park and the wait), fed straight into a ring.
 */
class QueriesTest {

    /** Rows into a ring, with the frame stamp and the clock under the test's control. */
    private static final class Feed {
        long frame;
        long seq = 1;
        long ms;
        final Recorder ring = new Recorder(1 << 12, () -> frame);

        Feed row(String thread, Lane lane, String kind, String detail) {
            ring.row(seq++, ms * 1_000_000L, 0, thread, lane, kind, detail);
            return this;
        }

        Feed main(Lane lane, String kind, String detail) {
            return row("main", lane, kind, detail);
        }

        /** One frame of the loop: its work, then present, park and the wait, with the frame span {@code took}. */
        Feed frame(long f, String took, String park, String waited) {
            frame = f;
            main(Lane.FRAME, "before frame", "1.00ms");
            main(Lane.INPUT, "edges", "0");
            main(Lane.INPUT, "snapshot", "200.0us");
            main(Lane.TIME, "batch", "300.0us");
            main(Lane.LAYOUT, "dispatch input", "50.0us");
            main(Lane.BUS, "pump drain", "100.0us");
            main(Lane.BUS, "pump delivered", "3");
            main(Lane.LAYOUT, "flex layout", "2.00ms");
            main(Lane.LAYOUT, "resolve geometry", "500.0us");
            main(Lane.LAYOUT, "tree update", "3.00ms");
            main(Lane.DRAW, "emit canvas", "1.50ms");
            main(Lane.GPU, "wait fence", "200.0us");
            main(Lane.GPU, "queue submit", "100.0us");
            main(Lane.GPU, "dxgi copy+present", "400.0us");
            main(Lane.GPU, "present frame", "2.50ms");
            ms += 7;
            main(Lane.FRAME, "frame", took);
            main(Lane.FRAME, "frame.present", "#" + f);
            main(Lane.FRAME, "loop.park", park);
            if (waited != null) {
                ms += Scan.nanos(waited) / 1_000_000L;
                main(Lane.FRAME, "wait for events", waited);
            }
            return this;
        }
    }

    /** Frame 0 (startup), then 1..4; frame 3 is woken by a worker's mutation, frame 2 is the slow one. */
    private static Feed run() {
        return run(null);
    }

    /** The same run, with an {@code agent.mark} written during frame 2 when {@code mark} is given. */
    private static Feed run(String mark) {
        Feed f = new Feed();
        f.main(Lane.APP, "log.info", "starting");
        f.frame(1, "7.00ms", "10ms", "10.00ms");
        f.frame(2, "9.00ms", "10ms", "4.00ms");
        if (mark != null) {
            f.row("vexelray-automation-1", Lane.APP, "agent.mark", mark);
        }
        f.row("worker", Lane.FRAME, "wake", "mutation on Gui@1");
        f.row("worker", Lane.BUS, "app.saves", "40.0us");
        f.frame(3, "7.00ms", "forever", "500.00ms");
        f.frame(4, "7.00ms", "10ms", null);
        return f;
    }

    @Test
    void whyExplainsAFrameFromItsRowsAndTheFrameBefore() {
        Feed f = run();
        FrameExplanation e = Queries.why(f.ring, 3);

        assertEquals(3, e.frame());
        assertEquals(7000, e.tookUs());
        assertEquals("yes", e.drawn());
        assertEquals("yes", e.presented());
        // Posted during frame 2, so owed to frame 3.
        assertEquals(new FrameExplanation.Wake("wake", "mutation on Gui@1", 1, "worker"), e.woke());
        assertEquals(List.of(
                new FrameExplanation.Stage("before", "frame", 1000),
                new FrameExplanation.Stage("update", "layout", 3000),
                new FrameExplanation.Stage("draw", "gpu", 2500),
                new FrameExplanation.Stage("other", "frame", 500)), e.stages());
        assertEquals(new FrameExplanation.Stage("layout", "layout", 2000), e.inside().get(4));
        assertEquals(new FrameExplanation.Stage("present", "gpu", 400), e.inside().get(9));
        assertEquals(List.of(new FrameExplanation.LayoutPass("flex layout", 2000)), e.passes());
        assertEquals(3, e.drained());
        assertEquals(List.of(new FrameExplanation.Published("app.saves", 1, "worker")), e.published());
        assertEquals("forever", e.park());
        assertEquals(500_000, e.waitedUs());
        assertEquals("rows --frame 3 --lane layout", e.see());
        assertFalse(e.unrecorded().isEmpty());
        assertEquals(0, e.span().lost());
        assertTrue(e.span().fromNs() < e.atNs(), "the span covers the frame before, for what woke it");
    }

    @Test
    void whatWokeAFrameIsReadFromTheParkBeforeWhenNoWakeSaysSo() {
        Feed f = run();
        // Frame 1 parked on 10ms and waited all of it.
        assertEquals("timeout", Queries.why(f.ring, 2).woke().kind());
        // Frame 3 parked forever and was ended after 500ms by nothing a row names.
        FrameExplanation.Wake w = Queries.why(f.ring, 4).woke();
        assertEquals("-", w.kind());
        assertTrue(w.detail().contains("no row names"), w.detail());
        // Frame 1 has no frame before it in the ring: frame 0 is startup, not a frame.
        assertEquals("-", Queries.why(f.ring, 1).woke().kind());
    }

    @Test
    void framesListsTheMostRecentAndTheWorst() {
        Feed f = run();
        FrameList last = Queries.lastFrames(f.ring, 2);
        assertEquals("last", last.order());
        assertEquals(List.of(3L, 4L), last.frames().stream().map(FrameSummary::frame).toList());
        assertEquals(2, last.more());
        assertEquals(1, last.firstFrame());
        assertEquals(4, last.lastFrame());

        FrameList worst = Queries.worstFrames(f.ring, 1);
        assertEquals(2, worst.frames().get(0).frame());
        assertEquals(9000, worst.frames().get(0).tookUs());
        assertEquals("other", worst.frames().get(0).stage(), "9ms less 6.5ms of named spans: the loop's own time");
        assertEquals(3, worst.more());

        FrameSummary three = last.frames().get(0);
        assertEquals("wake", three.woke());
        assertEquals("layout", three.stage());
        assertEquals(2000, three.stageUs());
        assertEquals("forever", three.park());
    }

    @Test
    void framesSinceAMarkStartsAfterTheFrameTheMarkWasWrittenIn() {
        Feed f = run("before-save");
        FrameList since = Queries.framesSince(f.ring, "BEFORE", 20);
        assertEquals(List.of(3L, 4L), since.frames().stream().map(FrameSummary::frame).toList());
        assertThrows(IllegalArgumentException.class, () -> Queries.framesSince(f.ring, "nothing", 20));
    }

    @Test
    void aFrameNotInTheRingIsRefusedNamingWhatIs() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Queries.why(run().ring, 99));
        assertEquals("frame 99 is not in the ring, which holds complete frames 1..4", e.getMessage());
    }

    @Test
    void withoutAFrameCounterTheFrameVerbsSaySo() {
        Recorder unstamped = new Recorder(16, Recorder.NO_FRAMES);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Queries.why(unstamped, 1));
        assertTrue(e.getMessage().contains("LoopCounters"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> Queries.lastFrames(unstamped, 5));
    }

    @Test
    void rowsFiltersAsCsvviewDoesAndSaysHowManyItLeftOut() {
        Feed f = run();
        RowList gpu = Queries.rows(f.ring, new RowFilter(List.of("gpu"), null, null, null, 3, 50));
        assertEquals(List.of("wait fence", "queue submit", "dxgi copy+present", "present frame"),
                gpu.rows().stream().map(RecordedRow::kind).toList());
        assertTrue(gpu.rows().stream().allMatch(r -> r.frame() == 3));
        assertEquals(0, gpu.more());

        RowList two = Queries.rows(f.ring, new RowFilter(List.of(), "FRAME.PRESENT", null, null, -1, 2));
        assertEquals(List.of("#3", "#4"), two.rows().stream().map(RecordedRow::detail).toList());
        assertEquals(2, two.more());

        RowList grep = Queries.rows(f.ring, new RowFilter(List.of(), null, null, "gui@1", -1, 50));
        assertEquals(1, grep.rows().size());
        assertEquals("worker", grep.rows().get(0).thread());
        assertEquals(f.seq - 1, grep.span().rows());
    }

    @Test
    void gapsAreJudgedByTheSameRuleAsCsvview() {
        Feed f = run();
        GapList g = Queries.gaps(f.ring, 100);
        // Only frame 3 -> 4 is over 100ms: a 500ms park on forever, so expected.
        assertEquals(1, g.gaps().size());
        Gap gap = g.gaps().get(0);
        assertEquals("expected", gap.verdict());
        assertFalse(gap.stall());
        assertEquals(3, gap.from().frame());
        assertEquals("forever", gap.park().detail());
        assertEquals("present frame", gap.last().kind(), "the last row strictly earlier than the closing present");
        assertEquals(0, g.stalls());

        // A loop that parks on 16ms and is gone for 800ms is a stall, and the row before the silence is named.
        f.frame = 5;
        f.main(Lane.FRAME, "frame.present", "#5");
        f.main(Lane.FRAME, "loop.park", "16ms");
        f.ms += 400;
        f.row("worker", Lane.APP, "save", "writing 40MB");
        f.ms += 400;
        f.main(Lane.FRAME, "frame.present", "#6");
        GapList stalled = Queries.gaps(f.ring, 100);
        Gap last = stalled.gaps().get(stalled.gaps().size() - 1);
        assertTrue(last.stall());
        assertEquals("short-park", last.verdict());
        assertEquals("writing 40MB", last.last().detail());
        assertEquals(1, stalled.stalls());
    }

    @Test
    void theStatusSaysWhatIsHeldAndWhatWasLost() {
        Feed f = run();
        f.seq += 3;   // three rows the probe numbered and this ring never got
        f.main(Lane.APP, "log.info", "after the hole");
        RecorderStatus s = Queries.status(f.ring);
        assertEquals(f.seq - 1 - 3, s.rows());
        assertEquals(1 << 12, s.capacityRows());
        assertEquals(3, s.span().lost());
        assertEquals(3, s.lostTotal());
        assertEquals(1, s.firstFrame());
        assertEquals(4, s.lastFrame());
        assertTrue(s.stamped());
        assertEquals((1L << 12) * Recorder.bytesPerSlot(), s.ringBytes());
        assertTrue(s.rateRowsPerS() > 0);
    }

    @Test
    void anEmptyRingAnswersWithAnEmptySpan() {
        Recorder r = new Recorder(16, () -> 1);
        assertTrue(Queries.status(r).span().isEmpty());
        assertTrue(Queries.rows(r, RowFilter.ALL).rows().isEmpty());
        assertTrue(Queries.gaps(r, 100).gaps().isEmpty());
        assertTrue(Queries.lastFrames(r, 5).frames().isEmpty());
    }

    @Test
    void durationsAreReadInEveryUnitTheProbeWrites() {
        assertEquals(900, Scan.nanos("900ns"));
        assertEquals(48_500, Scan.nanos("48.5us"));
        assertEquals(1_270_000, Scan.nanos("1.27ms"));
        assertEquals(2_500_000_000L, Scan.nanos("2.50s"));
        assertEquals(-1, Scan.nanos("3"));
        assertEquals(-1, Scan.nanos("forever"));
        assertEquals(-1, Scan.nanos("mutation on Gui@1"));
    }
}
