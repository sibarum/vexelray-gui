package dev.vexelray.gui.inspector.link;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * The Inspector asks for the stream: a request, answered with {@link StreamOpened}, after which batches and snapshots
 * arrive until the Inspector sends {@link StreamClose} or goes away.
 *
 * @param lanes       lane keys to send rows of; empty for every lane
 * @param fromSeq     the first {@code seq} to send, if the ring still holds it; negative to start with the next row
 * @param snapEveryMs how often to send a {@link Snap}; 0 for never
 */
@IntrabufMessage(name = "inspector.StreamOpen", id = 930, compat = Compat.BACKWARD)
public record StreamOpen(@Field(nonNull = true) List<String> lanes, long fromSeq, int snapEveryMs) {
}
