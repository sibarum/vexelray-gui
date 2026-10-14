package dev.vexelray.gui.core.edit;

import sibarum.atchung.Committer;
import sibarum.atchung.State;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Undo and redo for anything: two stacks of {@link Change}s and the rules for moving entries between them.
 *
 * <p>The history holds no model of its own. It never learns what a change is about, so a text field, a canvas, a
 * property sheet and an application's whole document all use this one and nothing here grows a case for any of
 * them. What it does own is the part every ad-hoc undo implementation gets wrong: that redo is discarded when the
 * user does something new, that a typing run collapses into one step, that a bounded stack drops the oldest entry
 * rather than the newest, and that "unsaved" is a question about a <em>position</em> in the history rather than a
 * flag somebody remembers to set.
 *
 * <p><b>Clean is a position, not a flag.</b> {@link #mark()} says "this is the saved state". After that,
 * {@link #clean()} is true exactly when the history stands at that entry again — undoing away from a save makes
 * the work dirty and redoing back up to it makes it clean, which a boolean set on every edit cannot do. If the
 * marked entry is ever destroyed — trimmed off the bottom by {@link #limit()}, or merged into a coalesced run —
 * the position is gone and the history reports dirty until the next {@link #mark()}. That is the honest answer:
 * the state that was saved is no longer reachable.
 *
 * <p><b>Status rides the bus.</b> {@link #status()} is a {@code State<Status>}, so a menu item's enablement, a
 * title bar's dirty dot and an unsaved-changes gate subscribe to it instead of polling, and a subscriber on
 * another thread is indistinguishable from a local one. It is published with the monitor released, so a listener
 * cannot re-enter the history it is being told about.
 *
 * <p><b>An entry may refuse, and a refusal moves nothing.</b> Each entry is asked {@link Change#check()} before it
 * applies; a conflict or a refusal leaves both stacks as they were, so a failed undo never costs the redo, and
 * {@link #attemptUndo()} says which failure it was. Whether the failed entry then stays in the way is
 * {@link #failure(Failure)}. Taking the user to an entry's {@link Change#target()} first is not this class's
 * job — it knows nothing about nodes — but {@link #undoTarget()} is what whoever does it reads
 * (docs/plans/undo.md).
 *
 * <p>Every method is safe from any thread. {@link Change#apply()} runs under the monitor, because interleaving
 * two undos would apply them in an order neither caller asked for; keep applications bounded, as {@link Change}
 * says.
 */
public final class History {

    /** Default cap on retained entries, so a long session cannot grow the stacks without bound. */
    public static final int DEFAULT_LIMIT = 1000;

    /** What a subscriber needs to render a history: what is possible, how much of it, and whether it is saved. */
    public record Status(boolean canUndo, boolean canRedo, boolean clean, int undoDepth, int redoDepth) {
    }

    private final int limit;
    private final Deque<Change> undo = new ArrayDeque<>();
    private final Deque<Change> redo = new ArrayDeque<>();
    private final State<Status> status;
    private final Committer<Status, Status> publish;

    /** What a failed check does to its entry; read under the monitor, written from anywhere. */
    private volatile Failure failure = Failure.BLOCK;

    /** Suppresses coalescing for the next entry — set by {@link #barrier()} and by anything that ends a run. */
    private boolean barrier;

    /**
     * Where {@link #mark()} last put the save: how many undo entries stood at the time, and whether that boundary
     * still exists. A <em>depth</em> rather than the entry itself, because an entry does not survive being
     * applied — undo and redo rebuild it each time, which is {@link Change}'s whole contract — so identity would
     * be lost on the first round trip and a save could never be returned to. Depth 0 while reachable is "clean
     * while nothing is done".
     */
    private int markDepth;
    private boolean markReachable = true;

    /** A history bounded at {@link #DEFAULT_LIMIT} entries. */
    public History() {
        this(DEFAULT_LIMIT);
    }

    /** A history bounded at {@code limit} entries; the oldest is dropped first. */
    public History(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        this.limit = limit;
        State.Builder<Status> builder = State.of(new Status(false, false, true, 0, 0));
        this.publish = builder.mutation("status", (current, next) -> next);
        this.status = builder.build();
    }

    /** The cap on retained entries. */
    public int limit() {
        return limit;
    }

    /** What is possible now, and whether the work is saved. Subscribe rather than poll. */
    public State<Status> status() {
        return status;
    }

    /**
     * Record {@code undoing} — the change that reverts something the caller has <b>already</b> applied. This is
     * the entry point for a model that owns its own mutation path (a widget committing to its own {@code State},
     * an application writing its own document): apply the edit where it belongs, then hand the history the way
     * back. Recording discards the redo stack, because the user has now done something else.
     */
    public void record(Change undoing) {
        Objects.requireNonNull(undoing, "undoing");
        synchronized (this) {
            recordLocked(undoing);
        }
        published();
    }

    /** The stack work of {@link #record}, with the monitor already held and the publication left to the caller. */
    private void recordLocked(Change undoing) {
        redo.clear();
        Change top = undo.peek();
        Change merged = barrier || top == null ? null : top.coalesce(undoing);
        if (merged != null) {
            if (markDepth == undo.size()) {
                markReachable = false;   // the saved state was folded into a run; that boundary is gone
            }
            undo.pop();
            undo.push(merged);
        } else {
            undo.push(undoing);
            while (undo.size() > limit) {
                undo.removeLast();
                if (markDepth == 0) {
                    markReachable = false;   // the saved state was the empty one, and it fell off the bottom
                } else {
                    markDepth--;             // every boundary above the dropped entry shifted down by one
                }
            }
        }
        barrier = false;
    }

    /**
     * Apply {@code change} now and record its reverse. The entry point for a caller that would rather state a
     * change once than write it twice — the doing and the undoing are then the same value, so they cannot
     * disagree.
     */
    public void perform(Change change) {
        Objects.requireNonNull(change, "change");
        synchronized (this) {
            // Applying and recording are one step: two concurrent performs must not apply in one order and
            // record in the other, which is the same reason undo holds the monitor across its own apply.
            recordLocked(change.apply());
        }
        published();
    }

    /**
     * Force the next {@link #record} to start its own entry rather than merging into the run in progress. What
     * ends a run is context the history cannot see — a caret jump, a paste, a selection change — so whoever can
     * see it says so here.
     */
    public synchronized void barrier() {
        barrier = true;
    }

    /**
     * Undo one entry. Returns true exactly when it was applied: false if there was nothing to undo, or if the
     * entry's {@link Change#check()} failed — {@link #attemptUndo()} says which.
     */
    public boolean undo() {
        return attemptUndo() == Outcome.APPLIED;
    }

    /** Redo one undone entry. Returns true exactly when it was applied; {@link #attemptRedo()} says why not. */
    public boolean redo() {
        return attemptRedo() == Outcome.APPLIED;
    }

    /**
     * Undo one entry and say what happened: {@link Outcome#APPLIED}, {@link Outcome#EMPTY}, or the failure its
     * {@link Change#check()} reported. A failure leaves the redo stack as it was, so redo still works after an
     * undo failed; whether the failed entry stays on top is the {@link #failure(Failure)} policy.
     */
    public Outcome attemptUndo() {
        return step(undo, redo);
    }

    /** {@link #attemptUndo()}, in the other direction — failing for exactly the same reasons. */
    public Outcome attemptRedo() {
        return step(redo, undo);
    }

    private Outcome step(Deque<Change> from, Deque<Change> to) {
        Outcome outcome;
        synchronized (this) {
            Change entry = from.peek();
            if (entry == null) {
                return Outcome.EMPTY;
            }
            Change.Check check = entry.check();
            if (check == Change.Check.OK) {
                from.pop();
                to.push(entry.apply());
                barrier = true;   // whatever comes next is a new run, not a continuation of the one just undone
                outcome = Outcome.APPLIED;
            } else {
                outcome = check == Change.Check.CONFLICT ? Outcome.CONFLICT : Outcome.REFUSED;
                if (failure == Failure.BLOCK) {
                    return outcome;   // nothing moved, so there is nothing to publish
                }
                dropLocked(from == undo);
            }
        }
        published();
        return outcome;
    }

    /**
     * Discard the entry on top of the undo stack ({@code fromUndo}) or the redo stack, and keep the save's
     * position honest about it. Dropping changes nothing in the model, so the state the user is looking at stays
     * where it is; what moves is which states the history can still reach.
     */
    private void dropLocked(boolean fromUndo) {
        if (fromUndo) {
            int depth = undo.size();
            undo.pop();
            if (markDepth == depth) {
                markDepth = depth - 1;   // the saved state is the current one, which now stands an entry lower
            } else if (markDepth < depth) {
                markReachable = false;   // undoing below here now keeps the dropped change applied
            } else {
                markDepth--;             // the redo side is unchanged, one entry nearer
            }
        } else {
            redo.pop();
            if (markDepth > undo.size()) {
                markReachable = false;   // the way up to the save went through the dropped entry
            }
        }
    }

    /**
     * Discard the entry on top of the undo stack (or the redo stack, when {@code redo}) if it still names
     * {@code target}; return whether it did. For an entry that can never apply again — its node has left the tree,
     * and node ids are not reused — which under {@link Failure#BLOCK} would otherwise stand in front of every
     * other entry for the rest of the session. The caller has already reported the failure; this only clears the
     * way. Checked against the target so that an entry recorded since the caller looked is never the one dropped.
     */
    public boolean discard(Target target, boolean redo) {
        Objects.requireNonNull(target, "target");
        synchronized (this) {
            Change top = redo ? this.redo.peek() : undo.peek();
            if (top == null || !target.equals(top.target())) {
                return false;
            }
            dropLocked(!redo);
        }
        published();
        return true;
    }

    /** The target of the entry an undo would apply next, or null when there is none or it has no target. */
    public synchronized Target undoTarget() {
        Change top = undo.peek();
        return top == null ? null : top.target();
    }

    /** The target of the entry a redo would apply next, or null when there is none or it has no target. */
    public synchronized Target redoTarget() {
        Change top = redo.peek();
        return top == null ? null : top.target();
    }

    /** What a failed undo or redo does to the entry that failed. */
    public enum Failure {
        /**
         * Leave it on top, so the next press meets the same failure. The default: skipping it would undo the
         * entries below while this one stays applied, which is safe only for changes known to be independent.
         */
        BLOCK,
        /** Discard it, so the next press goes on to the entry below. The failure is still reported, once. */
        DROP
    }

    /** Set what a failed undo or redo does to its entry; {@link Failure#BLOCK} until set. */
    public History failure(Failure policy) {
        this.failure = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /** What a failed undo or redo does to its entry. */
    public Failure failure() {
        return failure;
    }

    /** Whether there is anything to undo. */
    public synchronized boolean canUndo() {
        return !undo.isEmpty();
    }

    /** Whether there is anything to redo. */
    public synchronized boolean canRedo() {
        return !redo.isEmpty();
    }

    /** How many entries can still be undone. */
    public synchronized int undoDepth() {
        return undo.size();
    }

    /** How many entries can still be redone. */
    public synchronized int redoDepth() {
        return redo.size();
    }

    /** Declare the current position saved — call it after writing the file, not before. */
    public void mark() {
        synchronized (this) {
            markDepth = undo.size();
            markReachable = true;
        }
        published();
    }

    /** Whether the history stands where {@link #mark()} last left it — i.e. whether the work is saved. */
    public synchronized boolean clean() {
        return markReachable && undo.size() == markDepth;
    }

    /**
     * Forget everything, including where the save was. For a model that was replaced wholesale rather than edited
     * (a file loaded over the top): its old history describes content that is no longer there. The result is
     * clean, because a freshly loaded document is.
     */
    public void clear() {
        synchronized (this) {
            undo.clear();
            redo.clear();
            markDepth = 0;
            markReachable = true;
            barrier = false;
        }
        published();
    }

    /**
     * Forget every entry, on either stack, that {@code which} accepts — what {@link #clear()} is for one control in
     * a history several share. A field handed new content with no past (a file loaded over the top) has no state
     * its old entries could return to; left in, each would fail its check as a conflict, and under
     * {@link Failure#BLOCK} the first would stand in front of every other control's undo in the window.
     *
     * <p>The other entries keep their order. The save stays reachable only if the history stands at it now, since
     * the states between here and a save elsewhere included what was just forgotten.
     */
    public void forget(java.util.function.Predicate<Change> which) {
        Objects.requireNonNull(which, "which");
        synchronized (this) {
            int depth = undo.size();
            boolean removed = undo.removeIf(which);
            removed |= redo.removeIf(which);
            if (!removed) {
                return;
            }
            if (markReachable && markDepth == depth) {
                markDepth = undo.size();
            } else {
                markReachable = false;
            }
            barrier = true;
        }
        published();
    }

    /** Publish the current status, with the monitor released so a listener cannot re-enter. */
    private void published() {
        Status next;
        synchronized (this) {
            next = new Status(!undo.isEmpty(), !redo.isEmpty(), clean(), undo.size(), redo.size());
        }
        status.commit(publish, next);
    }
}
