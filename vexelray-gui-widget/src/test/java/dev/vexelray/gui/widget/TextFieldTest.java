package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.text.Span;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TextField behaviour driven entirely through the Atchung bus in a headless GUI (see {@link HeadlessGui}) —
 * deterministic, no window, no worker threads. Covers the logic that has no other automated coverage: undo/redo
 * run coalescing, selection edits, clipboard, word motion, and span auto-diff.
 */
class TextFieldTest {

    private static final Color RED = Color.rgb(0xff0000);

    private static TextField focusedField(HeadlessGui h, String initial) {
        TextField f = new TextField(h.gui, initial);
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    @Test
    void typingRunUndoesAsOneEntryAndRedoes() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "");
            h.type("hello");
            assertEquals("hello", f.text());

            h.chord(Key.Z, Key.LEFT_CONTROL);           // Ctrl+Z
            assertEquals("", f.text(), "a contiguous typing run undoes in one step");

            h.chord(Key.Y, Key.LEFT_CONTROL);           // Ctrl+Y
            assertEquals("hello", f.text(), "redo restores it");
        }
    }

    @Test
    void caretMoveBreaksTheTypingRun() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "");
            h.type("ab");
            h.tap(Key.LEFT);       // caret move ends the run
            h.type("c");           // "acb"
            assertEquals("acb", f.text());

            h.chord(Key.Z, Key.LEFT_CONTROL);
            assertEquals("ab", f.text(), "only the post-move insertion undoes; the earlier run is a separate entry");
        }
    }

    @Test
    void backspaceRunUndoesAsOneEntry() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "");
            h.type("abc");
            h.tap(Key.BACKSPACE);
            h.tap(Key.BACKSPACE);
            h.tap(Key.BACKSPACE);
            assertEquals("", f.text());

            h.chord(Key.Z, Key.LEFT_CONTROL);
            assertEquals("abc", f.text(), "a run of backspaces restores in one undo");
        }
    }

    @Test
    void selectAllThenTypeReplaces() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "abc");
            h.tap(Key.END);
            h.chord(Key.A, Key.LEFT_CONTROL);   // select all
            h.type("x");
            assertEquals("x", f.text(), "typing over a selection replaces it");
        }
    }

    @Test
    void shiftArrowSelectionIsReplacedByTyping() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "abcde");
            h.tap(Key.END);
            h.chord(Key.LEFT, Key.LEFT_SHIFT);  // select "e"
            h.chord(Key.LEFT, Key.LEFT_SHIFT);  // select "de"
            h.type("X");
            assertEquals("abcX", f.text());
        }
    }

    @Test
    void ctrlBackspaceDeletesWord() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "foo bar");
            h.tap(Key.END);
            h.chord(Key.BACKSPACE, Key.LEFT_CONTROL);
            assertEquals("foo ", f.text(), "Ctrl+Backspace removes the previous word");
        }
    }

    @Test
    void cutThenPasteRoundTrips() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "abc");
            h.tap(Key.END);
            h.chord(Key.A, Key.LEFT_CONTROL);   // select all
            h.chord(Key.X, Key.LEFT_CONTROL);   // cut
            assertEquals("", f.text());
            h.chord(Key.V, Key.LEFT_CONTROL);   // paste
            assertEquals("abc", f.text());
        }
    }

    @Test
    void spansShiftWithInsertedText() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "abcd");
            f.setSpans(List.of(Span.foreground(1, 3, RED))); // covers "bc"
            h.tap(Key.HOME);
            h.type("X");                                     // "Xabcd"
            List<Span> spans = f.spans();
            assertEquals(1, spans.size());
            assertEquals(2, spans.get(0).start(), "span shifts right by the insertion");
            assertEquals(4, spans.get(0).end());
        }
    }

    @Test
    void clickPositionsCaretForInsertion() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = focusedField(h, "abcd");
            // The field is the first child of the root column; its text starts at x = pad (10). Monospace CELL=10,
            // so clicking near x = pad + 2*CELL lands the caret at offset 2. Click y within the field's row.
            float y = f.node() == null ? 0 : 5f;
            h.click(10f + 2f * HeadlessGui.CELL + 1f, y);
            h.type("X");
            assertEquals("abXcd", f.text(), "clicking mid-text places the caret there");
        }
    }
}
