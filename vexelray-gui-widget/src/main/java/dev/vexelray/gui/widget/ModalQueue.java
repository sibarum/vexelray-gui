package dev.vexelray.gui.widget;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The order dialogs are shown in: one at a time, first asked first shown. All of {@link Modals}'s sequencing and
 * none of its windows, so the rule that matters — <b>never two dialogs at once, never a queued one forgotten</b>
 * — is testable without a GPU.
 *
 * <p>Two threads race here by design: a worker asking a question while another dialog is up, and the frame loop
 * reporting that a dialog has closed. Both go through this monitor, so "is one showing?" and "take the next one"
 * are the same decision rather than two that can interleave — the check-then-act that would otherwise open a
 * second dialog in the gap.
 */
final class ModalQueue {

    private final Deque<Modal> waiting = new ArrayDeque<>();
    private Modal showing;

    /**
     * Add {@code modal} to the queue. Returns it if it should be presented immediately (nothing was showing), or
     * {@code null} if it must wait — in which case it has been queued and will come back from {@link #next()}.
     */
    synchronized Modal offer(Modal modal) {
        if (showing != null) {
            waiting.add(modal);
            return null;
        }
        showing = modal;
        return modal;
    }

    /**
     * The dialog that was showing has closed. Returns the next one to present, or {@code null} when the queue is
     * empty — at which point nothing is showing and the application is unblocked.
     */
    synchronized Modal next() {
        showing = waiting.poll();
        return showing;
    }

    /** The dialog currently up, or null. */
    synchronized Modal showing() {
        return showing;
    }

    /** How many dialogs are waiting behind the one that is up. */
    synchronized int queued() {
        return waiting.size();
    }

    /** Forget everything queued; what is showing is left alone (its window still has to close). */
    synchronized void clear() {
        waiting.clear();
    }
}
