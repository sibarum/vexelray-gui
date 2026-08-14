package dev.vexelray.gui.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The edit-diff primitive: length delta, inverse symmetry, and forward offset remap (§4.3). */
class TextEditTest {

    @Test
    void deltaAndEnds() {
        TextEdit e = new TextEdit(3, "abc", "XY"); // replace 3 chars with 2
        assertEquals(-1, e.delta());
        assertEquals(6, e.removedEnd());
        assertEquals(5, e.insertedEnd());
    }

    @Test
    void inverseSwapsRemovedAndInserted() {
        TextEdit e = new TextEdit(2, "old", "new");
        TextEdit inv = e.inverse();
        assertEquals(2, inv.at());
        assertEquals("new", inv.removed());
        assertEquals("old", inv.inserted());
        assertEquals(e, inv.inverse(), "inverse is an involution");
    }

    @Test
    void mapForwardShiftsOffsetsAfterInsertion() {
        TextEdit ins = TextEdit.insert(5, "abc"); // +3 at offset 5
        assertEquals(3, ins.mapForward(3), "before the edit: unchanged");
        assertEquals(5, ins.mapForward(5), "at the edit point: unchanged");
        assertEquals(11, ins.mapForward(8), "after the edit: shifted by +3");
    }

    @Test
    void mapForwardPullsOffsetsBackAfterDeletion() {
        TextEdit del = TextEdit.delete(4, "xyz"); // remove [4,7)
        assertEquals(4, del.mapForward(4), "at the deletion start: unchanged");
        assertEquals(4, del.mapForward(6), "inside the deleted span: collapses to the edit point");
        assertEquals(7, del.mapForward(10), "after the deletion: shifted by -3");
    }

    @Test
    void mapForwardCollapsesInsideReplacementToInsertionEnd() {
        TextEdit repl = new TextEdit(2, "abcd", "XY"); // replace [2,6) with 2 chars
        assertEquals(2, repl.mapForward(2));
        assertEquals(4, repl.mapForward(4), "inside the replaced span collapses to the end of the insertion");
        assertEquals(4, repl.mapForward(6), "just past the replaced span: 6 + delta(-2) = 4");
    }
}
