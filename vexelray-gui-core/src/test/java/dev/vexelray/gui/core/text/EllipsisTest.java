package dev.vexelray.gui.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a label is cut, on a fixed pitch: every character, and the mark, 10 px. */
class EllipsisTest {

    private static final float PITCH = 10f;

    private static float[] adv(String s) {
        float[] a = new float[s.length() + 1];
        for (int i = 0; i <= s.length(); i++) {
            a[i] = i * PITCH;
        }
        return a;
    }

    private static String cut(Ellipsis e, String s, float room) {
        return e.cut(s, adv(s), PITCH, room);
    }

    @Test
    void textThatFitsIsNotCut() {
        assertEquals("harbour.jpg", cut(Ellipsis.MIDDLE, "harbour.jpg", 110f));
        assertEquals("harbour.jpg", cut(Ellipsis.END, "harbour.jpg", 110f));
    }

    @Test
    void theEndKeepsTheStart() {
        assertEquals("harbo…", cut(Ellipsis.END, "harbour.jpg", 60f));
    }

    /** Half the room left after the mark each side; an odd character goes to the end. */
    @Test
    void theMiddleKeepsTheStartAndTheEnd() {
        assertEquals("har….jpg", cut(Ellipsis.MIDDLE, "harbour.jpg", 80f));
        assertEquals("ha…jpg", cut(Ellipsis.MIDDLE, "harbour.jpg", 60f));
    }

    @Test
    void noRoomForAnyCharacterIsTheMarkAlone() {
        assertEquals("…", cut(Ellipsis.MIDDLE, "harbour.jpg", 5f));
        assertEquals("…", cut(Ellipsis.END, "harbour.jpg", 10f));
    }

    /** A character outside the BMP is two chars and one glyph: never cut between them. */
    @Test
    void aSurrogatePairIsNeverSplit() {
        String s = "ab📷cd";   // a b, a camera, c d: six chars
        assertEquals("ab…", cut(Ellipsis.END, s, 40f));
        String t = "abc📷d";   // the camera where the end's cut falls
        assertEquals("a…d", cut(Ellipsis.MIDDLE, t, 45f));
    }
}
