package dev.vexelray.gui.recorder;

import sibarum.probe.CsvView;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The questions asked of the ring, each answered as a record and nothing else. Formatting is {@link Replies}'s
 * job: the same record can be printed as a text reply, or carried as it is by another transport.
 *
 * <p>Each query reads the ring as it is when it starts, chunk by chunk ({@link Recorder#read}), so it never
 * blocks the application and never copies the whole ring at once. Every answer carries the {@link Span} it read.
 */
public final class Queries {

    private Queries() {
    }

    /** {@code recorder}: what the ring holds. */
    public static RecorderStatus status(Recorder recorder) {
        // The newest row first, so the rate can be counted in the same pass as everything else.
        Rows newest = recorder.read(Math.max(0, recorder.published() - 1), new Rows(1));
        long since = newest.isEmpty() ? Long.MAX_VALUE : newest.monoNanos(0) - 1_000_000_000L;
        long[] tally = {0, Long.MAX_VALUE, -1};   // rows in the last second, first frame, last frame
        Span span = Scan.all(recorder, (rows, i) -> {
            if (rows.monoNanos(i) >= since) {
                tally[0]++;
            }
            long f = rows.frame(i);
            if (f > 0) {
                tally[1] = Math.min(tally[1], f);
                tally[2] = Math.max(tally[2], f);
            }
        });
        List<String> lanes = new ArrayList<>();
        for (Lane l : Lane.values()) {
            if (Probe.on(l)) {
                lanes.add(l.key());
            }
        }
        boolean stamped = recorder.stamped() && tally[2] > 0;
        return new RecorderStatus(span.rows(), recorder.capacity(), span, recorder.lost(), tally[0], lanes,
                stamped ? tally[1] : -1, stamped ? tally[2] : -1, recorder.stamped(),
                (long) recorder.capacity() * Recorder.bytesPerSlot(), Probe.liveSinkFailures());
    }

    /** {@code rows}: the most recent rows matching {@code filter}. */
    public static RowList rows(Recorder recorder, RowFilter filter) {
        List<String> lanes = filter.lanes().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        ArrayDeque<RecordedRow> kept = new ArrayDeque<>(Math.min(filter.limit(), 1_024));
        long[] matched = {0};
        Span span = Scan.all(recorder, (rows, i) -> {
            if (!lanes.isEmpty() && !lanes.contains(rows.lane(i))) {
                return;
            }
            if (filter.frame() >= 0 && rows.frame(i) != filter.frame()) {
                return;
            }
            if (filter.kind() != null && !Scan.contains(rows.kind(i), filter.kind())) {
                return;
            }
            if (filter.thread() != null && !Scan.contains(rows.thread(i), filter.thread())) {
                return;
            }
            String g = filter.grep();
            if (g != null && !Scan.contains(rows.thread(i), g) && !Scan.contains(rows.lane(i), g)
                    && !Scan.contains(rows.kind(i), g) && !Scan.contains(rows.detail(i), g)) {
                return;
            }
            matched[0]++;
            kept.addLast(rows.row(i));
            if (kept.size() > filter.limit()) {
                kept.removeFirst();
            }
        });
        return new RowList(span, filter, new ArrayList<>(kept), matched[0] - kept.size());
    }

    /**
     * {@code gaps}: silences between frames of at least {@code thresholdMs}, judged by {@link CsvView#gapsOf} — the
     * rule {@code csvview --gaps} prints, applied to rows that never were a file.
     */
    public static GapList gaps(Recorder recorder, long thresholdMs) {
        // The rule reads frame.present and loop.park rows, and the last row inside each gap; the row just before
        // each frame.present (in time) is that last row. Keeping only those keeps the answer identical while
        // materialising a few rows per frame instead of the whole ring.
        List<Held> held = new ArrayList<>();
        Slot previous = new Slot();
        Slot earlier = new Slot();   // the latest row strictly earlier in time than the one being read
        Span span = Scan.all(recorder, (rows, i) -> {
            long mono = rows.monoNanos(i);
            if (previous.full && previous.monoNs < mono) {
                earlier.copy(previous);
            }
            String kind = rows.kind(i);
            boolean frame = kind.equals("frame.present");
            if (frame && earlier.full && !isHeld(held, earlier.seq)) {
                insertBySeq(held, new Held(earlier.row()));
            }
            if (frame || kind.equals("loop.park")) {
                held.add(new Held(rows.row(i)));
            }
            previous.take(rows, i);
        });
        List<CsvView.Gap<Held>> found = CsvView.gapsOf(held, thresholdMs * 1_000_000L);
        long stalls = found.stream().filter(g -> g.verdict().stall()).count();
        int from = Math.max(0, found.size() - GapList.LIMIT);
        List<Gap> kept = new ArrayList<>();
        for (CsvView.Gap<Held> g : found.subList(from, found.size())) {
            kept.add(new Gap(g.from().row(), g.last() == null ? null : g.last().row(), g.to().row(),
                    g.park() == null ? null : g.park().row(), g.nanos(),
                    g.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-'), g.verdict().stall()));
        }
        return new GapList(span, thresholdMs, kept, stalls, from);
    }

    /** {@code frames last N}: the most recent complete frames. */
    public static FrameList lastFrames(Recorder recorder, int n) {
        Listing l = listing(recorder);
        int count = clamp(n);
        int from = Math.max(0, l.summaries.size() - count);
        return l.list("last", l.summaries.subList(from, l.summaries.size()), from);
    }

    /** {@code frames worst N}: the slowest complete frames, slowest first. */
    public static FrameList worstFrames(Recorder recorder, int n) {
        Listing l = listing(recorder);
        List<FrameSummary> sorted = new ArrayList<>(l.summaries);
        sorted.sort(Comparator.comparingLong(FrameSummary::tookUs).reversed());
        int count = Math.min(clamp(n), sorted.size());
        return l.list("worst", sorted.subList(0, count), sorted.size() - count);
    }

    /**
     * {@code frames since <mark>}: frames that began after the latest {@code agent.mark} whose note contains
     * {@code mark}, earliest first.
     */
    public static FrameList framesSince(Recorder recorder, String mark, int n) {
        long[] at = {-1};
        Scan.all(recorder, (rows, i) -> {
            if (rows.kind(i).equals("agent.mark") && Scan.contains(rows.detail(i), mark)) {
                at[0] = rows.frame(i);
            }
        });
        if (at[0] < 0) {
            throw new IllegalArgumentException("no mark containing '" + mark + "' is in the ring");
        }
        Listing l = listing(recorder);
        List<FrameSummary> after = l.summaries.stream().filter(s -> s.frame() > at[0]).toList();
        int count = Math.min(clamp(n), after.size());
        return l.list("since", after.subList(0, count), after.size() - count);
    }

    /** {@code why <frame>}: one frame explained. */
    public static FrameExplanation why(Recorder recorder, long frame) {
        requireStamped(recorder);
        Frames.Result r = Frames.fold(recorder, frame);
        Frames.Acc before = null;
        for (Frames.Acc f : r.frames()) {
            if (f.frame == frame && f.complete()) {
                return Frames.explain(f, before, r.lanes(), r.targetSpan());
            }
            before = f;
        }
        long first = -1;
        long last = -1;
        for (Frames.Acc f : r.frames()) {
            if (f.complete()) {
                first = first < 0 ? f.frame : first;
                last = f.frame;
            }
        }
        throw new IllegalArgumentException("frame " + frame + " is not in the ring, which holds "
                + (first < 0 ? "no complete frame" : "complete frames " + first + ".." + last));
    }

    // --- internals -----------------------------------------------------------

    private static int clamp(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("a count must be at least 1, not " + n);
        }
        return Math.min(n, FrameList.MAX_COUNT);
    }

    private static void requireStamped(Recorder recorder) {
        if (!recorder.stamped()) {
            throw new IllegalStateException("frames need the loop's frame counter, and this host gave the recorder"
                    + " none (Windows built without LoopCounters)");
        }
    }

    /** Every complete frame, summarised. */
    private record Listing(Span span, List<FrameSummary> summaries) {
        FrameList list(String order, List<FrameSummary> kept, long more) {
            long first = summaries.isEmpty() ? -1 : summaries.get(0).frame();
            long last = summaries.isEmpty() ? -1 : summaries.get(summaries.size() - 1).frame();
            return new FrameList(span, order, kept, more, first, last);
        }
    }

    private static Listing listing(Recorder recorder) {
        requireStamped(recorder);
        Frames.Result r = Frames.fold(recorder, -1);
        List<FrameSummary> out = new ArrayList<>();
        Frames.Acc before = null;
        for (Frames.Acc f : r.frames()) {
            if (f.complete()) {
                out.add(Frames.summary(f, before, r.lanes()));
            }
            before = f;
        }
        return new Listing(r.span(), out);
    }

    /** A kept row, as the gap rule reads it. */
    private record Held(RecordedRow row) implements CsvView.Stamped {
        @Override
        public long monoNanos() {
            return row.monoNs();
        }

        @Override
        public String kind() {
            return row.kind();
        }

        @Override
        public String detail() {
            return row.detail();
        }
    }

    /** One row's fields, copied rather than materialised, so remembering the row before costs no allocation. */
    private static final class Slot {
        boolean full;
        long seq;
        long monoNs;
        long wallNs;
        long frame;
        String thread;
        String lane;
        String kind;
        String detail;

        void take(Rows rows, int i) {
            full = true;
            seq = rows.seq(i);
            monoNs = rows.monoNanos(i);
            wallNs = rows.wallNanos(i);
            frame = rows.frame(i);
            thread = rows.thread(i);
            lane = rows.lane(i);
            kind = rows.kind(i);
            detail = rows.detail(i);
        }

        void copy(Slot o) {
            full = o.full;
            seq = o.seq;
            monoNs = o.monoNs;
            wallNs = o.wallNs;
            frame = o.frame;
            thread = o.thread;
            lane = o.lane;
            kind = o.kind;
            detail = o.detail;
        }

        RecordedRow row() {
            return new RecordedRow(seq, monoNs, wallNs, frame, thread, lane, kind, detail);
        }
    }

    private static boolean isHeld(List<Held> held, long seq) {
        for (int i = held.size() - 1; i >= 0 && held.get(i).row().seq() >= seq; i--) {
            if (held.get(i).row().seq() == seq) {
                return true;
            }
        }
        return false;
    }

    private static void insertBySeq(List<Held> held, Held h) {
        int at = held.size();
        while (at > 0 && held.get(at - 1).row().seq() > h.row().seq()) {
            at--;
        }
        held.add(at, h);
    }
}
