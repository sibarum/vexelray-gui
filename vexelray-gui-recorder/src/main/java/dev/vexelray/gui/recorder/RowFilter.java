package dev.vexelray.gui.recorder;

import java.util.List;

/**
 * Which rows {@code rows} keeps. Every criterion given must hold, as {@code csvview}'s options combine. Lanes are an
 * exact set, because a lane is a closed vocabulary and a partial match would silently widen the answer; the rest
 * match case-insensitively as substrings.
 *
 * @param lanes  lane keys to keep; empty keeps all
 * @param kind   a substring of the kind, or {@code null}
 * @param thread a substring of the thread, or {@code null}
 * @param grep   a substring of thread, lane, kind or detail, or {@code null}
 * @param frame  only rows stamped with this frame, or -1 for any
 * @param limit  at most this many rows, the most recent
 */
public record RowFilter(List<String> lanes, String kind, String thread, String grep, long frame, int limit) {

    /** {@code rows} with no count: the last 50. */
    public static final int DEFAULT_LIMIT = 50;

    /** The most one answer holds, whatever was asked for. */
    public static final int MAX_LIMIT = 1_000;

    public static final RowFilter ALL = new RowFilter(List.of(), null, null, null, -1, DEFAULT_LIMIT);

    public RowFilter {
        lanes = List.copyOf(lanes);
        if (limit < 1) {
            throw new IllegalArgumentException("a limit must be at least 1, not " + limit);
        }
        limit = Math.min(limit, MAX_LIMIT);
    }
}
