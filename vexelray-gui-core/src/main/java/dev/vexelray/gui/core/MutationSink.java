package dev.vexelray.gui.core;

import dev.vexelray.gui.core.model.Mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The producer end of the mutation channel — a lock-free multi-producer / single-consumer queue. Any thread may
 * {@link #post}; only the GUI thread {@link #drain}s. A {@code PENDING_WAKE} CAS collapses a burst of posts into a
 * single wake of a (possibly sleeping) GUI thread: only the first poster after a drain fires {@code wake}. The
 * wake itself is a hook — a no-op until the engine's idle-blocking loop (E2) provides a real OS wake; today's
 * loop polls every frame and drains regardless.
 */
public final class MutationSink {

    private final ConcurrentLinkedQueue<Mutation> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean pendingWake = new AtomicBoolean(false);
    private volatile Runnable wake = () -> { };

    /** Install the OS-wake hook (E2). Until then the default no-op is fine — the loop polls. */
    public void onWake(Runnable wake) {
        this.wake = wake;
    }

    /** Enqueue a mutation from any thread; wakes the GUI thread once per idle burst. */
    public void post(Mutation m) {
        queue.add(m);
        if (pendingWake.compareAndSet(false, true)) {
            wake.run();
        }
    }

    /** Drain all queued mutations in FIFO order (GUI thread only) and re-arm the wake. */
    public List<Mutation> drain() {
        pendingWake.set(false);
        List<Mutation> out = new ArrayList<>();
        Mutation m;
        while ((m = queue.poll()) != null) {
            out.add(m);
        }
        return out;
    }
}
