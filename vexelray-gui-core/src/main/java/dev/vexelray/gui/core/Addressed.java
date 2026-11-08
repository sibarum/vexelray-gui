package dev.vexelray.gui.core;

import sibarum.atchung.Topic;

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

    /**
     * The tree {@code address} is in: {@code "main"} of {@code "main.path"}, as {@link Gui#address(String)} writes it.
     * An address that is not a widget's (a node's id) is in no tree, and is empty.
     */
    static String tree(Object address) {
        if (address instanceof String s) {
            int dot = s.indexOf('.');
            return dot < 0 ? "" : s.substring(0, dot);
        }
        return "";
    }

    /**
     * An edge topic of a widget's messages, scoped by tree: on a bus every window shares, a tree's widgets are offered
     * only their own tree's messages, so a window that is closed, and runs no frames, holds none of another's.
     */
    static <T extends Addressed> Topic<T> edge(Class<T> payloadType) {
        return Topic.edge(payloadType, m -> tree(m.address()));
    }

    /** As {@link #edge}, for a state topic: folded by {@code key}, and scoped by tree. */
    static <T extends Addressed> Topic<T> state(Class<T> payloadType, Topic.Key<T> key) {
        return Topic.state(payloadType, key).scoped(m -> tree(m.address()));
    }
}
