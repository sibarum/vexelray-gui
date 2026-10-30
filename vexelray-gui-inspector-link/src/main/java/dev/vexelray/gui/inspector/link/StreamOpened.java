package dev.vexelray.gui.inspector.link;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;
import java.util.Optional;

/**
 * The answer to {@link StreamOpen}: where the stream really starts and what the ring held, the same facts as the
 * text stream's {@code ok stream} line (the recorder's {@code StreamOpened}).
 *
 * @param err         present when the stream was refused (the recorder is off, a lane does not exist, a stream is
 *                    already open), with the reason; the other fields are then -1
 * @param originNanos where the rows' {@code tMonoNs} count from, on the application's {@code System.nanoTime()}
 *                    ({@code Probe.originNanos}): a row's time plus this is its moment on the machine's monotonic
 *                    clock. Empty from an application older than the field, and when the stream was refused
 */
@IntrabufMessage(name = "inspector.StreamOpened", id = 931, minor = 1, compat = Compat.BACKWARD)
public record StreamOpened(@Field(nonNull = true) List<String> lanes, long fromSeq, long startSeq, long oldestSeq,
                           long headSeq, @Field(nonNull = true) Optional<String> err,
                           @Field(since = 1, nonNull = true) Optional<Long> originNanos) {
}
