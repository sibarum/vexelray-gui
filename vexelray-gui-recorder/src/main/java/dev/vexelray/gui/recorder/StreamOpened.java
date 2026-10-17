package dev.vexelray.gui.recorder;

import java.util.List;

/**
 * How a stream began: what it asked for, where it really started, and what the ring held at that moment.
 *
 * @param lanes     the lanes asked for; empty for every lane
 * @param fromSeq   the {@code seq} asked to start from; -1 for "from the next row published"
 * @param startSeq  the first {@code seq} the stream will deliver from the history it asked for: {@code fromSeq}
 *                  itself, or a later one when the ring no longer holds {@code fromSeq}; -1 when it started live,
 *                  or asked for history the ring had not reached yet
 * @param oldestSeq the oldest {@code seq} the ring held; -1 when it was empty
 * @param headSeq   the newest {@code seq} the ring held; -1 when it was empty
 */
public record StreamOpened(List<String> lanes, long fromSeq, long startSeq, long oldestSeq, long headSeq) {

    public StreamOpened {
        lanes = List.copyOf(lanes);
    }
}
