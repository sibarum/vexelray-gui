package dev.vexelray.gui.recorder;

import dev.vexelray.gui.recorder.FrameExplanation.LayoutPass;
import dev.vexelray.gui.recorder.FrameExplanation.Published;
import dev.vexelray.gui.recorder.FrameExplanation.Stage;
import dev.vexelray.gui.recorder.FrameExplanation.Wake;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each reply's shape, from records built by hand: a reply is a pure function of its record, so its text can be
 * asserted exactly. The rules are flight-recorder.md §2's — ok and key=value first, units in the keys, over= and
 * lost= always, more= on every list, a header row before any table, and why ending with a see line.
 */
class RepliesTest {

    private static final Span SPAN = new Span(1_578_000_000L, 4_701_000_000L, 11148, 2, 10, 11159);

    private static RecordedRow row(long seq, long ms, long frame, String thread, String lane, String kind,
                                   String detail) {
        return new RecordedRow(seq, ms * 1_000_000L, 1_760_000_000_000_000_000L + ms * 1_000_000L, frame, thread,
                lane, kind, detail);
    }

    @Test
    void recorderIsOneLineOfFacts() {
        RecorderStatus s = new RecorderStatus(11148, 262144, SPAN, 5, 3135, List.of("frame", "layout"), 1, 87, true,
                262144L * 48, 0);
        assertEquals("ok rows=11148 capacity_rows=262144 over=+1.578s..+4.701s lost=2 lost_total=5"
                        + " rate_rows_per_s=3135 frames=1..87 frame_stamp=yes ring_mb=12.0 sink_failures=0"
                        + " lanes=frame,layout",
                Replies.text(s));
    }

    @Test
    void recorderWithoutAFrameCounterSaysSo() {
        RecorderStatus s = new RecorderStatus(0, 16, Span.EMPTY, 0, 0, List.of(), -1, -1, false, 768, 0);
        assertEquals("ok rows=0 capacity_rows=16 over=- lost=0 lost_total=0 rate_rows_per_s=0 frames=-"
                + " frame_stamp=no ring_mb=0.0 sink_failures=0 lanes=-", Replies.text(s));
    }

    @Test
    void rowsAreCorrelationLogLinesWithTheFrameLast() {
        RowList r = new RowList(SPAN, RowFilter.ALL, List.of(
                row(7, 1000, 3, "main", "frame", "loop.park", "10ms"),
                row(8, 1001, 3, "worker", "app", "note", "a, \"quoted\"\nline")), 41);
        assertEquals(String.join("\n",
                "ok rows=2 over=+1.578s..+4.701s lost=2 more=41",
                "seq,t_mono_ns,t_wall,thread,lane,kind,detail,frame",
                "7,1000000000,2025-10-09T08:53:21Z,main,frame,loop.park,10ms,3",
                "8,1001000000,2025-10-09T08:53:21.001Z,worker,app,note,\"a, \"\"quoted\"\" line\",3"),
                Replies.text(r));
    }

    @Test
    void noRowsMeansNoHeader() {
        assertEquals("ok rows=0 over=- lost=0 more=0",
                Replies.text(new RowList(Span.EMPTY, RowFilter.ALL, List.of(), 0)));
    }

    @Test
    void gapsAreATableWithTheVerdictAndTheSuspect() {
        Gap g = new Gap(row(1, 1733, 4, "main", "frame", "frame.present", "#4"),
                row(2, 1935, 5, "main", "frame", "frame", "2.29ms"),
                row(3, 1936, 5, "main", "frame", "frame.present", "#5"),
                row(4, 1733, 4, "main", "frame", "loop.park", "200ms"), 203_100_000L, "expected", false);
        assertEquals(String.join("\n",
                "ok gaps=1 stalls=0 threshold_ms=100 over=+1.578s..+4.701s lost=2 more=0",
                "frame  at       gap_ms  verdict   park   last",
                "4      +1.733s  203.1   expected  200ms  main frame frame 2.29ms"),
                Replies.text(new GapList(SPAN, 100, List.of(g), 0, 0)));
    }

    @Test
    void framesAreATableWithUnitsInTheHeader() {
        FrameList f = new FrameList(SPAN, "worst", List.of(
                new FrameSummary(2, 1_684_000_000L, 32350, "emit", 21530, "wake", "yes", "yes", "0ms"),
                new FrameSummary(88, 4_737_000_000L, 728, "-", -1, "timeout", "no", "-", "200ms")), 84, 1, 89);
        assertEquals(String.join("\n",
                "ok frames=2 order=worst held=1..89 over=+1.578s..+4.701s lost=2 more=84",
                "frame  at       took_us  stage  stage_us  woke     drawn  presented  park",
                "2      +1.684s  32350    emit   21530     wake     yes    yes        0ms",
                "88     +4.737s  728      -      -         timeout  no     -          200ms"),
                Replies.text(f));
    }

    @Test
    void whyIsLabelledLinesEndingWithWhereToLook() {
        FrameExplanation e = new FrameExplanation(73, 4_309_000_000L, 5070, "yes", "yes",
                new Wake("wake", "paint motion on Gui@2133c8f8", 1, "main"),
                List.of(new Stage("before", "frame", 700), new Stage("update", "layout", 2150),
                        new Stage("draw", "gpu", 2130), new Stage("other", "frame", 90)),
                List.of(new Stage("layout", "layout", 646), new Stage("emit", "draw", -1)),
                List.of(new LayoutPass("flex layout", 646)), 9,
                List.of(new Published("vexelray.gui.clicks", 1, "main")), "11ms", 11330, 0,
                List.of("gc (the probe has no gc rows)"), "rows --frame 73 --lane layout",
                new Span(4_293_000_000L, 4_325_000_000L, 300, 0, 1, 300));
        String text = Replies.text(e);
        assertEquals(String.join("\n",
                "ok frame=73 at=+4.309s took_us=5070 drawn=yes presented=yes over=+4.293s..+4.325s lost=0",
                "woke    wake: paint motion on Gui@2133c8f8 (main; 1 wake mark)",
                "stage   before=700 update=2150 draw=2130 other=90   (us; together they make up took_us)",
                "inside  layout=646 emit=-   (us)",
                "layout  1 pass: flex layout 646us",
                "bus     drained=9 published=vexelray.gui.clicks x1 (main)",
                "park    11ms waited_us=11330",
                "also    slow_spans=0",
                "unknown gc (the probe has no gc rows)",
                "see     rows --frame 73 --lane layout"), text);
        assertTrue(text.substring(text.lastIndexOf('\n') + 1).startsWith("see "));
    }

    @Test
    void whyWithTheBusOffAndNoWakeSaysWhyNot() {
        FrameExplanation e = new FrameExplanation(5, 0, 100, "-", "-",
                new Wake("-", "frame 4 is not in the ring", 0, "-"), List.of(), List.of(), List.of(), -1, List.of(),
                "-", -1, 0, List.of(), "rows --frame 5", Span.EMPTY);
        String text = Replies.text(e);
        assertTrue(text.contains("\nwoke    -: frame 4 is not in the ring\n"), text);
        assertTrue(text.contains("\nbus     drained=- published=-   (the bus lane is off)\n"), text);
        assertTrue(text.contains("\nlayout  0 passes\n"), text);
        assertTrue(text.contains("\npark    - waited_us=-\n"), text);
    }
}
