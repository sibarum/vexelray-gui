package dev.vexelray.gui.core;

/**
 * Where a message came from and where it sat in its conduit's order.
 *
 * <p>{@code conduitId} + {@code sequence} <b>is</b> the order, and it is exact: a conduit is single-threaded, so
 * its counter has no uncertainty, no contention and nothing to synchronize. A gap means loss and an inversion
 * means a transport bug, both unambiguously. What it deliberately does not do is order two <em>different</em>
 * conduits — those counters are incomparable, and a scheme that pretends otherwise is the one that correlates
 * well enough to hide the bug until there are two producers.
 *
 * <p>{@code stampNanos} is evidence rather than order: it is {@link System#nanoTime()}, monotonic within this
 * process and with no shared epoch, so it measures elapsed time and nothing else. Comparing stamps across
 * processes needs an epoch this does not have — that is a decision for the wire contract (M1), and any such
 * comparison carries an uncertainty interval that ordering within a conduit does not.
 *
 * @param conduitId  the ordering domain this message was sequenced in
 * @param sequence   its position in that domain, from 0, gapless
 * @param stampNanos {@code System.nanoTime()} when it was sequenced — intra-process only
 */
public record Provenance(long conduitId, long sequence, long stampNanos) {
}
