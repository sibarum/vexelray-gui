package dev.vexelray.gui.core;

import dev.vexelray.gui.core.model.Mutation;

/**
 * The write end of the mutation channel as seen by a {@link Node} handle: a one-method seam that accepts a
 * {@link Mutation} from any thread. It says nothing about transport — {@link Gui} backs it by publishing to an
 * Atchung {@code Topic<Mutation>}, which the GUI thread drains through a {@code Pump} once per frame
 * (architecture.md §4-5). Keeping this a functional interface lets {@code Node} stay oblivious to the bus.
 */
@FunctionalInterface
public interface MutationSink {

    /** Enqueue a mutation from any thread. Delivery to the reconciler happens on the GUI thread's next drain. */
    void post(Mutation m);
}
