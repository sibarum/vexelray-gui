package dev.vexelray.gui.core.text;

import java.util.function.IntConsumer;

/**
 * Which whitespace a text node shows, as a faint dot in the character's own cell. The marks are drawn over the
 * text and measure nothing: turning them on or off moves no glyph and reflows no line.
 *
 * <p>The modes are the ones code editors converged on. {@link #BOUNDARY} is the useful one: indentation, what
 * trails a line, and runs of more than one blank are where whitespace means something or hides a mistake, and the
 * single space between two words is neither — dotting every one of those turns prose into braille.
 *
 * <p>Whitespace is everything {@link Character#isWhitespace} or {@link Character#isSpaceChar} says it is but the
 * newline, which is not a cell and so has nowhere to put a dot. The second is for the no-break spaces (U+00A0,
 * U+2007, U+202F), which the first excludes and which are the classic invisible character pasted into source.
 */
public enum Whitespace {

    /** No marks. */
    NONE {
        @Override
        boolean shows(String s, int i, boolean leading, boolean trailing) {
            return false;
        }
    },
    /** Leading and trailing whitespace, and any run of two or more; not the single blank between two words. */
    BOUNDARY {
        @Override
        boolean shows(String s, int i, boolean leading, boolean trailing) {
            return leading || trailing
                    || (i > 0 && blank(s.charAt(i - 1))) || (i + 1 < s.length() && blank(s.charAt(i + 1)));
        }
    },
    /** Only what trails the last visible character of a line. */
    TRAILING {
        @Override
        boolean shows(String s, int i, boolean leading, boolean trailing) {
            return trailing;
        }
    },
    /** Every whitespace character. */
    ALL {
        @Override
        boolean shows(String s, int i, boolean leading, boolean trailing) {
            return true;
        }
    };

    /**
     * Whether this mode marks the blank at {@code i} of {@code s}, which is {@code leading} when only blanks precede
     * it on its hard line and {@code trailing} when only blanks follow it there.
     */
    abstract boolean shows(String s, int i, boolean leading, boolean trailing);

    /**
     * Hand {@code at} the offset of every character in {@code [from, to)} of {@code s} this mode marks, in order.
     * Leading and trailing are judged against the <em>hard</em> line — the range between two newlines — so a
     * wrapped row's first blank is not leading just because the wrap put it there.
     *
     * <p>Bounded by the range and the blank runs that touch it, never by the length of the hard line it is in: a
     * renderer calls this per visible row, every frame, and a row in the middle of a long line must not cost the
     * whole line.
     */
    public void mark(String s, int from, int to, IntConsumer at) {
        if (this == NONE || from >= to) {
            return;
        }
        from = Math.max(0, from);
        to = Math.min(s.length(), to);
        // Leading continues into this range when everything before it on its hard line is blank.
        int back = from - 1;
        while (back >= 0 && blank(s.charAt(back))) {
            back--;
        }
        boolean leading = back < 0 || s.charAt(back) == '\n';
        // Trailing starts after the range's last ink, but only when everything after the range on its hard line
        // is blank too; otherwise nothing in the range trails.
        int ahead = to;
        while (ahead < s.length() && blank(s.charAt(ahead))) {
            ahead++;
        }
        int lastInk = from - 1;
        if (ahead >= s.length() || s.charAt(ahead) == '\n') {
            for (int i = to - 1; i >= from; i--) {
                char c = s.charAt(i);
                if (!blank(c) && c != '\n') {
                    lastInk = i;
                    break;
                }
            }
        } else {
            lastInk = to;
        }
        // The last ink of the hard line the loop is on, found once as the loop enters that line rather than once
        // per blank, and only within the range: past `to` is already decided by lastInk.
        int lineInk = lastInkOfLine(s, from, to, lastInk);
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                // A range can hold more than one hard line; each starts leading again, and the trailing judged
                // above is only for the last of them, so an earlier one's is found by looking ahead from here.
                leading = true;
                lineInk = lastInkOfLine(s, i + 1, to, lastInk);
                continue;
            }
            if (!blank(c)) {
                leading = false;
                continue;
            }
            if (shows(s, i, leading, i > lineInk)) {
                at.accept(i);
            }
        }
    }

    /**
     * The last ink of the hard line starting at {@code start}, searching only {@code [start, to)}. A newline inside
     * the range ends the line, and its last ink is the answer ({@code start - 1} when it has none); a line that
     * runs to the range's end is the range's last, which {@code lastInk} already judged against what follows.
     */
    private static int lastInkOfLine(String s, int start, int to, int lastInk) {
        int ink = start - 1;
        for (int j = start; j < to; j++) {
            char c = s.charAt(j);
            if (c == '\n') {
                return ink;
            }
            if (!blank(c)) {
                ink = j;
            }
        }
        return lastInk;
    }

    /** Whitespace that occupies a cell: everything but the newline. */
    static boolean blank(char c) {
        return c != '\n' && (Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
}
