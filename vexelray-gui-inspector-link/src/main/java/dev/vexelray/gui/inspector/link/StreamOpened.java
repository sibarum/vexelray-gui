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
 * @param err present when the stream was refused (the recorder is off, a lane does not exist, a stream is already
 *            open), with the reason; the other fields are then -1
 */
@IntrabufMessage(name = "inspector.StreamOpened", id = 931, compat = Compat.BACKWARD)
public record StreamOpened(@Field(nonNull = true) List<String> lanes, long fromSeq, long startSeq, long oldestSeq,
                           long headSeq, @Field(nonNull = true) Optional<String> err) {
}
