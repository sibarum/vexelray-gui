package dev.vexelray.gui.core.text;

/**
 * Where a one-line label that does not fit its box is cut, an ellipsis standing for what was taken out.
 *
 * <p>Without one, a label kept to a line ({@code wordWrap(false)}) is cut at its box's edge, through whatever glyph is
 * there. With one, the layout measures the text against the room it settled on and draws a shorter string that fits,
 * cut between characters. Only what is drawn is cut: the node's text, which is what a screen reader, an automation
 * script and a test read, stays whole.
 *
 * <p>{@link #MIDDLE} is the one for names, whose start and end both say something: a file's name and its extension, a
 * path's root and its leaf.
 */
public enum Ellipsis {

    /** Keep the start: {@code A long caption th…}. */
    END,
    /** Keep the start and the end, half the room each: {@code IMG_2024…harbour.jpg}. */
    MIDDLE;

    /** What stands for the characters taken out. */
    public static final String MARK = "…";

    /**
     * {@code text} cut to fit {@code room} px, or {@code text} itself when it fits.
     *
     * @param adv  the advance of {@code text} at each character boundary: {@code text.length() + 1} long, from 0
     * @param mark the advance of {@link #MARK}
     */
    public String cut(String text, float[] adv, float mark, float room) {
        int n = text.length();
        if (adv[n] <= room) {
            return text;
        }
        float left = room - mark;
        if (left <= 0f) {
            return MARK;
        }
        if (this == END) {
            return text.substring(0, whole(text, last(adv, left), false)) + MARK;
        }
        int head = whole(text, last(adv, left / 2f), false);
        // The tail takes what the head left, which is at least half: a narrow glyph at the cut is not wasted.
        float tailRoom = left - adv[head];
        int tail = n;
        while (tail > head && adv[n] - adv[tail - 1] <= tailRoom) {
            tail--;
        }
        return text.substring(0, head) + MARK + text.substring(whole(text, tail, true));
    }

    /** The last boundary whose advance is within {@code room}. */
    private static int last(float[] adv, float room) {
        int i = 0;
        while (i + 1 < adv.length && adv[i + 1] <= room) {
            i++;
        }
        return i;
    }

    /** {@code i}, moved off the middle of a surrogate pair: back for a head's end, on for a tail's start. */
    private static int whole(String s, int i, boolean on) {
        if (i > 0 && i < s.length() && Character.isLowSurrogate(s.charAt(i))
                && Character.isHighSurrogate(s.charAt(i - 1))) {
            return on ? i + 1 : i - 1;
        }
        return i;
    }
}
