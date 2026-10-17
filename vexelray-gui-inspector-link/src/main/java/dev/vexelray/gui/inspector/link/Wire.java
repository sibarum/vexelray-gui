package dev.vexelray.gui.inspector.link;

import sibarum.elektro.queue.message.ArrayMessageRegistry;
import sibarum.elektro.queue.message.MessageRegistry;
import sibarum.elektro.queue.message.MessageType;

/**
 * The link's message types, and the registry both ends build their conduits on. One place, so the application and
 * the Inspector cannot register different sets.
 */
public final class Wire {

    public static final MessageType<RowBatch> ROW_BATCH = MessageType.of(RowBatchIntrabuf.CODEC, RowBatch.class);
    public static final MessageType<Snap> SNAP = MessageType.of(SnapIntrabuf.CODEC, Snap.class);
    public static final MessageType<Query> QUERY = MessageType.of(QueryIntrabuf.CODEC, Query.class);
    public static final MessageType<QueryReply> QUERY_REPLY = MessageType.of(QueryReplyIntrabuf.CODEC, QueryReply.class);
    public static final MessageType<StreamOpen> STREAM_OPEN = MessageType.of(StreamOpenIntrabuf.CODEC, StreamOpen.class);
    public static final MessageType<StreamOpened> STREAM_OPENED =
            MessageType.of(StreamOpenedIntrabuf.CODEC, StreamOpened.class);
    public static final MessageType<StreamClose> STREAM_CLOSE =
            MessageType.of(StreamCloseIntrabuf.CODEC, StreamClose.class);

    private Wire() {
    }

    /** A registry holding every link message type. */
    public static MessageRegistry registry() {
        ArrayMessageRegistry r = new ArrayMessageRegistry();
        r.register(ROW_BATCH);
        r.register(SNAP);
        r.register(QUERY);
        r.register(QUERY_REPLY);
        r.register(STREAM_OPEN);
        r.register(STREAM_OPENED);
        r.register(STREAM_CLOSE);
        return r;
    }
}
