package dev.vexelray.gui.core;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the frame loop still owes this tree, as one count — the read side of the wake, for
 * {@link Gui#frameOwed()} and the automation driver's {@code settle}.
 *
 * <p><b>One count, not two flags.</b> Work travels through two stages: a <em>wake</em> (something was published
 * and no frame has started on it) and a <em>frame</em> that took that wake (dispatch input and run its handlers,
 * drain, lay out, publish), and a frame that ran a handler wakes the loop again, because a handler's effect need
 * not be a mutation. Each stage was once read on its own (0.1 had a third, a handler on a worker lane), and the
 * reader could always land between two of them: the wake flag is cleared at the top of the frame, so a
 * {@code settle} arriving while that frame was still dispatching the click saw nothing owed and answered
 * {@code ok} against the tree from before the click. Reading flags in any order cannot close that, because the
 * stages form a cycle and some order of reads always falls through the hand-over.
 *
 * <p>So every stage <b>takes its unit before the stage that handed it over pays</b>: a frame keeps the wake's
 * unit until it has published, and a frame that ran a handler wakes before it pays. The count therefore never
 * touches zero while work is moving, and zero means exactly "the loop has caught up with everything it has been
 * told, and everything that followed from it".
 *
 * <p>A frame that was not woken takes nothing. A loop that runs frames back to back — a test spinning
 * {@code frame}, a host that never parks — would otherwise hold a unit almost continuously, and a reader would
 * meet zero only for the instant between two frames.
 */
final class Owed {

    private final AtomicInteger units = new AtomicInteger();
    private final Object paid = new Object();

    /** Starts owing one: a tree is owed its first frame, and "nothing laid out yet" is not "settled". */
    Owed() {
        units.set(1);
    }

    void owe() {
        units.incrementAndGet();
    }

    void pay() {
        if (units.decrementAndGet() <= 0) {
            synchronized (paid) {
                paid.notifyAll();
            }
        }
    }

    boolean any() {
        return units.get() > 0;
    }

    /**
     * Block until nothing is owed, bounded — {@code true} once that holds, {@code false} if it still does not
     * when {@code timeout} runs out. Answers at once when nothing is owed already.
     */
    boolean awaitPaid(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (paid) {
            // Checked under the lock the payer notifies under, so a payment between the check and the wait is
            // not a notification nobody was waiting for.
            while (any()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(paid, remaining);
            }
            return true;
        }
    }

    /** What is owed, as a phrase for a refusal: {@code "frame still owed"}. */
    String describe() {
        return any() ? "frame still owed" : "nothing owed";
    }
}
