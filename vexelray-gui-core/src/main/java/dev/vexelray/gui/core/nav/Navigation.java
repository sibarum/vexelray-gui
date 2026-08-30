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

    private final Address address;
    private final CompletableFuture<Node> arrival = new CompletableFuture<>();

    public Navigation(Address address) {
        this.address = address;
    }

    /** Where this navigation is going. */
    public Address address() {
        return address;
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
        return "Navigation[" + address + (done() ? ", done]" : "]");
    }
}
