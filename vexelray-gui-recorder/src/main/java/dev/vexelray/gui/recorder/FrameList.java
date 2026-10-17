package dev.vexelray.gui.recorder;

import java.util.List;

/**
 * The answer to {@code frames}.
 *
 * @param span       the stretch of the ring read
 * @param order      {@code last}, {@code worst} or {@code since}
 * @param frames     the frames kept: in frame order for {@code last} and {@code since}, slowest first for
 *                   {@code worst}
 * @param more       frames that qualified and were left out
 * @param firstFrame the oldest complete frame in the ring, or -1 when there is none
 * @param lastFrame  the newest complete frame in the ring, or -1
 */
public record FrameList(Span span, String order, List<FrameSummary> frames, long more, long firstFrame,
                        long lastFrame) {

    /** {@code frames} with no count. */
    public static final int DEFAULT_COUNT = 20;

    /** The most one answer lists, whatever was asked for. */
    public static final int MAX_COUNT = 200;

    public FrameList {
        frames = List.copyOf(frames);
    }
}
