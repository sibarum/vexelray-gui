package dev.vexelray.gui.inspector.link;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

/**
 * The answer to a {@link Query}: the verb's text reply, exactly as the automation socket gives it, and whether it
 * began {@code ok}. Typed replies per verb ({@code why}'s record, and so on) are later additions, as new message
 * types beside this one.
 */
@IntrabufMessage(name = "inspector.QueryReply", id = 921, compat = Compat.BACKWARD)
public record QueryReply(boolean ok, @Field(nonNull = true) String text) {
}
