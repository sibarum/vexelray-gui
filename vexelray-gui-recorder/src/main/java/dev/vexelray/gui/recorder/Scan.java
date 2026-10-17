package dev.vexelray.gui.recorder;

/**
 * Reading every row the ring holds, oldest first, a chunk at a time — the one loop every query is built on.
 *
 * <p>The end is fixed when the scan starts, so an answer about a busy application finishes rather than chasing
 * rows written while it reads. A chunk the writer laps before it is copied comes back shorter, the rows it lost
 * show up as a {@code seq} gap, and the {@link Span} counts them as lost, which is what they are to this answer.
 */
final class Scan {

    /** One row of a chunk; valid only during the call. */
    interface Visitor {
        void row(Rows rows, int i);
    }

    private Scan() {
    }

    /** Visit every row held now and return the span read. */
    static Span all(Recorder recorder, Visitor visitor) {
        Rows buf = new Rows();
        Tally tally = new Tally();
        long end = recorder.published();
        long p = recorder.oldest();
        while (p < end) {
            recorder.read(p, buf);
            int n = buf.size();
            if (n == 0) {
                break;
            }
            for (int i = 0; i < n && buf.first() + i < end; i++) {
                tally.add(buf.seq(i), buf.monoNanos(i));
                visitor.row(buf, i);
            }
            p = buf.next();
        }
        return tally.span();
    }

    /** The span of the rows added to it, and the {@code seq} numbers missing between them. */
    static final class Tally {
        private long fromNs = -1;
        private long toNs = -1;
        private long rows;
        private long lost;
        private long firstSeq;
        private long lastSeq;

        void add(long seq, long monoNs) {
            if (rows == 0) {
                fromNs = monoNs;
                firstSeq = seq;
            } else if (seq > lastSeq + 1) {
                lost += seq - lastSeq - 1;
            }
            toNs = monoNs;
            lastSeq = seq;
            rows++;
        }

        Span span() {
            return rows == 0 ? Span.EMPTY : new Span(fromNs, toNs, rows, lost, firstSeq, lastSeq);
        }
    }

    /** Whether {@code hay} contains {@code needle}, ignoring case, without allocating. */
    static boolean contains(String hay, String needle) {
        int n = needle.length();
        for (int i = 0, last = hay.length() - n; i <= last; i++) {
            if (hay.regionMatches(true, i, needle, 0, n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A span's detail as nanoseconds — the probe writes a closed span's time as {@code 900ns}, {@code 48.5us},
     * {@code 1.27ms} or {@code 2.50s} — or -1 when the detail is not a duration (a counter's number, a mark's text).
     */
    static long nanos(String detail) {
        int len = detail.length();
        int unitAt = len;
        while (unitAt > 0 && Character.isLetter(detail.charAt(unitAt - 1))) {
            unitAt--;
        }
        if (unitAt == 0 || unitAt == len) {
            return -1;
        }
        double scale;
        String unit = detail.substring(unitAt);
        if (unit.equals("ns")) {
            scale = 1;
        } else if (unit.equals("us")) {
            scale = 1e3;
        } else if (unit.equals("ms")) {
            scale = 1e6;
        } else if (unit.equals("s")) {
            scale = 1e9;
        } else {
            return -1;
        }
        try {
            return Math.round(Double.parseDouble(detail.substring(0, unitAt)) * scale);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A counter's detail as a number, or 0 when it is not one. */
    static long count(String detail) {
        try {
            return Long.parseLong(detail.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
