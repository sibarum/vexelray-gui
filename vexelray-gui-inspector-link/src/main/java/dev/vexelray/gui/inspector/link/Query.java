package dev.vexelray.gui.inspector.link;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

/**
 * Any read verb of the automation socket, as its own line ({@code why 18342}, {@code frames worst 5}, {@code tree}),
 * so the Inspector and an agent ask the same questions in the same words. A request, answered with
 * {@link QueryReply}. A verb that drives is refused: the hand is the automation socket's.
 */
@IntrabufMessage(name = "inspector.Query", id = 920, compat = Compat.BACKWARD)
public record Query(@Field(nonNull = true) String line) {
}
