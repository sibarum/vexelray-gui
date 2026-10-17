package dev.vexelray.gui.recorder;

import java.util.List;

/**
 * The answer to {@code recorder}: what the ring holds, and whether it can be trusted.
 *
 * @param rows         rows held now
 * @param capacityRows rows held when full
 * @param span         the stretch those rows cover, and the loss inside it
 * @param lostTotal    rows lost since the recorder started, including any since overwritten
 * @param rateRowsPerS rows taken in the last second of the span
 * @param lanes        the probe lanes switched on, by key
 * @param firstFrame   the oldest frame stamp held, or -1 when rows are not stamped
 * @param lastFrame    the newest frame stamp held, or -1 when rows are not stamped
 * @param stamped      whether the host handed the recorder its frame counter
 * @param ringBytes    what the ring's own arrays occupy, before the strings they point at
 * @param sinkFailures rows a live sink threw on, process-wide ({@code Probe.liveSinkFailures()})
 */
public record RecorderStatus(long rows, long capacityRows, Span span, long lostTotal, long rateRowsPerS,
                             List<String> lanes, long firstFrame, long lastFrame, boolean stamped, long ringBytes,
                             long sinkFailures) {

    public RecorderStatus {
        lanes = List.copyOf(lanes);
    }
}
