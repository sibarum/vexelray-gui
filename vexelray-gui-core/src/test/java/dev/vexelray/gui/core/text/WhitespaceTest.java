package dev.vexelray.gui.core.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
