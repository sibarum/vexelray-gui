package dev.vexelray.gui.inspector.link;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * The rows that arrived since the last batch, sent every 10 ms to one Inspector.
 *
 * <p>{@code lostFrom..lostTo} is the {@code seq} range this Inspector never got, just before {@code rows}: the in-band
 * twin of the text stream's {@code # lost} line. Both are 0 when nothing was lost. A batch carries at most one gap, so
 * a second gap starts a new batch and the order of rows and gaps is kept.
 */
@IntrabufMessage(name = "inspector.RowBatch", id = 900, compat = Compat.BACKWARD)
public record RowBatch(long lostFrom, long lostTo, @Field(nonNull = true) List<Row> rows) {

    /** Whether rows were lost just before this batch. */
    public boolean lost() {
        return lostTo >= lostFrom && lostTo > 0;
    }
}
