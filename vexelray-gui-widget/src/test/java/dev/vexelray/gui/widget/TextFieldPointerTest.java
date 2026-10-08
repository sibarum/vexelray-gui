package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.Document;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What the pointer does in a text field beyond placing the caret: double-click for a word, triple for a line,
 * holding either and dragging to extend by that unit, and Shift+click to extend what is selected.
 *
 * <p>The field is the whole width of the headless viewport, so nothing wraps; with the monospace
 * {@value HeadlessGui#CELL}px stub, {@link #at} names a point just inside the left of a character.
 */
class TextFieldPointerTest {

    private static final String TEXT = "alpha beta.gamma\n    indented line\nlast";

    private static TextField field(HeadlessGui h) {
        TextField f = new TextField(h.gui, TEXT).multiline(true);
        f.node().width(Length.vw(100)).height(Length.rem(10));
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    /** x of a point just inside the left edge of column {@code col}: a click there lands at offset {@code col}. */
    private static float x(int col) {
        return HeadlessGui.FIELD_PAD_X + col * HeadlessGui.CELL + 2f;
    }

    /** y of the middle of line {@code line}. */
    private static float y(int line) {
        return HeadlessGui.FIELD_PAD_Y + line * HeadlessGui.CELL + HeadlessGui.CELL * 0.5f;
    }

    private static String selected(TextField f) {
        return f.document().value().selectedText();
    }

    @Test
    void doubleClickSelectsTheWord() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.clicks(x(7), y(0), 2);                      // in "beta"
            assertEquals("beta", selected(f));
            assertEquals(10, f.caret(), "the caret ends at the end of the word, as a forward selection");
        }
    }

    /** x of a point on the right half of column {@code col}: nearer the boundary after it than before it. */
    private static float rightHalf(int col) {
        return HeadlessGui.FIELD_PAD_X + col * HeadlessGui.CELL + 7f;
    }

    @Test
    void doubleClickSelectsTheWordThePointerIsOnNotTheNearestBoundary() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.clicks(rightHalf(4), y(0), 2);              // the last 'a' of "alpha", nearer the space
            assertEquals("alpha", selected(f));
        }
    }

    @Test
    void doubleClickPastTheEndOfALineSelectsItsLastWord() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.clicks(x(40), y(0), 2);
            assertEquals("gamma", selected(f));
        }
    }

    @Test
    void doubleClickOnPunctuationOrSpaceSelectsThatRun() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.clicks(rightHalf(10), y(0), 2);             // on the '.', nearer the 'g' of "gamma"
            assertEquals(".", selected(f));
            h.pause().clicks(x(1), y(1), 2);              // in the indentation
            assertEquals("    ", selected(f));
        }
    }

    @Test
    void tripleClickSelectsTheLineWithItsNewline() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.clicks(x(6), y(1), 3);
            assertEquals("    indented line\n", selected(f));
            h.pause().clicks(x(1), y(2), 3);
            assertEquals("last", selected(f), "the last line has no newline to take");
        }
    }

    @Test
    void doubleClickAndDragExtendsByWholeWords() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.pressHeld(x(7), y(0), 2);                   // "beta"
            h.hover(x(12), y(0));                         // into "gamma"
            assertEquals("beta.gamma", selected(f), "the word under the pointer is taken whole");

            h.hover(x(1), y(0));                          // back past the start, into "alpha"
            assertEquals("alpha beta", selected(f), "dragging back keeps the first word and takes the one under the pointer");
            Document d = f.document().value();
            assertEquals(0, d.caret(), "the caret follows the pointer");
            assertEquals(10, d.anchor(), "and the anchor moved to the far end of the word first picked");

            h.release(x(1), y(0));
            assertEquals("alpha beta", selected(f));
        }
    }

    @Test
    void tripleClickAndDragExtendsByWholeLines() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.pressHeld(x(3), y(0), 3);
            h.hover(x(2), y(1));
            assertEquals("alpha beta.gamma\n    indented line\n", selected(f));
            h.release(x(2), y(1));
        }
    }

    @Test
    void shiftClickExtendsTheSelectionFromItsAnchor() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.clicks(x(2), y(0), 1);
            h.pause().down(Key.LEFT_SHIFT).frame();
            h.clicks(x(9), y(0), 1);
            h.up(Key.LEFT_SHIFT).frame();
            assertEquals("pha bet", selected(f));

            h.pause().down(Key.LEFT_SHIFT).frame();
            h.clicks(x(0), y(0), 1);                      // to the other side of the anchor
            h.up(Key.LEFT_SHIFT).frame();
            assertEquals("al", selected(f), "the anchor stays where the selection began");
        }
    }

    @Test
    void slowClicksAreSingleClicks() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            h.clicks(x(7), y(0), 1);
            h.pause().clicks(x(7), y(0), 1);
            assertFalse(f.document().value().hasSelection(), "two clicks a second apart are two clicks");
            assertEquals(7, f.caret());
        }
    }
}
