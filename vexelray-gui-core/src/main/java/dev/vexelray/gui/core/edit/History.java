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

    /** Undo one entry. Returns false if there was nothing to undo. */
    public boolean undo() {
        return step(undo, redo);
    }

    /** Redo one undone entry. Returns false if there was nothing to redo. */
    public boolean redo() {
        return step(redo, undo);
    }

    private boolean step(Deque<Change> from, Deque<Change> to) {
        synchronized (this) {
            Change entry = from.poll();
            if (entry == null) {
                return false;
            }
            to.push(entry.apply());
            barrier = true;   // whatever comes next is a new run, not a continuation of the one just undone
        }
        published();
        return true;
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

    /** Publish the current status, with the monitor released so a listener cannot re-enter. */
    private void published() {
        Status next;
        synchronized (this) {
            next = new Status(!undo.isEmpty(), !redo.isEmpty(), clean(), undo.size(), redo.size());
        }
        status.commit(publish, next);
    }
}
