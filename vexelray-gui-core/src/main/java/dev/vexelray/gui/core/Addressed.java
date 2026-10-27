package dev.vexelray.gui.core;

/**
 * A message for one widget or one node, and which one. What {@link Gui#mailbox(sibarum.atchung.Topic, Object,
 * sibarum.atchung.Subscriber)} routes by: a command to the text field named {@code "path"} answers {@code "path"}, an
 * input event for a node answers the node's id.
 *
 * <p>The address is a value with sensible {@code equals} and {@code hashCode} (a name, a boxed id), and unique among the
 * mailboxes on one topic of one tree: two widgets answering to one address would each be the other's bug.
 */
public interface Addressed {

    /** Which widget or node this message is for. */
    Object address();
}
