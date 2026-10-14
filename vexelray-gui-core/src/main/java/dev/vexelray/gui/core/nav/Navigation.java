package dev.vexelray.gui.core.nav;

import dev.vexelray.gui.core.Node;

import java.util.concurrent.CompletableFuture;

/**
 * One navigation in progress — the handle {@code Gui.navigate} hands back, and the answer to "did it get
 * there?".
 *
 * <p><b>It takes frames, not a call.</b> Revealing a landmark is a sequence in which each step changes what the
 * next one is looking at: selecting a tab builds nothing but re-lays-out everything under it, expanding a branch
 * moves every row below it, and scrolling to a node needs the node to have been laid out where it finally is.
 * Done in one call, each step would act on the previous frame's geometry — which is precisely the class of bug
 * ("it works the second time you click the link") this exists to make unwritable. So a navigation advances one
 * step per frame, each step reading the layout the step before it produced, and this is how the caller finds out
 * when it is over.
 *
 * <p>The arrival is a {@link CompletableFuture}, so a test can {@code get} it, a macro can chain the next step
 * onto it, and an application can hang a {@code Cue} on it to say where the eye should go. It completes with the
 * landmark's node on success and exceptionally with {@link Unreachable} when the address names nothing, when a
 * container declined to reveal it, or when the walk ran out of frames.
 */
public final class Navigation {

    /** {@link #node()} for a navigation that goes to a landmark rather than a node. Node ids are allocated from 1. */
    public static final long NO_NODE = 0;

    private final Address address;
    private final long node;
    private final CompletableFuture<Node> arrival = new CompletableFuture<>();

    public Navigation(Address address) {
        this.address = address;
        this.node = NO_NODE;
    }

    /**
     * A navigation to a node by id rather than by name — what undo uses to take the user to the control an entry
     * edits, which has no landmark unless the application gave it one. Its {@link #address()} is null.
     */
    public Navigation(long node) {
        if (node == NO_NODE) {
            throw new IllegalArgumentException("not a node id: " + node);
        }
        this.address = null;
        this.node = node;
    }

    /** Where this navigation is going, or null when it goes to a {@link #node()}. */
    public Address address() {
        return address;
    }

    /** The node this navigation goes to, or {@link #NO_NODE} when it goes to an {@link #address()}. */
    public long node() {
        return node;
    }

    /**
     * Completes with the landmark's node once it is revealed, scrolled into view and focused; completes
     * exceptionally with {@link Unreachable} if it cannot be. Completed by the GUI thread, so anything chained
     * onto it directly runs there — hand work off to {@code Gui.handlers()} if it is not trivial.
     */
    public CompletableFuture<Node> arrival() {
        return arrival;
    }

    /** Whether this navigation has finished, either way. */
    public boolean done() {
        return arrival.isDone();
    }

    /** Give up on this navigation; the frame loop drops it at its next step. Harmless once it has finished. */
    public boolean cancel() {
        return arrival.cancel(false);
    }

    /** Why a navigation could not arrive: an unknown address, a refusal, or a walk that ran out of frames. */
    public static final class Unreachable extends RuntimeException {

        public Unreachable(String message) {
            super(message);
        }
    }

    @Override
    public String toString() {
        return "Navigation[" + destination() + (done() ? ", done]" : "]");
    }

    /** The address, or {@code node <id>} — for messages, which should say where the walk was going either way. */
    String destination() {
        return address != null ? address.toString() : "node " + node;
    }
}
