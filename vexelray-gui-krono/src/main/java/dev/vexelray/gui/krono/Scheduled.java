package dev.vexelray.gui.krono;

import sibarum.kronometer.Shred;

/**
 * A handle on work that may not have started yet.
 *
 * <p>It exists because of the off-timeline problem. {@link KronoGui#every} called from a click handler
 * cannot spork immediately — the handler is on a worker thread, so the work is posted to the next moment
 * the kernel observes — which means there is no {@link Shred} to hand back at the point of the call.
 * Returning the shred anyway would return {@code null}, and a handle that is sometimes null is worse
 * than no handle at all.
 *
 * <p>So this is a promise about identity rather than a reference to a thing: cancel it before the work
 * starts and it never starts; cancel it after and the shred is cancelled on the timeline in the usual
 * way. Either order works, which is the only property that makes it usable from a handler.
 */
public final class Scheduled {

    private volatile Shred shred;
    private volatile boolean cancelled;

    Scheduled() {
    }

    /**
     * Stop this work, whether or not it has begun.
     *
     * <p>Safe from any thread. If the shred is already running, cancellation is delivered on the
     * timeline, so its {@code finally} blocks run at a definite moment.
     */
    public void cancel() {
        cancelled = true;
        Shred current = shred;
        if (current != null && current.isAlive()) {
            current.cancel();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Whether the work has started and not yet finished. */
    public boolean isRunning() {
        Shred current = shred;
        return current != null && current.isAlive();
    }

    /** The shred, once it exists. {@code null} while the start is still pending. */
    public Shred shred() {
        return shred;
    }

    /**
     * Called on the timeline when the shred is finally created.
     *
     * <p>The cancelled check here is what makes the two orders equivalent: a cancel that arrived before
     * the start is honoured by never letting the work run.
     */
    void bind(Shred started) {
        if (cancelled) {
            started.cancel();
        } else {
            this.shred = started;
        }
    }
}
