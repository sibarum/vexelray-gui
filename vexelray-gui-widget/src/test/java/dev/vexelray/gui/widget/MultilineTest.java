package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.TextMetrics;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multiline, word wrap and vertical navigation (docs/layout-read-model.md §11) — all of it pure widget code over
 * the published read-model, with no measurer or atlas at the call site and no seam added to core.
 *
 * <p>Geometry, with the monospace {@value HeadlessGui#CELL}px stub on an 800×600 headless viewport: the field is
 * {@code vw(10)} = 80px wide and {@code rem(5)} = 80px tall at the origin. {@code pad = min(PAD_X, w*0.25) = 10},
 * so text starts at x=10 and the visible width is 60px — exactly 6 characters. Lines are {@code CELL} = 10px
 * tall and the first tops out at {@code y = PAD_Y = 6}, so visual line <i>i</i> spans y ∈ [6+10i, 16+10i).
 */
class MultilineTest {

    /** A focused multiline field, 6 characters wide and 6 lines tall, optionally wrapping. */
    private static TextField field(HeadlessGui h, boolean wrap) {
        TextField f = new TextField(h.gui, "").multiline(true).wordWrap(wrap);
        f.node().width(Length.vw(10)).height(Length.rem(5));
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    /** Centre-of-line y for visual line {@code i}, for click tests. */
    private static float lineY(int i) {
        return TextMetrics.PAD_Y + i * HeadlessGui.CELL + HeadlessGui.CELL * 0.5f;
    }

    /**
     * Type {@code lines} into the focused field, pressing Enter between them.
     *
     * <p>A newline cannot be <em>typed</em>: {@code '\n'} is a control character, so it rides the key channel and
     * is deliberately filtered out of {@code CharTyped} (TextField.onCodePoint). Building multi-line text through
     * Enter is not a test workaround — it is the only path a real keyboard has.
     */
    private static void typeLines(HeadlessGui h, String... lines) {
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                h.tap(Key.ENTER);
            }
            if (!lines[i].isEmpty()) {
                h.type(lines[i]);
            }
        }
    }

    @Test
    void enterInsertsANewlineWhenMultiline() {
        try (HeadlessGui h = new HeadlessGui()) {
            boolean[] submitted = {false};
            TextField f = field(h, false).onSubmit(s -> submitted[0] = true);

            h.type("ab");
            h.tap(Key.ENTER);
            h.type("cd");

            assertEquals("ab\ncd", f.text());
            assertFalse(submitted[0], "a multiline field never submits on Enter");
        }
    }

    @Test
    void enterStillSubmitsASingleLineField() {
        try (HeadlessGui h = new HeadlessGui()) {
            String[] submitted = {null};
            TextField f = new TextField(h.gui, "");
            f.onSubmit(s -> submitted[0] = s);
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());

            h.type("ab");
            h.tap(Key.ENTER);

            assertEquals("ab", f.text(), "Enter inserts nothing in a single-line field");
            assertEquals("ab", submitted[0], "it submits instead");
        }
    }

    /**
     * A newline is an undo boundary. Without one it is just another single-character insert continuing the
     * typing run, so an entire multi-line burst collapses into a single Ctrl+Z — which is not what any editor
     * does, and is very annoying to discover after the fact.
     */
    @Test
    void aNewlineIsItsOwnUndoStep() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            h.type("ab");
            h.tap(Key.ENTER);
            h.type("cd");
            assertEquals("ab\ncd", f.text());

            h.chord(Key.Z, Key.LEFT_CONTROL);
            assertEquals("ab\n", f.text(), "the run after the newline undoes on its own");
            h.chord(Key.Z, Key.LEFT_CONTROL);
            assertEquals("ab", f.text(), "then the newline itself");
            h.chord(Key.Z, Key.LEFT_CONTROL);
            assertEquals("", f.text(), "then the run before it");
        }
    }

    /** Plain Home/End are visual-line; with Ctrl they address the whole document, as everywhere else. */
    @Test
    void ctrlHomeAndEndReachTheWholeDocument() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            typeLines(h, "aaa", "bbb");

            h.chord(Key.HOME, Key.LEFT_CONTROL);
            h.type("X");
            assertEquals("Xaaa\nbbb", f.text(), "Ctrl+Home goes to the document start, not the line start");

            h.chord(Key.END, Key.LEFT_CONTROL);
            h.type("Y");
            assertEquals("Xaaa\nbbbY", f.text(), "and Ctrl+End to the document end");
        }
    }

    /**
     * Tab indents inside a multiline editor and traverses focus everywhere else — and the field expresses that by
     * <em>claiming</em> the chord while focused, not by core deciding on its behalf. Shift+Tab is deliberately
     * left unclaimed, so there is always a way out of the field with the keyboard.
     */
    @Test
    void tabInsertsSoftTabsInAMultilineField() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            h.type("x");
            h.tap(Key.TAB);

            assertEquals("x    ", f.text(), "Tab inserts four spaces, not a tab character");
        }
    }

    @Test
    void shiftTabStillLeavesAMultilineField() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            h.type("x");
            h.chord(Key.TAB, Key.LEFT_SHIFT);

            assertEquals("x", f.text(), "Shift+Tab is unclaimed, so it traverses instead of indenting");
        }
    }

    @Test
    void tabDoesNotIndentASingleLineField() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "");
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());
            h.type("x");
            h.tap(Key.TAB);

            assertEquals("x", f.text(), "a single-line field claims nothing, so Tab traverses as always");
        }
    }

    @Test
    void upAndDownMoveBetweenLines() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            typeLines(h, "aaa", "bbb");   // caret at the end of line 2

            h.tap(Key.UP);
            h.type("X");

            assertEquals("aaaX\nbbb", f.text(), "Up from the end of line 2 lands at the end of line 1");

            h.tap(Key.DOWN);
            h.type("Y");
            assertEquals("aaaX\nbbbY", f.text(), "and Down comes back");
        }
    }

    /**
     * The sticky desired column: stepping Up through a short line and out the other side returns to the original
     * column rather than dragging left. This is the behaviour that only works because the widget owns the column
     * and the read-model answers "what offset is nearest this x on that line".
     */
    @Test
    void theDesiredColumnSticksAcrossAShortLine() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            typeLines(h, "long", "x", "long");   // caret at the end of line 3, column 4

            h.tap(Key.UP);             // line 2 is only 1 char — the caret clamps to column 1 visually...
            h.tap(Key.UP);             // ...but the remembered column is still 4
            h.type("Z");

            assertEquals("longZ\nx\nlong", f.text(),
                    "two Ups return to column 4, not to column 1 where the short line clamped it");
        }
    }

    /** A horizontal move re-anchors the column, so it is sticky across Up/Down and across nothing else. */
    @Test
    void aHorizontalMoveResetsTheDesiredColumn() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            typeLines(h, "long", "x", "long");

            h.tap(Key.UP);             // caret at the end of "x"
            h.tap(Key.LEFT);           // horizontal move: the column is now 0, not the remembered 4
            h.tap(Key.UP);
            h.type("Z");

            assertEquals("Zlong\nx\nlong", f.text(), "Up now lands at column 0 of line 1");
        }
    }

    @Test
    void wordWrapProducesSeveralVisualLinesAndRoundTripsThroughThem() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, true);
            h.type("abcdefghij");      // 10 chars in a 6-char-wide field

            TextMetrics m = f.node().layout().text();
            assertNotNull(m);
            assertEquals(2, m.lines().size(), "10 characters wrap onto two 6-character lines");
            assertEquals(0, m.lines().get(0).start());
            assertEquals(6, m.lines().get(1).start(), "the second visual line starts mid-string, with no newline");

            // offset -> point -> offset round-trips across the wrap boundary.
            for (int offset : new int[] {0, 5, 6, 8, 10}) {
                float x = m.caretX(offset);
                float y = m.caretTop(offset) + m.caretHeight(offset) * 0.5f;
                assertEquals(offset, m.offsetAt(x, y), "offset " + offset + " round-trips through its point");
            }
        }
    }

    @Test
    void homeAndEndAreVisualWithWrapOn() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, true);
            h.type("abcdefghij");      // wraps as [0,6) and [6,10); caret at 10, on line 2

            h.tap(Key.HOME);
            h.type("-");
            assertEquals("abcdef-ghij", f.text(), "Home goes to the start of the *visual* line, not the string");

            h.tap(Key.END);
            h.type("+");
            assertEquals("abcdef-ghij+", f.text(), "End goes to the end of the visual line");
        }
    }

    @Test
    void clickSelectsTheLineUnderThePointer() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            typeLines(h, "long", "x", "long");

            h.click(TextMetrics.PAD_X, lineY(1));   // start of visual line 2
            h.type("Z");

            assertEquals("long\nZx\nlong", f.text(), "the click lands on line 2, where the pointer was");
        }
    }

    @Test
    void selectionSpansLines() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            typeLines(h, "long", "x", "long");      // caret at the end

            h.chord(Key.UP, Key.LEFT_SHIFT);        // extend the selection up one visual line
            h.chord(Key.C, Key.LEFT_CONTROL);

            assertEquals("\nlong", h.gui.clipboard().get(),
                    "the selection runs from the end of line 2 across the newline to the end of line 3");
            assertEquals("long\nx\nlong", f.text(), "and copying changes nothing");
        }
    }

    /**
     * A wrapped label is as tall as the number of lines it wraps onto. Text height is <em>height-for-width</em>,
     * not an intrinsic: {@code TextMeasurer.intrinsic(VERTICAL)} can only report one line, so the layout asks
     * {@code lineSpans} — the same seam that decides where the lines actually break — for the count.
     *
     * <p>Without this a wrapped label reserved one line of space: neighbours overlapped it and a scroll container
     * stopped short of its last line, both of which the demo showed.
     */
    @Test
    void aWrappedLabelIsAsTallAsItsWrappedLineCount() {
        try (HeadlessGui h = new HeadlessGui()) {
            // 80px wide => 60px of text => 6 monospace characters per line.
            dev.vexelray.gui.core.Node oneLine = h.gui.text("abc").width(Length.vw(10));
            dev.vexelray.gui.core.Node twoLines = h.gui.text("abcdefghij").width(Length.vw(10));
            dev.vexelray.gui.core.Node fourLines = h.gui.text("abcdefghijklmnopqrstuvw").width(Length.vw(10));
            h.gui.root().children(oneLine, twoLines, fourLines);
            h.frame();

            assertEquals(HeadlessGui.CELL, oneLine.layout().rect().h(), 0.5f, "3 chars fit on one line");
            assertEquals(2 * HeadlessGui.CELL, twoLines.layout().rect().h(), 0.5f, "10 chars wrap onto two");
            assertEquals(4 * HeadlessGui.CELL, fourLines.layout().rect().h(), 0.5f, "23 chars wrap onto four");
        }
    }

    /**
     * The invariant behind the demo's overlap: a label's reserved box must contain every line it draws. Asserting
     * that stacked boxes merely don't overlap is too weak — the boxes never overlapped, the <em>text</em> spilled
     * out of a box that was one line tall.
     */
    @Test
    void aLabelsBoxContainsEveryLineItDraws() {
        try (HeadlessGui h = new HeadlessGui()) {
            dev.vexelray.gui.core.Node first = h.gui.text("abcdefghij").width(Length.vw(10));
            dev.vexelray.gui.core.Node second = h.gui.text("klmnopqrst").width(Length.vw(10));
            h.gui.root().children(first, second);
            h.frame();

            for (var node : java.util.List.of(first, second)) {
                var box = node.layout().rect();
                TextMetrics m = node.layout().text();
                assertNotNull(m);
                var last = m.lines().get(m.lines().size() - 1);
                assertTrue(last.bottom() <= box.y() + box.h() + 0.5f,
                        "the last drawn line ends at y=" + last.bottom()
                                + " but the box ends at y=" + (box.y() + box.h()));
                assertTrue(m.lines().get(0).top() >= box.y() - 0.5f,
                        "and the first drawn line starts inside the box");
            }
        }
    }

    /**
     * The scrollbar/wrap circularity: reserving a vertical scrollbar narrows the content width, which makes
     * wrapped children taller, which is more content to scroll. {@code contentH} must describe the children at
     * the width they are actually laid out at — otherwise the scroll range stops short of the last line.
     *
     * <p>Harmless before text height depended on width; a real bug after it.
     */
    @Test
    void aScrollContainerMeasuresItsChildrenAtTheWidthTheyAreLaidOutAt() {
        try (HeadlessGui h = new HeadlessGui()) {
            dev.vexelray.gui.core.Node col = h.gui.column()
                    .width(Length.vw(12.5f))     // 100px
                    .height(Length.rem(2.5f));   // 40px — three wrapped labels will overflow it
            var labels = new java.util.ArrayList<dev.vexelray.gui.core.Node>();
            for (int i = 0; i < 3; i++) {
                labels.add(h.gui.text("abcdefghijklmnop"));   // 16 chars
            }
            col.children(labels.toArray(new dev.vexelray.gui.core.Node[0]));
            h.gui.root().children(col);
            h.frame();

            var box = col.layout();
            assertTrue(box.overflowY(), "precondition: the column overflows and reserves a vertical scrollbar");

            float drawn = 0f;
            for (var child : labels) {
                drawn += child.layout().rect().h();
            }
            assertTrue(box.contentH() >= drawn - 0.5f,
                    "contentH=" + box.contentH() + " must cover the children's actual laid-out height " + drawn
                            + " — the scroll range stops short otherwise");
        }
    }

    @Test
    void aTallDocumentScrollsVerticallyToFollowTheCaret() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false);
            // 10 lines in a field showing floor(68 / 10) = 6 of them, so the view must have scrolled.
            typeLines(h, "l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10");

            TextMetrics m = f.node().layout().text();
            assertNotNull(m);
            assertEquals(10, m.lines().size());

            int end = f.text().length();
            float caretTop = m.caretTop(end);
            assertTrue(caretTop >= 0f && caretTop + m.caretHeight(end) <= 80f,
                    "the caret's line must be inside the 80px-tall field, but its top is " + caretTop);
            assertTrue(m.caretTop(0) < 0f,
                    "and the first line has scrolled up out of view, at y=" + m.caretTop(0));
        }
    }
}
