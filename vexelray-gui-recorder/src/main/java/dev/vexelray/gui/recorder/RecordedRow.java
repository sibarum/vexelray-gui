package dev.vexelray.gui.recorder;

/**
 * One probe row as the recorder holds it: the correlation log's row ({@code docs/reference/automation.md} §4)
 * plus the frame stamp. A value with nothing in it but numbers and strings, so any transport can carry it.
 *
 * @param seq    the probe's row number, gapless across the process; a gap is loss
 * @param monoNs {@code t_mono_ns}: nanoseconds since the probe started
 * @param wallNs {@code t_wall}, as nanoseconds since the epoch
 * @param frame  the loop's frame in progress when the row was written; 0 before the first frame, or when the host
 *               handed the recorder no frame counter
 * @param thread the emitting thread's name
 * @param lane   the lane's key ({@code frame}, {@code layout}, ...)
 * @param kind   the span, counter or mark name
 * @param detail the producer's detail: a duration for a span, a number for a counter, free text for a mark
 */
public record RecordedRow(long seq, long monoNs, long wallNs, long frame, String thread, String lane, String kind,
                          String detail) {
}
