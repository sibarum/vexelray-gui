package dev.vexelray.gui.recorder;

/**
 * What an answer was computed from: the stretch of the ring it read, and how many rows were missing from it.
 * Every answer carries one, because a reader not told about absent rows is being invited to conclude something
 * from a hole (flight-recorder.md §2).
 *
 * @param fromNs   {@code t_mono_ns} of the first row read; -1 when nothing was read
 * @param toNs     {@code t_mono_ns} of the last row read; -1 when nothing was read
 * @param rows     rows read
 * @param lost     rows missing inside the stretch: {@code seq} numbers between the first and the last that were
 *                 never received
 * @param firstSeq the first row's {@code seq}, or 0
 * @param lastSeq  the last row's {@code seq}, or 0
 */
public record Span(long fromNs, long toNs, long rows, long lost, long firstSeq, long lastSeq) {

    /** Nothing read. */
    public static final Span EMPTY = new Span(-1, -1, 0, 0, 0, 0);

    public boolean isEmpty() {
        return rows == 0;
    }
}
