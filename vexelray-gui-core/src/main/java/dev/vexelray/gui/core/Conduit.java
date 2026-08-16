package dev.vexelray.gui.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single-threaded ordering domain: one counter, advanced by one thread, stamping the messages that pass
 * through it.
 *
 * <p>The confinement is the whole design. Because exactly one thread calls {@link #next()}, the counter is a
 * plain increment — no CAS, no shared cache line, nothing that gets more expensive as volume rises. The counter
 * that cannot be afforded is a <em>global</em> one; per-conduit was never that. And within the conduit the
 * resulting order is exact, with none of the uncertainty interval that comparing clocks across domains carries.
 *
 * <p>The framework's conduit is the frame loop's input drain, which is single-threaded by construction — so the
 * order it stamps is the order events were routed in, made explicit rather than left implied by which thread
 * happened to run a handler.
 *
 * <p>Ordering <em>between</em> conduits is not defined here and is not a counter's job (see {@link Provenance}).
 * Resolving a genuine conflict between two conduits is a further step again, and deliberately undefined: there
 * is no general answer, so a winner is picked by criteria the domain chooses — a deterministic hash, an arbiter —
 * and today's single-writer topology means no such conflict can arise (architecture.md §13).
 */
public final class Conduit {

    private static final AtomicLong IDS = new AtomicLong(1);

    private final long id = IDS.getAndIncrement();
    private long sequence;

    /** This conduit's process-unique identity. */
    public long id() {
        return id;
    }

    /**
     * Stamp the next message. <b>Must be called from the conduit's own thread</b> — that confinement is what makes
     * the counter free and its order exact; calling it from two threads silently forfeits both.
     */
    public Provenance next() {
        return new Provenance(id, sequence++, System.nanoTime());
    }
}
