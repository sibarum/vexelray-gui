package dev.vexelray.gui.recorder;

import java.util.List;

/**
 * The answer to {@code gaps}: silences of at least the threshold, the most recent last.
 *
 * @param span        the stretch of the ring searched
 * @param thresholdMs the shortest silence reported
 * @param gaps        the gaps kept: the most recent ones
 * @param stalls      how many of all the gaps found, kept or not, were stalls
 * @param more        gaps found and left out, all older than those kept
 */
public record GapList(Span span, long thresholdMs, List<Gap> gaps, long stalls, long more) {

    /** {@code gaps} with no threshold: 100 ms, as {@code csvview --gaps}. */
    public static final long DEFAULT_THRESHOLD_MS = 100;

    /** At most this many gaps in one answer. */
    public static final int LIMIT = 20;

    public GapList {
        gaps = List.copyOf(gaps);
    }
}
