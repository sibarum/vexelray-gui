package dev.vexelray.gui.recorder;

import sibarum.probe.Lane;

/**
 * A reader's own copy of a run of the ring: columns like the ring's, allocated once and reused, so reading the
 * whole ring chunk by chunk allocates nothing per row. {@link Recorder#read} fills it; {@link #row} turns one row
 * into a {@link RecordedRow} when a caller wants to keep it.
 *
 * <p>Indexes run {@code 0..size()-1}, oldest first. Not thread-safe: one reader, one buffer.
 */
public final class Rows {

    /** The default chunk a reader copies at a time. */
    public static final int CHUNK = 4_096;

    final long[] seq;
    final long[] mono;
    final long[] wall;
    final long[] frame;
    final String[] thread;
    final Lane[] lane;
    final String[] kind;
    final String[] detail;

    private int start;
    private int end;
    private long first;
    private long next;
    private long skipped;

    public Rows() {
        this(CHUNK);
    }

    public Rows(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        seq = new long[capacity];
        mono = new long[capacity];
        wall = new long[capacity];
        frame = new long[capacity];
        thread = new String[capacity];
        lane = new Lane[capacity];
        kind = new String[capacity];
        detail = new String[capacity];
    }

    void reset() {
        start = 0;
        end = 0;
        skipped = 0;
    }

    void set(int start, int end, long first, long next, long skipped) {
        this.start = start;
        this.end = end;
        this.first = first;
        this.next = next;
        this.skipped = skipped;
    }

    public int capacity() {
        return seq.length;
    }

    public int size() {
        return end - start;
    }

    public boolean isEmpty() {
        return end == start;
    }

    /** The ring position of row 0. */
    public long first() {
        return first;
    }

    /** The ring position to read from next, to carry on after this chunk. */
    public long next() {
        return next;
    }

    /** Positions asked for that had been overwritten before they could be copied. */
    public long skipped() {
        return skipped;
    }

    public long seq(int i) {
        return seq[start + i];
    }

    /** {@code t_mono_ns}: nanoseconds since the probe started. */
    public long monoNanos(int i) {
        return mono[start + i];
    }

    /** {@code t_wall} as nanoseconds since the epoch. */
    public long wallNanos(int i) {
        return wall[start + i];
    }

    /** The loop's frame in progress when the row was written. */
    public long frame(int i) {
        return frame[start + i];
    }

    public String thread(int i) {
        return thread[start + i];
    }

    Lane laneOf(int i) {
        return lane[start + i];
    }

    /** The lane's key, as the file's {@code lane} column has it. */
    public String lane(int i) {
        return lane[start + i].key();
    }

    public String kind(int i) {
        return kind[start + i];
    }

    public String detail(int i) {
        return detail[start + i];
    }

    /** Row {@code i}, as a value to keep. */
    public RecordedRow row(int i) {
        int j = start + i;
        return new RecordedRow(seq[j], mono[j], wall[j], frame[j], thread[j], lane[j].key(), kind[j], detail[j]);
    }
}
