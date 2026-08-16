package dev.vexelray.gui.core.input;

/**
 * A key press and what became of it: which node held focus, and whether a {@link ClaimScope claim} took it before
 * the focused node could see it.
 *
 * <p>Published for <b>every</b> key press, whatever the routing decided — the channel an extension, devtool or
 * remote peer watches to see keys it would otherwise never be told about. Strictly observational: subscribing
 * here cannot cancel or redirect anything. The moment observation could veto, this would be
 * {@code preventDefault} again, with the synchronous-handler assumption it drags along (see {@link ClaimScope}).
 * Authority lives in claims, which are declared in advance and readable on the GUI thread.
 *
 * @param event         the key and the modifiers held with it
 * @param focusedNodeId the node holding keyboard focus at the time, or {@code -1} for none
 * @param claimed       whether a claim preempted delivery to the focused node
 * @param from          the dispatch conduit and this event's position in it — the drain's order, stated rather
 *                      than inferred, so an observer can tell a dropped key from a reordered one
 */
public record KeyRouted(KeyEvent event, long focusedNodeId, boolean claimed,
                        dev.vexelray.gui.core.Provenance from) {
}
