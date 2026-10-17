package dev.vexelray.gui.inspector.link;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.IntrabufMessage;

/**
 * The Inspector is done with the stream; the connection stays open for queries. Disconnecting ends it too.
 *
 * @param reason a few words for the application's log
 */
@IntrabufMessage(name = "inspector.StreamClose", id = 932, compat = Compat.BACKWARD)
public record StreamClose(String reason) {
}
