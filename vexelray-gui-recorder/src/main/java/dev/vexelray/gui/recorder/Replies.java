package dev.vexelray.gui.recorder;

import dev.vexelray.gui.recorder.FrameExplanation.LayoutPass;
import dev.vexelray.gui.recorder.FrameExplanation.Published;
import dev.vexelray.gui.recorder.FrameExplanation.Stage;
import dev.vexelray.gui.recorder.FrameExplanation.Wake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text replies: each answer record rendered for the automation socket, and nothing computed here that the
 * record does not already hold. A reply is a pure function of its record, so the same record sent by another
 * transport says exactly what the text said.
 *
 * <p>The shape is the socket's (flight-recorder.md §2): a first line of {@code ok} and {@code key=value} pairs,
 * units in the keys, the span read ({@code over=}) and the rows missing from it ({@code lost=}) on every reply,
 * {@code more=} on every list, and any table after it with a header row. Times are on the probe's clock, as
 * {@code +seconds} since it started — the same clock as the correlation log's {@code t_mono_ns}.
 */
public final class Replies {

    /** The longest detail a one-line table cell shows before it is cut. */
    static final int CELL = 72;

    /** The header row of every list of rows: the correlation log's columns, and the frame stamp last. */
    public static final String ROW_HEADER = "seq,t_mono_ns,t_wall,thread,lane,kind,detail,frame";

    private Replies() {
    }

    /**
     * A stream's first line. The rows follow under {@link #ROW_HEADER}; gaps and pauses are {@code #} lines,
     * which a reader of the correlation log skips as not-a-row; {@link #text(StreamEnded)} is the last line.
     */
    public static String text(StreamOpened o) {
        return "ok stream lanes=" + (o.lanes().isEmpty() ? "all" : String.join(",", o.lanes()))
                + " from_seq=" + num(o.fromSeq())
                + " start_seq=" + num(o.startSeq())
                + " oldest_seq=" + num(o.oldestSeq())
                + " head_seq=" + num(o.headSeq());
    }

    /** A stream's last line before the terminator: what it delivered, its span and loss, and why it stopped. */
    public static String text(StreamEnded e) {
        Span s = e.span();
        return "# end rows=" + s.rows() + " " + over(s)
                + " seq=" + (s.isEmpty() ? "-" : s.firstSeq() + ".." + s.lastSeq())
                + " why=" + e.why();
    }

    /** A gap in a stream, in order with the rows around it: rows {@code fromSeq..toSeq} never reached it. */
    public static String lost(long fromSeq, long toSeq) {
        return "# lost " + fromSeq + ".." + toSeq + " rows=" + (toSeq - fromSeq + 1);
    }

    /** A stream with nothing to send for a while says so, which is also how it notices a reader that has gone. */
    public static String idle(long quietMs) {
        return "# idle quiet_ms=" + quietMs;
    }

    /** Row {@code i} of {@code rows} as a correlation-log line, appended to {@code sb}; see {@link #csv}. */
    public static StringBuilder appendCsv(StringBuilder sb, Rows rows, int i) {
        return csvLine(sb, rows.seq(i), rows.monoNanos(i), rows.wallNanos(i), rows.thread(i), rows.lane(i),
                rows.kind(i), rows.detail(i), rows.frame(i));
    }

    public static String text(RecorderStatus s) {
        return "ok rows=" + s.rows()
                + " capacity_rows=" + s.capacityRows()
                + " " + over(s.span())
                + " lost_total=" + s.lostTotal()
                + " rate_rows_per_s=" + s.rateRowsPerS()
                + " frames=" + (s.firstFrame() < 0 ? "-" : s.firstFrame() + ".." + s.lastFrame())
                + " frame_stamp=" + (s.stamped() ? "yes" : "no")
                + String.format(Locale.ROOT, " ring_mb=%.1f", s.ringBytes() / (1024.0 * 1024.0))
                + " sink_failures=" + s.sinkFailures()
                + " lanes=" + (s.lanes().isEmpty() ? "-" : String.join(",", s.lanes()));
    }

    /** The rows as the correlation log has them, with the frame stamp as one more column. */
    public static String text(RowList r) {
        StringBuilder sb = new StringBuilder("ok rows=").append(r.rows().size())
                .append(' ').append(over(r.span())).append(" more=").append(r.more());
        if (!r.rows().isEmpty()) {
            sb.append('\n').append(ROW_HEADER);
            for (RecordedRow row : r.rows()) {
                sb.append('\n').append(csv(row));
            }
        }
        return sb.toString();
    }

    public static String text(GapList g) {
        StringBuilder sb = new StringBuilder("ok gaps=").append(g.gaps().size())
                .append(" stalls=").append(g.stalls())
                .append(" threshold_ms=").append(g.thresholdMs())
                .append(' ').append(over(g.span())).append(" more=").append(g.more());
        if (!g.gaps().isEmpty()) {
            Table t = new Table("frame", "at", "gap_ms", "verdict", "park", "last");
            for (Gap gap : g.gaps()) {
                t.row(Long.toString(gap.from().frame()), at(gap.from().monoNs()),
                        String.format(Locale.ROOT, "%.1f", gap.gapNs() / 1e6), gap.verdict(),
                        gap.park() == null ? "-" : gap.park().detail(),
                        gap.last() == null ? "-" : cut(gap.last().thread() + " " + gap.last().lane() + " "
                                + gap.last().kind() + " " + gap.last().detail()));
            }
            sb.append('\n').append(t);
        }
        return sb.toString();
    }

    public static String text(FrameList f) {
        StringBuilder sb = new StringBuilder("ok frames=").append(f.frames().size())
                .append(" order=").append(f.order())
                .append(" held=").append(f.firstFrame() < 0 ? "-" : f.firstFrame() + ".." + f.lastFrame())
                .append(' ').append(over(f.span())).append(" more=").append(f.more());
        if (!f.frames().isEmpty()) {
            Table t = new Table("frame", "at", "took_us", "stage", "stage_us", "woke", "drawn", "presented",
                    "park");
            for (FrameSummary s : f.frames()) {
                t.row(Long.toString(s.frame()), at(s.atNs()), Long.toString(s.tookUs()), s.stage(), num(s.stageUs()),
                        s.woke(), s.drawn(), s.presented(), s.park());
            }
            sb.append('\n').append(t);
        }
        return sb.toString();
    }

    public static String text(FrameExplanation e) {
        StringBuilder sb = new StringBuilder("ok frame=").append(e.frame())
                .append(" at=").append(at(e.atNs()))
                .append(" took_us=").append(e.tookUs())
                .append(" drawn=").append(e.drawn())
                .append(" presented=").append(e.presented())
                .append(' ').append(over(e.span()));
        line(sb, "woke", woke(e.woke()));
        line(sb, "stage", stages(e.stages()) + "   (us; together they make up took_us)");
        line(sb, "inside", stages(e.inside()) + "   (us)");
        line(sb, "layout", passes(e.passes()));
        line(sb, "bus", bus(e.drained(), e.published()));
        line(sb, "park", e.park() + " waited_us=" + num(e.waitedUs()));
        line(sb, "also", "slow_spans=" + e.slowSpans());
        line(sb, "unknown", String.join("; ", e.unrecorded()));
        line(sb, "see", e.see());
        return sb.toString();
    }

    // --- pieces ---------------------------------------------------------------

    /** {@code over=+a..+b lost=n}: what every reply says it read. */
    static String over(Span s) {
        return "over=" + (s.isEmpty() ? "-" : at(s.fromNs()) + ".." + at(s.toNs())) + " lost=" + s.lost();
    }

    /** A time on the probe's clock, as {@code +seconds}. */
    static String at(long monoNs) {
        return monoNs < 0 ? "-" : String.format(Locale.ROOT, "+%.3fs", monoNs / 1e9);
    }

    private static String num(long v) {
        return v < 0 ? "-" : Long.toString(v);
    }

    private static void line(StringBuilder sb, String label, String text) {
        sb.append('\n').append(label);
        for (int i = label.length(); i < 8; i++) {
            sb.append(' ');
        }
        sb.append(text);
    }

    private static String woke(Wake w) {
        if (w.count() > 0) {
            return w.kind() + ": " + w.detail() + " (" + w.thread() + "; " + w.count() + " wake mark"
                    + (w.count() == 1 ? "" : "s") + ")";
        }
        return w.kind() + ": " + w.detail();
    }

    private static String stages(List<Stage> stages) {
        List<String> parts = new ArrayList<>(stages.size());
        for (Stage s : stages) {
            parts.add(s.name() + "=" + num(s.us()));
        }
        return String.join(" ", parts);
    }

    private static String passes(List<LayoutPass> passes) {
        if (passes.isEmpty()) {
            return "0 passes";
        }
        List<String> parts = new ArrayList<>(passes.size());
        for (LayoutPass p : passes) {
            parts.add(p.kind() + " " + p.us() + "us");
        }
        return passes.size() + " pass" + (passes.size() == 1 ? "" : "es") + ": " + String.join(", ", parts);
    }

    private static String bus(long drained, List<Published> published) {
        if (drained < 0) {
            return "drained=- published=-   (the bus lane is off)";
        }
        StringBuilder sb = new StringBuilder("drained=").append(drained).append(" published=");
        if (published.isEmpty()) {
            return sb.append("none").toString();
        }
        List<String> parts = new ArrayList<>(published.size());
        for (Published p : published) {
            parts.add(p.topic() + " x" + p.count() + " (" + p.threads() + ")");
        }
        return sb.append(String.join(", ", parts)).toString();
    }

    /** One row as a correlation-log line: the probe's quoting, one physical line, and the frame last. */
    static String csv(RecordedRow r) {
        return csvLine(new StringBuilder(96), r.seq(), r.monoNs(), r.wallNs(), r.thread(), r.lane(), r.kind(),
                r.detail(), r.frame()).toString();
    }

    private static StringBuilder csvLine(StringBuilder sb, long seq, long monoNs, long wallNs, String thread,
                                         String lane, String kind, String detail, long frame) {
        sb.append(seq).append(',').append(monoNs).append(',')
                .append(Instant.ofEpochSecond(0, wallNs)).append(',');
        field(sb, thread).append(',');
        field(sb, lane).append(',');
        field(sb, kind).append(',');
        field(sb, detail).append(',');
        return sb.append(frame);
    }

    private static StringBuilder field(StringBuilder sb, String v) {
        String s = v.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0) {
            return sb.append(s);
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append('"');
            }
            sb.append(c);
        }
        return sb.append('"');
    }

    private static String cut(String s) {
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= CELL ? flat : flat.substring(0, CELL - 1) + "~";
    }

    /** Columns padded to their widest cell; the last column is left ragged. */
    private static final class Table {
        private final List<String[]> rows = new ArrayList<>();

        Table(String... header) {
            rows.add(header);
        }

        void row(String... cells) {
            rows.add(cells);
        }

        @Override
        public String toString() {
            int columns = rows.get(0).length;
            int[] width = new int[columns];
            for (String[] r : rows) {
                for (int c = 0; c < columns - 1; c++) {
                    width[c] = Math.max(width[c], r[c].length());
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String[] r : rows) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                for (int c = 0; c < columns; c++) {
                    sb.append(r[c]);
                    if (c < columns - 1) {
                        for (int k = r[c].length(); k < width[c] + 2; k++) {
                            sb.append(' ');
                        }
                    }
                }
            }
            return sb.toString();
        }
    }
}
