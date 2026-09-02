package dev.vexelray.gui.widget;

import dev.vexelray.gui.widget.SelectionModel.Mode;
import dev.vexelray.gui.widget.SelectionModel.Order;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selection's claims, written as the gestures they exist to make agree. Each test names the gesture rather
 * than the method, because the reason this type exists is that four gestures are three operations, and the bugs
 * it prevents are all of the form "this one path disagrees with that one".
 */
class SelectionModelTest {

    /** Ten rows, the order a range runs along. Mutable, because the interesting cases change it underneath. */
    private final List<String> rows = new ArrayList<>(
            List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));
    private final Order<String> order = Order.of(rows);

    // ------------------------------------------------------------------ the invariant

    /**
     * The reason the anchor is state and the extent is not folded into the set. Two Shift-clicks select the
     * <em>second</em> range, not the union of both — the bug every hand-rolled selection has, because a set of
     * selected items has nowhere to put "and this part was a range".
     */
    @Test
    void aSecondShiftClickReplacesTheRangeRatherThanAddingToIt() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("c");
        s.extendTo("f", order);
        assertEquals(Set.of("c", "d", "e", "f"), s.selection());

        s.extendTo("d", order);
        assertEquals(Set.of("c", "d"), s.selection(), "the range shrank back toward the anchor, it did not union");

        s.extendTo("a", order);
        assertEquals(Set.of("a", "b", "c"), s.selection(), "and it runs backwards from the same anchor");
        assertEquals("c", s.anchor(), "which is unmoved by extending");
        assertEquals("a", s.lead(), "while the lead follows the user");
    }

    /**
     * Shift+Arrow is Shift-click one row further, and that is the whole of it. Stated because it is the pair that
     * disagrees first when a widget keeps two code paths.
     */
    @Test
    void shiftArrowIsShiftClickOneRowOn() {
        SelectionModel<String> keyboard = SelectionModel.range();
        SelectionModel<String> pointer = SelectionModel.range();
        keyboard.at("d");
        pointer.at("d");

        for (int i = rows.indexOf("d") + 1; i <= rows.indexOf("g"); i++) {
            keyboard.extendTo(rows.get(i), order);   // one row at a time, as an arrow key arrives
        }
        pointer.extendTo("g", order);                // and in one jump, as a shift-click

        assertEquals(pointer.selection(), keyboard.selection());
        assertEquals(Set.of("d", "e", "f", "g"), keyboard.selection());
    }

    /** A rubber band is the same operation once per pointer move, which is why it needs nothing of its own. */
    @Test
    void aRubberBandIsExtendToPerPointerMove() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("b");
        for (String over : List.of("c", "d", "e", "d", "c")) {   // dragged out and part-way back
            s.extendTo(over, order);
        }
        assertEquals(Set.of("b", "c"), s.selection(), "the band shrinks as the pointer comes back");
    }

    /** Ctrl-click moves the anchor, so a range afterwards grows from where the pointer last was, not from before. */
    @Test
    void ctrlClickMovesTheAnchorAndKeepsWhatWasAlreadyThere() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("a");
        s.extendTo("c", order);
        s.toggle("g");
        assertEquals(Set.of("a", "b", "c", "g"), s.selection(), "the earlier range survives the ctrl-click");
        assertEquals("g", s.anchor());

        s.extendTo("i", order);
        assertEquals(Set.of("a", "b", "c", "g", "h", "i"), s.selection(), "and the new range grows from it");
    }

    @Test
    void ctrlClickingASelectedItemDeselectsIt() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("a");
        s.toggle("b");
        s.toggle("a");
        assertEquals(Set.of("b"), s.selection());
        assertFalse(s.isSelected("a"));
    }

    /** A plain click ends whatever was going on: one item, and a fresh anchor. */
    @Test
    void aPlainClickReplacesEverything() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("a");
        s.extendTo("e", order);
        s.at("h");
        assertEquals(Set.of("h"), s.selection());
        assertEquals("h", s.anchor());
    }

    /** The first Shift-click in a fresh list selects one row — there is no anchor to reach from yet. */
    @Test
    void shiftClickingWithNoAnchorSelectsOneRow() {
        SelectionModel<String> s = SelectionModel.range();
        s.extendTo("e", order);
        assertEquals(Set.of("e"), s.selection());
        assertEquals("e", s.anchor());
    }

    // ------------------------------------------------------------------ mode as a capability

    @Test
    void aSingleSelectionCannotBeTalkedIntoHoldingTwo() {
        SelectionModel<String> s = SelectionModel.single();
        s.at("a");
        s.toggle("b");
        assertEquals(Set.of("b"), s.selection(), "ctrl-click is a plain click here");
        s.extendTo("f", order);
        assertEquals(Set.of("f"), s.selection(), "and so is shift-click");
        s.all(order);
        assertEquals(Set.of("f"), s.selection(), "and select-all is not offered at all");
    }

    @Test
    void aMultipleSelectionTogglesButDoesNotRange() {
        SelectionModel<String> s = new SelectionModel<>(Mode.MULTIPLE);
        s.at("a");
        s.toggle("c");
        assertEquals(Set.of("a", "c"), s.selection());
        s.extendTo("f", order);
        assertEquals(Set.of("a", "c", "f"), s.selection(), "shift degrades to the nearest thing this mode has");
    }

    @Test
    void selectAllTakesTheOrderAndLeavesAUsableAnchor() {
        SelectionModel<String> s = SelectionModel.range();
        s.all(order);
        assertEquals(Set.copyOf(rows), s.selection());
        assertEquals("a", s.anchor());
        assertEquals("j", s.lead());
    }

    // ------------------------------------------------------------------ identity, not index

    /**
     * The reason the model holds items and asks for the order per call. Rows move under a selection constantly —
     * an expand, a sort, a filter — and a stored index would silently come to mean a different row.
     */
    @Test
    void theSelectionSurvivesTheRowsMovingUnderneathIt() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("h");
        s.extendTo("i", order);
        assertEquals(Set.of("h", "i"), s.selection());

        rows.add(0, "new");                       // everything shifts down by one
        assertEquals(Set.of("h", "i"), s.selection(), "the same rows are selected, not the same positions");

        s.extendTo("j", order);
        assertEquals(Set.of("h", "i", "j"), s.selection(), "and the anchor still means the row it always did");
    }

    /** An endpoint that has been filtered away has no position, so the range reaches nothing rather than guessing. */
    @Test
    void aRangeToAnItemThatIsNoLongerShownSelectsNothing() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("c");
        s.extendTo("e", order);
        rows.removeAll(List.of("d", "e"));
        s.extendTo("e", order);
        assertEquals(Set.of("c"), s.selection(), "the base is still there; the extent reaches nowhere");
    }

    @Test
    void retainAllDropsWhatIsGoneAndLeavesAnAnchorThatExists() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("c");
        s.extendTo("f", order);
        s.retainAll(List.of("a", "b", "c", "d"));
        assertEquals(Set.of("c", "d"), s.selection());
        assertTrue(s.selection().contains(s.anchor()), "an anchor nobody can see is not an anchor");

        s.retainAll(List.of("x"));
        assertTrue(s.selection().isEmpty());
        assertNull(s.anchor(), "with nothing left, the next shift-click starts fresh rather than reaching back");
    }

    // ------------------------------------------------------------------ notification

    @Test
    void aChangeIsAnnouncedOnceAndOnlyWhenTheSelectionActuallyMoved() {
        SelectionModel<String> s = SelectionModel.range();
        AtomicInteger announced = new AtomicInteger();
        s.onChange(sel -> announced.incrementAndGet());

        s.at("a");
        assertEquals(1, announced.get());
        s.at("a");
        assertEquals(1, announced.get(), "clicking the selected row again changes nothing, so it says nothing");

        s.extendTo("c", order);
        assertEquals(2, announced.get());
        s.extendTo("c", order);
        assertEquals(2, announced.get(), "and a rubber band resting on the same row is silent too");
    }

    @Test
    void theOneSelectedItemIsTheOneTheUserTouchedLast() {
        SelectionModel<String> s = SelectionModel.range();
        s.at("c");
        s.extendTo("a", order);
        assertEquals("a", s.one(), "the lead, not the lowest-indexed member");
        s.clear();
        assertNull(s.one());
    }

    @Test
    void anApplicationCanRestoreASelectionWholesale() {
        SelectionModel<String> s = SelectionModel.range();
        s.set(List.of("b", "d", "f"));
        assertEquals(Set.of("b", "d", "f"), s.selection());
        assertEquals("f", s.anchor(), "the last restored item, so the user's next shift-click grows from view");

        SelectionModel<String> one = SelectionModel.single();
        one.set(List.of("b", "d", "f"));
        assertEquals(Set.of("b"), one.selection(), "a single selection takes the first and no more");
    }
}
