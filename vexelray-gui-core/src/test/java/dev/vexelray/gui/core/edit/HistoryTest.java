package dev.vexelray.gui.core.edit;

import dev.vexelray.gui.core.nav.Address;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The general history, exercised against a model it knows nothing about — a list of strings — which is the point:
 * everything asserted here holds for a text field, a canvas or a document without any of them appearing.
 */
final class HistoryTest {

    /** A change over a {@code List<String>}: set slot {@code i} to {@code to}, and hand back the way home. */
    private record Set(List<String> model, int at, String to) implements Change {

        @Override
        public Change apply() {
            String was = model.get(at);
            model.set(at, to);
            return new Set(model, at, was);
        }

        @Override
        public Change coalesce(Change following) {
            // A run is successive edits to the same slot; the merged undo is this one's, which is the older state.
            return following instanceof Set next && next.at == at && next.model == model ? this : null;
        }
    }

    private static List<String> model() {
        return new ArrayList<>(List.of("a", "b"));
    }

    @Test
    void performAppliesAndUndoRestores() {
        List<String> m = model();
        History h = new History();
        h.perform(new Set(m, 0, "x"));
        assertEquals(List.of("x", "b"), m, "perform applies the change");
        assertTrue(h.undo());
        assertEquals(List.of("a", "b"), m, "undo restores the prior state");
        assertTrue(h.redo());
        assertEquals(List.of("x", "b"), m, "redo puts it back");
    }

    @Test
    void nothingToUndoIsAnsweredNotThrown() {
        History h = new History();
        assertFalse(h.undo(), "an empty history undoes nothing");
        assertFalse(h.redo(), "and redoes nothing");
    }

    @Test
    void doingSomethingNewDropsTheRedoStack() {
        List<String> m = model();
        History h = new History();
        h.perform(new Set(m, 0, "x"));
        h.undo();
        assertTrue(h.canRedo());
        h.perform(new Set(m, 1, "y"));
        assertFalse(h.canRedo(), "a new change discards the future the user walked away from");
    }

    @Test
    void aRunCoalescesUntilABarrier() {
        List<String> m = model();
        History h = new History();
        h.perform(new Set(m, 0, "x"));
        h.perform(new Set(m, 0, "xy"));
        assertEquals(1, h.undoDepth(), "successive edits to the same slot are one entry");
        h.undo();
        assertEquals(List.of("a", "b"), m, "the merged entry undoes the whole run");

        h.clear();
        h.perform(new Set(m, 0, "x"));
        h.barrier();
        h.perform(new Set(m, 0, "xy"));
        assertEquals(2, h.undoDepth(), "a barrier ends the run");
    }

    @Test
    void theOldestEntryIsDroppedNotTheNewest() {
        List<String> m = model();
        History h = new History(2);
        h.perform(new Set(m, 0, "1"));
        h.barrier();
        h.perform(new Set(m, 0, "2"));
        h.barrier();
        h.perform(new Set(m, 0, "3"));
        assertEquals(2, h.undoDepth(), "the history stays inside its limit");
        h.undo();
        h.undo();
        assertEquals("1", m.get(0), "the two most recent entries survived; the first was dropped");
        assertFalse(h.canUndo());
    }

    @Test
    void cleanIsAPositionInTheHistory() {
        List<String> m = model();
        History h = new History();
        assertTrue(h.clean(), "a history nothing has been done to is clean");
        h.perform(new Set(m, 0, "x"));
        assertFalse(h.clean());
        h.mark();
        assertTrue(h.clean(), "marking says this is the saved state");
        h.undo();
        assertFalse(h.clean(), "undoing away from the save is dirty again");
        h.redo();
        assertTrue(h.clean(), "and redoing back up to it is clean again — a flag could not do this");
    }

    @Test
    void aMarkSurvivesTrimmingWhileTheStateItNamesStillDoes() {
        List<String> m = model();
        History h = new History(1);
        h.perform(new Set(m, 0, "x"));
        h.mark();
        h.barrier();
        h.perform(new Set(m, 1, "y"));   // drops the oldest entry; the save is now the deepest reachable state
        assertFalse(h.clean());
        h.undo();
        assertTrue(h.clean(), "undoing as far as the history goes lands on the saved state, which it still names");
    }

    @Test
    void aMarkDestroyedByTrimmingReportsDirtyRatherThanLying() {
        List<String> m = model();
        History h = new History(1);
        h.mark();                        // the saved state is the empty one
        h.perform(new Set(m, 0, "x"));
        h.barrier();
        h.perform(new Set(m, 1, "y"));   // the entry that led back to the saved state is gone
        assertFalse(h.clean());
        h.undo();
        assertFalse(h.clean(), "the saved state is unreachable now, so it is never claimed");
    }

    @Test
    void aMarkDestroyedByCoalescingReportsDirty() {
        List<String> m = model();
        History h = new History();
        h.perform(new Set(m, 0, "x"));
        h.mark();
        h.perform(new Set(m, 0, "xy"));   // merges into the marked entry, dissolving the boundary
        assertFalse(h.clean());
        h.undo();
        assertFalse(h.clean(), "the run undoes past the save in one step, so that state cannot be returned to");
    }

    @Test
    void statusRidesTheBus() {
        List<String> m = model();
        History h = new History();
        List<History.Status> seen = new ArrayList<>();
        h.status().onCommit((versioned) -> seen.add(versioned.value()));
        h.perform(new Set(m, 0, "x"));
        h.undo();
        // Clean without a mark: a history nobody has saved calls its starting state the unmodified one.
        assertEquals(new History.Status(false, true, true, 0, 1), seen.get(seen.size() - 1),
                "subscribers are told what is possible and whether the work is saved");
    }

    // ---- refusal: docs/plans/undo.md §2, rules 6 to 8 ----

    /**
     * {@link Set} that looks before it applies: slot {@code at} must still hold {@code expect}, the value the edit
     * it reverts left there. {@code closed} stands in for a disabled widget.
     */
    private record Guarded(List<String> model, int at, String to, String expect, AtomicBoolean closed)
            implements Change {

        Guarded(List<String> model, int at, String to, AtomicBoolean closed) {
            this(model, at, to, model.get(at), closed);
        }

        @Override
        public Change apply() {
            String was = model.get(at);
            model.set(at, to);
            return new Guarded(model, at, was, to, closed);
        }

        @Override
        public Check check() {
            if (closed.get()) {
                return Check.REFUSED;
            }
            return model.get(at).equals(expect) ? Check.OK : Check.CONFLICT;
        }

        @Override
        public Target target() {
            return Target.node(100 + at);
        }
    }

    @Test
    void aConflictMovesNothing() {
        List<String> m = model();
        History h = new History();
        h.perform(new Guarded(m, 0, "x", new AtomicBoolean()));
        m.set(0, "z");   // written underneath the history, as a sync from the model would
        assertEquals(Outcome.CONFLICT, h.attemptUndo());
        assertEquals("z", m.get(0), "the entry was not applied over a value it was not computed against");
        assertEquals(1, h.undoDepth(), "and it is still there");
        assertFalse(h.undo(), "the boolean form answers false for a failure too");
    }

    @Test
    void aFailedUndoKeepsTheRedo() {
        List<String> m = model();
        History h = new History();
        AtomicBoolean closed = new AtomicBoolean();
        h.perform(new Guarded(m, 0, "x", closed));
        h.barrier();
        h.perform(new Guarded(m, 1, "y", closed));
        assertEquals(Outcome.APPLIED, h.attemptUndo());
        m.set(0, "z");
        assertEquals(Outcome.CONFLICT, h.attemptUndo());
        assertEquals(1, h.redoDepth(), "the failure did not cost the redo");
        assertEquals(Outcome.APPLIED, h.attemptRedo(), "and redo still works after an undo failed");
        assertEquals(List.of("z", "y"), m);
    }

    @Test
    void redoFailsForTheSameReasons() {
        List<String> m = model();
        History h = new History();
        AtomicBoolean closed = new AtomicBoolean();
        h.perform(new Guarded(m, 0, "x", closed));
        h.undo();
        closed.set(true);
        assertEquals(Outcome.REFUSED, h.attemptRedo(), "a closed target refuses a redo as it would an undo");
        closed.set(false);
        m.set(0, "z");
        assertEquals(Outcome.CONFLICT, h.attemptRedo());
        assertEquals(1, h.redoDepth());
        assertEquals("z", m.get(0));
    }

    @Test
    void blockMeetsTheSameFailureAgain() {
        List<String> m = model();
        History h = new History();
        AtomicBoolean closed = new AtomicBoolean();
        h.perform(new Guarded(m, 0, "x", closed));
        h.barrier();
        h.perform(new Guarded(m, 1, "y", closed));
        closed.set(true);
        assertEquals(Outcome.REFUSED, h.attemptUndo());
        assertEquals(Outcome.REFUSED, h.attemptUndo(), "the failed entry stays in the way by default");
        assertEquals(List.of("x", "y"), m);
    }

    @Test
    void dropReportsOnceAndGoesOn() {
        List<String> m = model();
        History h = new History().failure(History.Failure.DROP);
        h.perform(new Guarded(m, 0, "x", new AtomicBoolean()));
        h.barrier();
        h.perform(new Guarded(m, 1, "y", new AtomicBoolean()));
        m.set(1, "z");
        assertEquals(Outcome.CONFLICT, h.attemptUndo(), "the failure is still reported");
        assertEquals(1, h.undoDepth(), "and the entry is gone");
        assertEquals(Outcome.APPLIED, h.attemptUndo(), "so the next press undoes the one below");
        assertEquals(List.of("a", "z"), m);
    }

    @Test
    void droppingKeepsTheSaveHonest() {
        List<String> m = model();
        History h = new History().failure(History.Failure.DROP);
        h.perform(new Guarded(m, 0, "x", new AtomicBoolean()));
        h.barrier();
        h.perform(new Guarded(m, 1, "y", new AtomicBoolean()));
        h.mark();
        m.set(1, "z");
        h.attemptUndo();
        assertTrue(h.clean(), "dropping changed nothing on screen, so a save of it still stands");
        h.undo();
        assertFalse(h.clean());

        History below = new History().failure(History.Failure.DROP);
        List<String> n = model();
        below.mark();
        below.perform(new Guarded(n, 0, "x", new AtomicBoolean()));
        n.set(0, "z");
        below.attemptUndo();
        assertFalse(below.clean(), "the way back to the save went through the dropped entry, so it is gone");
    }

    @Test
    void theTargetIsReadFromTheTopAndRidesTheReverse() {
        List<String> m = model();
        History h = new History();
        assertNull(h.undoTarget(), "an empty history has no target");
        h.perform(new Set(m, 0, "x"));
        assertNull(h.undoTarget(), "a change that names no target is applied where the user is");
        h.perform(new Guarded(m, 1, "y", new AtomicBoolean()));
        assertEquals(Target.node(101), h.undoTarget());
        h.undo();
        assertEquals(Target.node(101), h.redoTarget(), "a redo goes where the undo went");
    }

    @Test
    void emptyIsAnAnswerNotAFailure() {
        History h = new History();
        assertEquals(Outcome.EMPTY, h.attemptUndo());
        assertEquals(Outcome.EMPTY, h.attemptRedo());
        assertFalse(Outcome.EMPTY.failed());
        assertTrue(Outcome.CONFLICT.failed());
    }

    @Test
    void aTargetIsANodeOrAnAddressNeverNeither() {
        assertThrows(IllegalArgumentException.class, () -> Target.node(Target.NO_NODE));
        assertThrows(IllegalArgumentException.class, () -> new Target(Target.NO_NODE, null));
        assertTrue(Target.node(3).isNode());
        assertFalse(Target.address(Address.of("theme.accent")).isNode());
    }
}
