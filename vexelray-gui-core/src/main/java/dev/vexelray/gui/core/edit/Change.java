package dev.vexelray.gui.core.edit;

/**
 * Something that can be done, stated as the thing that undoes it.
 *
 * <p>A change does not carry a direction. {@link #apply()} performs it and returns the change that puts the world
 * back — so the same value, applied again, is the redo. That is the whole of the contract, and it is what lets
 * {@link History} keep one kind of entry and move it between two stacks rather than keeping a do and an undo side
 * by side and hoping they stay in step. A history whose entries had separate {@code undo()} and {@code redo()}
 * methods can be written so that undoing then redoing is not the identity; this one cannot express that.
 *
 * <p>Entries are <b>absolute</b>, not intents. A {@code dev.vexelray.gui.core.text.Edit} is deliberately relative —
 * re-resolved against whatever the document is at commit time — because it describes what the user is asking for
 * now. A history entry describes a state the user already saw, so it must restore <em>that</em> state and never be
 * re-resolved against a caret that has since moved. The two are different jobs and this is the one that is fixed.
 *
 * <p>Applying happens under {@link History}'s monitor, in the order the history decides. Keep it bounded model
 * work: it is the same rule the GUI thread's drain lives by. Long or blocking work belongs behind a change that
 * flips a value something else is watching.
 */
@FunctionalInterface
public interface Change {

    /** Perform this change and return its exact reverse, which is also its redo. */
    Change apply();

    /**
     * Merge {@code following} — the entry recorded immediately after this one — into a single entry, or return
     * {@code null} (the default) to leave them separate. This is how a typing run becomes one Ctrl+Z: the history
     * asks, it does not decide, because only the change knows what continues a run of its own kind.
     *
     * <p>Both arguments are undo-entries, so the merged result must revert <em>both</em>, {@code following}
     * first. A change that cannot answer for what it is given must return {@code null}; nothing is lost by
     * declining, only a stack entry gained. {@link History#barrier()} suppresses the question entirely for the
     * next entry, which is how a caret jump or a paste ends a run without either side knowing about the other.
     */
    default Change coalesce(Change following) {
        return null;
    }
}
