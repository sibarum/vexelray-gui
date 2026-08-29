package dev.vexelray.gui.core.edit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
