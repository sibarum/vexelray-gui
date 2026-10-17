package dev.vexelray.gui.inspector.link;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

/**
 * One probe row as the recorder holds it: the correlation log's row plus the frame stamp, the same fields as the
 * recorder's {@code RecordedRow} and the text stream's CSV line. {@code detail} is sent raw, newlines kept.
 *
 * @param lane the lane's key, as the CSV's {@code lane} column has it ({@code frame}, {@code gpu}, ...)
 */
@IntrabufMessage(name = "inspector.Row", compat = Compat.BACKWARD)
public record Row(
        long seq,
        long tMonoNs,
        long tWallNs,
        long frame,
        @Field(nonNull = true) String thread,
        @Field(nonNull = true) String lane,
        @Field(nonNull = true) String kind,
        @Field(nonNull = true) String detail) {
}
