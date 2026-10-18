package dev.vexelray.gui.core.text;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhitespaceTest {

    /** The marked offsets of {@code s}, drawn as a string: {@code ·} where a mark is, the text elsewhere. */
    private static String marked(Whitespace mode, String s, int from, int to) {
        List<Integer> at = new ArrayList<>();
        mode.mark(s, from, to, at::add);
        StringBuilder out = new StringBuilder(s);
        for (int i : at) {
            out.setCharAt(i, '·');
        }
        return out.toString();
    }

    private static String marked(Whitespace mode, String s) {
        return marked(mode, s, 0, s.length());
    }

    @Test
    void boundaryLeavesTheSingleSpaceBetweenWords() {
        assertEquals("··int x = 1;··", marked(Whitespace.BOUNDARY, "  int x = 1;  "));
    }

    @Test
    void boundaryMarksARunInTheMiddle() {
        assertEquals("a··b c", marked(Whitespace.BOUNDARY, "a  b c"));
    }

    @Test
    void aSingleLeadingOrTrailingBlankIsMarked() {
        assertEquals("·a b·", marked(Whitespace.BOUNDARY, " a b "));
    }

    @Test
    void eachHardLineIsJudgedOnItsOwn() {
        assertEquals("a·\n·b c\n·\nd", marked(Whitespace.BOUNDARY, "a \n b c\n \nd"));
    }

    @Test
    void aWrappedRowIsJudgedAgainstItsHardLine() {
        String s = "ab cd ef  ";
        // The row "cd " begins after a blank but not at the line's start, so its blank is between words.
        assertEquals("ab cd ef  ", marked(Whitespace.BOUNDARY, s, 3, 6));
        // The row that holds the trailing blanks marks them, though the line's ink is in an earlier row.
        assertEquals("ab cd ef··", marked(Whitespace.BOUNDARY, s, 6, 10));
        // A row of indentation that started in the row before is still leading.
        assertEquals("  ··x", marked(Whitespace.BOUNDARY, "    x", 2, 4));
    }

    @Test
    void aRowWhoseLineGoesOnHasNoTrailing() {
        assertEquals("a b c", marked(Whitespace.BOUNDARY, "a b c", 0, 2));
    }

    @Test
    void trailingIsOnlyTheEnd() {
        assertEquals("  a  b··\n c·", marked(Whitespace.TRAILING, "  a  b  \n c "));
    }

    @Test
    void allAndNone() {
        assertEquals("·a·b·", marked(Whitespace.ALL, " a b "));
        assertEquals(" a b ", marked(Whitespace.NONE, " a b "));
    }

    /** U+00A0, U+2007 and U+202F are not {@link Character#isWhitespace}, and are the blanks most worth showing. */
    @Test
    void noBreakSpacesAreWhitespace() {
        assertEquals("a·b·c·d", marked(Whitespace.ALL, "a b c d"));
        assertEquals("x = 1;·", marked(Whitespace.TRAILING, "x = 1; "));
    }

    /** Trailing on an earlier hard line of the range is judged up to its own newline, not past it. */
    @Test
    void anEarlierHardLinesTrailingStopsAtItsNewline() {
        assertEquals("a··\nb c\nd··\ne f", marked(Whitespace.TRAILING, "a  \nb c\nd  \ne f"));
        assertEquals("a b\n··\nc", marked(Whitespace.TRAILING, "a b\n  \nc"));
    }

    // --- wrapped, through the real breaker and TextGeometry, rather than hand-picked ranges ---

    /** Every character {@value #ADVANCE} px wide; a 100 px view therefore wraps at ten characters. */
    private static final float ADVANCE = 10f;

    private static final TextMeasurer FIXED_PITCH = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode n, Axis axis, float textSizePx) {
            String s = n.textString();
            return axis == Axis.VERTICAL ? 16f : (s == null ? 0f : s.length() * ADVANCE);
        }

        @Override
        public float[] caretAdvances(String text, float textSizePx) {
            float[] adv = new float[text.length() + 1];
            for (int i = 0; i <= text.length(); i++) {
                adv[i] = i * ADVANCE;
            }
            return adv;
        }

        @Override
        public List<TextLayout.LineSpan> lineSpans(String text, float wrapWidth, float textSizePx) {
            return TextLayout.breakLineSpans(text, (cp, index) -> ADVANCE, wrapWidth,
                    wrapWidth > 0f ? TextLayout.WrapMode.WORD : TextLayout.WrapMode.NONE);
        }
    };

    /** A read-only text node (so it wraps) 100 px wide, its geometry resolved the way the compute phase does. */
    private static TextMetrics wrapped(String text) {
        RetainedNode n = new RetainedNode(1L);
        n.set(PropKey.TEXT, text);
        n.w = 100f;
        n.h = 200f;
        n.textSizePx = 16f;
        n.viewW = 100f;
        n.viewH = 200f;
        TextGeometry.resolve(n, FIXED_PITCH);
        assertTrue(n.textMetrics.lines().size() > 1, "the text must actually wrap for this test to mean anything");
        return n.textMetrics;
    }

    /** What {@code TreeRenderer.drawMarks} marks: each visual row's own range, row by row. */
    private static List<Integer> markedByRows(Whitespace mode, String s, TextMetrics m) {
        List<Integer> at = new ArrayList<>();
        for (TextMetrics.VisualLine line : m.lines()) {
            mode.mark(s, line.start(), line.end(), at::add);
        }
        return at;
    }

    @Test
    void wrappedRowsAreJudgedAgainstTheirHardLine() {
        // Indentation on the first row, a run in the middle of a row, a single space between words, and a
        // continuation row that begins after the wrap without being "leading".
        String s = "  ab  cd e fghij klm";
        TextMetrics m = wrapped(s);
        List<Integer> at = markedByRows(Whitespace.BOUNDARY, s, m);
        assertTrue(at.containsAll(List.of(0, 1)), "indentation is leading: " + at + " over " + m.lines());
        assertTrue(at.containsAll(List.of(4, 5)), "a run of two is marked: " + at);
        assertFalse(at.contains(8), "the single blank between two words is not: " + at);
        TextMetrics.VisualLine last = m.lines().get(m.lines().size() - 1);
        assertFalse(at.stream().anyMatch(i -> i >= last.start()),
                "a continuation row's blanks between words are not leading: " + at + " over " + m.lines());
    }

    /**
     * Row by row must mark what the whole hard line marks. It does not: the breaker drops the blanks at a wrap
     * point out of both rows, so they are never dotted — the very blanks the feature exists to show.
     */
    @Disabled("docs/plans/todo.md §7.1, blanks at a soft wrap: undecided whether drawMarks or the breaker owns the gap")
    @Test
    void blanksAtASoftWrapAreMarked() {
        String s = "abcdefgh      ijk    ";
        TextMetrics m = wrapped(s);
        List<Integer> whole = new ArrayList<>();
        Whitespace.BOUNDARY.mark(s, 0, s.length(), whole::add);
        assertEquals(whole, markedByRows(Whitespace.BOUNDARY, s, m), "rows " + m.lines());
    }
}
