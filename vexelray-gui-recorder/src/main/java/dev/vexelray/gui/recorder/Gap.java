package dev.vexelray.gui.recorder;

/**
 * One silence between frames, judged by {@code sibarum.probe.CsvView}'s rule: the same rule {@code csvview --gaps}
 * applies to a file.
 *
 * @param from    the {@code frame.present} before the silence
 * @param last    the last row inside it, the suspect in a stall; {@code null} when nothing happened inside
 * @param to      the {@code frame.present} that ended it
 * @param park    the park meant to cover it, or {@code null} when nothing said the loop would sleep
 * @param gapNs   how long it lasted
 * @param verdict {@code expected}, {@code no-park} or {@code short-park}
 * @param stall   whether the loop failed to come back, as opposed to sleeping as announced
 */
public record Gap(RecordedRow from, RecordedRow last, RecordedRow to, RecordedRow park, long gapNs, String verdict,
                  boolean stall) {
}
