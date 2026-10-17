package dev.vexelray.gui.recorder;

import sibarum.probe.Lane;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * One streaming reader's cursor into the ring: where it has got to, which lanes it wants, and what it has missed
 * (docs/plans/flight-recorder.md §6). The text stream on the automation socket and the Inspector's link both
 * follow the ring through one of these, so the two cannot disagree about which rows there were or which were lost.
 *
 * <p><b>The ring is the queue.</b> A follower holds no rows of its own beyond one chunk it copies at a time, and
 * the writer never waits for it. A follower that falls a whole ring behind is lapped: the rows it never copied
 * are gone, and it says so.
 *
 * <p><b>Loss is a gap in {@code seq}, and nothing else.</b> Rows arrive in the ring in {@code seq} order, so a
 * follower that tracks the {@code seq} of every row it passes over, wanted or not, sees both kinds of loss the
 * same way: a row the probe numbered and the recorder never received, and a row the writer overwrote before this
 * follower copied it. Each gap goes to the {@link Sink} as a range, in order with the rows around it. Rows that
 * were overwritten <em>before the follower started</em> are not loss: asking for history older than the ring
 * holds starts the follower at the oldest row there is, and {@link #opened} says where it really began.
 *
 * <p>Not thread-safe: one follower, one thread, which is the streaming connection's own.
 */
public final class Follower {

    /** What a follower hands its rows and its gaps to, in ring order. */
    public interface Sink {

        /** Row {@code i} of {@code rows}, valid only for the duration of the call. */
        void row(Rows rows, int i);

        /** Rows {@code fromSeq..toSeq} (inclusive) were never seen by this follower. */
        void lost(long fromSeq, long toSeq);
    }

    private final Recorder recorder;
    private final boolean[] wanted;
    private final List<String> lanes;
    private final long fromSeq;
    private final Rows buf;

    private long pos;
    /** The last {@code seq} passed over, wanted or not; -1 before the first. */
    private long lastSeq = -1;
    /** Rows with a lower {@code seq} are not delivered: history before {@link #fromSeq}. */
    private final long minSeq;
    private final StreamOpened opened;

    private long firstNs = -1;
    private long lastNs = -1;
    private long delivered;
    private long lost;
    private long firstDelivered;
    private long lastDelivered;

    /**
     * A follower over {@code recorder}.
     *
     * @param lanes   the lane keys to deliver ({@code frame}, {@code gpu}, ...); empty for every lane
     * @param fromSeq the first {@code seq} to deliver, if the ring still holds it; negative to start with the next
     *                row published, delivering nothing already held
     * @throws IllegalArgumentException for a lane key the probe does not have
     */
    public Follower(Recorder recorder, Collection<String> lanes, long fromSeq) {
        this(recorder, lanes, fromSeq, Rows.CHUNK);
    }

    Follower(Recorder recorder, Collection<String> lanes, long fromSeq, int chunk) {
        this.recorder = java.util.Objects.requireNonNull(recorder, "recorder");
        this.wanted = new boolean[Lane.values().length];
        this.lanes = lanes == null ? List.of() : List.copyOf(lanes);
        for (String key : this.lanes) {
            wanted[lane(key).ordinal()] = true;
        }
        if (this.lanes.isEmpty()) {
            java.util.Arrays.fill(wanted, true);
        }
        this.fromSeq = fromSeq;
        this.buf = new Rows(chunk);

        Rows one = new Rows(1);
        long oldest = recorder.oldest();
        long head = recorder.published();
        long oldestSeq = head > oldest ? seqAt(oldest, one) : -1;
        long headSeq = head > oldest ? seqAt(head - 1, one) : -1;

        long start = -1;
        if (fromSeq < 0) {
            pos = head;
            minSeq = Long.MIN_VALUE;
        } else {
            minSeq = fromSeq;
            pos = head;
            for (long p = oldest; p < head; ) {
                recorder.read(p, buf);
                if (buf.isEmpty()) {
                    if (buf.next() == p) {
                        break;
                    }
                    p = buf.next();
                    continue;
                }
                int found = -1;
                for (int i = 0; i < buf.size(); i++) {
                    if (buf.seq(i) >= fromSeq) {
                        found = i;
                        break;
                    }
                }
                if (found >= 0) {
                    pos = buf.first() + found;
                    start = buf.seq(found);
                    // Starting exactly where asked: a gap right after is loss. Starting later, because the ring no
                    // longer holds the start, is the ring's span shrinking, which is not.
                    if (start == fromSeq) {
                        lastSeq = fromSeq - 1;
                    }
                    break;
                }
                p = buf.next();
            }
        }
        this.opened = new StreamOpened(this.lanes, fromSeq, start, oldestSeq, headSeq);
    }

    /** The lane with this key, or an exception naming the keys there are. */
    public static Lane lane(String key) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        List<String> known = new ArrayList<>();
        for (Lane l : Lane.values()) {
            if (l.key().equals(k)) {
                return l;
            }
            known.add(l.key());
        }
        throw new IllegalArgumentException("there is no lane '" + key + "'; the lanes are " + String.join(",", known));
    }

    private long seqAt(long position, Rows one) {
        recorder.read(position, one);
        return one.isEmpty() ? -1 : one.seq(0);
    }

    /** Where this follower began, and what the ring held when it did. */
    public StreamOpened opened() {
        return opened;
    }

    /**
     * Copy the next chunk of the ring and hand its wanted rows, and any gap before them, to {@code sink}.
     *
     * @return whether the cursor moved; false means nothing newer has been published yet
     */
    public boolean poll(Sink sink) {
        long before = pos;
        recorder.read(pos, buf);
        for (int i = 0; i < buf.size(); i++) {
            long seq = buf.seq(i);
            if (seq < minSeq) {
                continue;
            }
            if (lastSeq >= 0 && seq > lastSeq + 1) {
                lost += seq - lastSeq - 1;
                sink.lost(lastSeq + 1, seq - 1);
            }
            lastSeq = Math.max(lastSeq, seq);
            if (!wanted[buf.laneOf(i).ordinal()]) {
                continue;
            }
            long ns = buf.monoNanos(i);
            if (delivered == 0) {
                firstNs = ns;
                firstDelivered = seq;
            }
            lastNs = ns;
            lastDelivered = seq;
            delivered++;
            sink.row(buf, i);
        }
        pos = buf.next();
        return pos != before;
    }

    /** Rows delivered so far. */
    public long delivered() {
        return delivered;
    }

    /** Rows missed so far, in the gaps handed to the sink. */
    public long lost() {
        return lost;
    }

    /** What this follower has delivered, as a span: the same shape every other answer reports. */
    public Span span() {
        return new Span(firstNs, lastNs, delivered, lost, firstDelivered, lastDelivered);
    }

    /** The ending, for a reply: what was delivered and why it stopped. */
    public StreamEnded ended(String why) {
        return new StreamEnded(span(), why);
    }
}
