package dev.vexelray.gui.core.drop;

import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragSessionTest {

    private static final PayloadType<String> ROW = PayloadType.of("row");
    private static final PayloadType<List<Path>> FILES = PayloadType.of("files");

    /** A change that appends to a log and reverts by appending the opposite. */
    private static Change logging(List<String> log, String what) {
        return new Change() {
            @Override
            public Change apply() {
                log.add(what);
                return logging(log, "un" + what);
            }
        };
    }

    private static DragSession.Lookup targets(DropTarget... innermostFirst) {
        return (x, y) -> List.of(innermostFirst);
    }

    private static Drop moveTo(Rect where, List<String> log, String what) {
        return Drop.move(where, logging(log, what));
    }

    @Test
    @DisplayName("a session opens knowing its payload, before anything has been resolved")
    void openingResolvesNothing() {
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 10f, 20f);

        assertEquals(Drop.NONE, drag.drop(), "nothing has been asked yet, so nothing is promised");
        assertEquals(10f, drag.x());
        assertEquals(0, drag.framesSeen());
        assertFalse(drag.ended());
    }

    @Test
    @DisplayName("the innermost target that accepts wins")
    void innermostAcceptanceWins() {
        List<String> log = new ArrayList<>();
        DropTarget row = (p, x, y) -> moveTo(new Rect(0f, 0f, 100f, 2f), log, "into-row");
        DropTarget tree = (p, x, y) -> moveTo(new Rect(0f, 0f, 100f, 40f), log, "into-tree");
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);

        drag.moveTo(5f, 5f, targets(row, tree));

        assertEquals(2f, drag.drop().indicator().h(), "the row's answer, not the tree's");
    }

    @Test
    @DisplayName("a declining node does not punch a hole in the container that would have accepted")
    void declinesBubbleRatherThanBlock() {
        List<String> log = new ArrayList<>();
        DropTarget fussyRow = (p, x, y) -> Drop.NONE;                 // this row wants nothing to do with it
        DropTarget tree = (p, x, y) -> moveTo(new Rect(0f, 0f, 100f, 40f), log, "into-tree");
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);

        drag.moveTo(5f, 5f, targets(fussyRow, tree));

        assertTrue(drag.drop().accepts(), "an inner decline is not a veto — that would be a row-shaped dead zone");
    }

    @Test
    @DisplayName("where nothing accepts, the answer is a chosen NONE rather than a gap")
    void nothingAcceptingIsStillAnAnswer() {
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);

        drag.moveTo(5f, 5f, targets((p, x, y) -> Drop.NONE));

        assertEquals(Drop.NONE, drag.drop());
        assertFalse(drag.drop().accepts());
    }

    @Test
    @DisplayName("a target reads the payload in the form it understands and ignores the rest")
    void targetsChooseTheirOwnForm() {
        Payload dragged = Payload.of(ROW, "a").and(FILES, List.of(Path.of("x.txt")));
        List<String> log = new ArrayList<>();
        DropTarget filesOnly = (p, x, y) -> p.as(FILES)
                .map(f -> moveTo(new Rect(0f, 0f, 1f, 1f), log, "files:" + f.size()))
                .orElse(Drop.NONE);
        DragSession drag = DragSession.open(dragged, 0f, 0f);

        drag.moveTo(1f, 1f, targets(filesOnly));
        drag.seen();
        History history = new History();
        drag.commit(history);

        assertEquals(List.of("files:1"), log);
    }

    @Test
    @DisplayName("moving re-resolves, so a scroll under a still pointer is not stale")
    void resolutionIsRecomputedEveryMove() {
        List<String> log = new ArrayList<>();
        List<Float> asked = new ArrayList<>();
        DropTarget target = (p, x, y) -> {
            asked.add(y);
            return moveTo(new Rect(0f, y, 10f, 1f), log, "at" + y);
        };
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);

        drag.moveTo(0f, 5f, targets(target));
        drag.moveTo(0f, 5f, targets(target));   // same point: the tree may have moved underneath it

        assertEquals(List.of(5f, 5f), asked, "re-asking is not conditional on the pointer having moved");
    }

    /**
     * The protection against the click that registered as a drag. A gesture that never rendered cannot have been
     * deliberate, whatever distance it covered.
     */
    @Test
    @DisplayName("a drop the user never saw does not commit")
    void anUnseenDropIsNotADrop() {
        List<String> log = new ArrayList<>();
        History history = new History();
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);
        drag.moveTo(1f, 1f, targets((p, x, y) -> moveTo(new Rect(0f, 0f, 1f, 1f), log, "moved")));

        Drop performed = drag.commit(history);

        assertEquals(Drop.NONE, performed);
        assertEquals(List.of(), log, "nothing was applied");
        assertFalse(history.canUndo(), "and nothing was recorded to undo");
    }

    @Test
    @DisplayName("a drop the user did see commits once, and is undoable")
    void aSeenDropCommitsAndIsUndoable() {
        List<String> log = new ArrayList<>();
        History history = new History();
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);
        drag.moveTo(1f, 1f, targets((p, x, y) -> moveTo(new Rect(0f, 0f, 1f, 1f), log, "moved")));
        drag.seen();

        assertTrue(drag.commit(history).accepts());
        assertEquals(List.of("moved"), log);

        history.undo();
        assertEquals(List.of("moved", "unmoved"), log, "the drop's own change is what puts it back");
    }

    @Test
    @DisplayName("committing twice performs once")
    void commitIsIdempotent() {
        List<String> log = new ArrayList<>();
        History history = new History();
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);
        drag.moveTo(1f, 1f, targets((p, x, y) -> moveTo(new Rect(0f, 0f, 1f, 1f), log, "moved")));
        drag.seen();

        drag.commit(history);
        assertEquals(Drop.NONE, drag.commit(history));
        assertEquals(List.of("moved"), log);
    }

    /**
     * A cancel must not record-then-undo: {@code History.record} discards the redo stack, so doing that would
     * silently destroy redoable work as the price of abandoning a drag.
     */
    @Test
    @DisplayName("a cancelled drag leaves the history exactly as it found it")
    void cancellingRecordsNothingAndKeepsRedo() {
        List<String> log = new ArrayList<>();
        History history = new History();
        history.perform(logging(log, "earlier"));
        history.undo();
        assertTrue(history.canRedo(), "precondition: there is redoable work to protect");

        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);
        drag.moveTo(1f, 1f, targets((p, x, y) -> moveTo(new Rect(0f, 0f, 1f, 1f), log, "moved")));
        drag.seen();
        drag.cancel();

        assertTrue(history.canRedo(), "abandoning a drag must not cost the user their redo stack");
        assertFalse(drag.drop().accepts());
        assertTrue(drag.ended());
    }

    @Test
    @DisplayName("a cancelled session stops resolving and cannot then commit")
    void cancellingIsTerminal() {
        List<String> log = new ArrayList<>();
        History history = new History();
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);
        drag.seen();
        drag.cancel();

        drag.moveTo(1f, 1f, targets((p, x, y) -> moveTo(new Rect(0f, 0f, 1f, 1f), log, "moved")));

        assertEquals(Drop.NONE, drag.drop());
        assertEquals(Drop.NONE, drag.commit(history));
        assertEquals(List.of(), log);
    }

    @Test
    @DisplayName("a drop cannot promise an effect it has no change for, nor hide one it does")
    void aDropCannotLie() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drop(DropEffect.MOVE, Rect.ZERO, null),
                "an accepting drop with nothing to perform would fail only at release");
        assertThrows(IllegalArgumentException.class,
                () -> new Drop(DropEffect.NONE, Rect.ZERO, () -> null),
                "a change carried under NONE would run despite the user being told nothing would happen");
    }

    @Test
    @DisplayName("the indicator and the change come from the same value, so they cannot disagree")
    void theIndicatorIsTheCommittedResolution() {
        List<String> log = new ArrayList<>();
        History history = new History();
        Rect seam = new Rect(0f, 64f, 200f, 2f);
        DragSession drag = DragSession.open(Payload.of(ROW, "a"), 0f, 0f);
        drag.moveTo(1f, 70f, targets((p, x, y) -> moveTo(seam, log, "into-seam-64")));
        drag.seen();

        Drop shown = drag.drop();
        Drop performed = drag.commit(history);

        assertSame(shown, performed, "what was drawn is what happened");
        assertEquals(seam, shown.indicator());
    }
}
