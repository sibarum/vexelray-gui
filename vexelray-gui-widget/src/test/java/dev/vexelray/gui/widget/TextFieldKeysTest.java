package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The keys every editor answers that the core motion and editing set does not cover on its own: Left and Right
 * over a selection, the code editor's Home, Enter that keeps the indentation, Tab over lines, Escape, and the
 * Insert/Delete clipboard chords.
 */
class TextFieldKeysTest {

    private static TextField field(HeadlessGui h, String text, boolean multiline) {
        TextField f = new TextField(h.gui, text).multiline(multiline);
        f.node().width(Length.vw(100)).height(Length.rem(10));
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    private static String selected(TextField f) {
        return f.document().value().selectedText();
    }

    @Test
    void leftAndRightOverASelectionLandOnItsEdges() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "abcdef", false);
            f.select(2, 4);
            h.tap(Key.LEFT);
            assertEquals(2, f.caret(), "Left lands on the start of the selection, not one before the caret");
            assertFalse(f.document().value().hasSelection());

            f.select(4, 2);                                  // a backwards selection: caret at 2
            h.tap(Key.RIGHT);
            assertEquals(4, f.caret(), "Right lands on the end, whichever end the caret was");
        }
    }

    @Test
    void homeGoesToTheIndentationThenTheMargin() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "x\n    code();", true);
            h.tap(Key.HOME);
            assertEquals(6, f.caret(), "first to where the text starts");
            h.tap(Key.HOME);
            assertEquals(2, f.caret(), "then to the margin");
            h.tap(Key.HOME);
            assertEquals(6, f.caret(), "and back");
        }
    }

    @Test
    void homeInASingleLineFieldIsTheStart() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "  padded", false);
            h.tap(Key.HOME);
            assertEquals(0, f.caret(), "leading blanks in a one-line field are content");
        }
    }

    @Test
    void enterKeepsTheIndentationWhenAsked() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "    if (x) {", true).autoIndent(true);
            h.tap(Key.ENTER);
            h.type("y();");
            assertEquals("    if (x) {\n    y();", f.text());

            f.caret(2);                                      // inside the indentation
            h.tap(Key.ENTER);
            assertEquals("  \n    if (x) {\n    y();", f.text(), "only the indentation left of the caret is carried");
        }
    }

    @Test
    void enterDoesNotIndentByDefault() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "    note", true);
            h.tap(Key.ENTER);
            assertEquals("    note\n", f.text());
        }
    }

    @Test
    void keypadEnterIsEnter() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "ab", true);
            h.tap(Key.NUMPAD_ENTER);
            assertEquals("ab\n", f.text());
        }
    }

    @Test
    void tabOverLinesIndentsThemAsOneUndo() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "one\n\ntwo\nthree", true);
            f.select(1, 7);                                  // from inside "one" to inside "two"
            h.tap(Key.TAB);
            assertEquals("    one\n\n    two\nthree", f.text(), "every touched line but the empty one, and not the next");
            assertEquals("ne\n\n    tw", selected(f), "the selection stays over the same text");

            h.chord(Key.Z, Key.LEFT_CONTROL);
            assertEquals("one\n\ntwo\nthree", f.text(), "one Ctrl+Z takes the whole indent back");
        }
    }

    @Test
    void tabOverAWholeLineSelectionDoesNotReachTheNextLine() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "one\ntwo\n", true);
            f.select(0, 4);                                  // "one\n", as a triple-click leaves it
            h.tap(Key.TAB);
            assertEquals("    one\ntwo\n", f.text());
        }
    }

    @Test
    void tabOverPartOfALineStillReplacesIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "abc", true);
            f.select(1, 2);
            h.tap(Key.TAB);
            assertEquals("a    c", f.text());
        }
    }

    @Test
    void escapeLetsGoOfTheSelection() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "abcdef", false);
            f.select(1, 4);
            h.tap(Key.ESCAPE);
            assertFalse(f.document().value().hasSelection());
            assertEquals(4, f.caret(), "the caret stays where it was");
        }
    }

    @Test
    void theInsertAndDeleteClipboardChords() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, "abcdef", false);
            f.select(0, 2);
            h.chord(Key.INSERT, Key.LEFT_CONTROL);           // copy
            assertEquals("ab", h.gui.clipboard().get());
            assertEquals("abcdef", f.text());

            f.select(2, 4);
            h.chord(Key.DELETE, Key.LEFT_SHIFT);             // cut
            assertEquals("cd", h.gui.clipboard().get());
            assertEquals("abef", f.text());

            f.caret(4);
            h.chord(Key.INSERT, Key.LEFT_SHIFT);             // paste
            assertEquals("abefcd", f.text());
        }
    }
}
