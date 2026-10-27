package dev.vexelray.gui.scroll;

import dev.vexelray.gui.core.ScrollRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the thumb is: what is drawn and what a press is judged against are these numbers. */
class ThumbTest {

    private static final float TRACK = 210f;
    private static final float T = 10f;          // thickness; the inset is 2, so the inner track is 206

    @Test
    void nothingToScrollIsATrackWithNoThumb() {
        Thumb t = Thumb.of(new ScrollRange(100f, 100f, 0f), TRACK, T);
        assertEquals(0f, t.length());
        assertFalse(t.holds(50f), "no thumb, nothing to take hold of");
    }

    @Test
    void theThumbIsTheVisibleFractionOfTheTrack() {
        Thumb t = Thumb.of(new ScrollRange(412f, 103f, 0f), TRACK, T);   // a quarter visible
        assertEquals(206f / 4f, t.length(), 0.01f);
        assertEquals(2f, t.start(), 0.01f, "at the top, inset by a fifth of the thickness");
        assertEquals(206f - 206f / 4f, t.travel(), 0.01f);
    }

    @Test
    void neverShorterThanTwoThicknesses() {
        Thumb t = Thumb.of(new ScrollRange(100_000f, 100f, 0f), TRACK, T);
        assertEquals(2f * T, t.length(), 0.01f, "still something to grab over very long content");
    }

    @Test
    void theOffsetPlacesItAndAPlaceGivesTheOffsetBack() {
        ScrollRange range = new ScrollRange(412f, 103f, 309f);   // at the end
        Thumb t = Thumb.of(range, TRACK, T);
        assertEquals(2f + t.travel(), t.start(), 0.01f, "at the end of its travel");
        assertTrue(t.holds(t.start() + 1f));
        assertEquals(309f, t.offsetFor(t.start(), range), 0.01f, "the inverse");
        assertEquals(0f, t.offsetFor(-50f, range), 0.01f, "clamped at the start");
        assertEquals(309f, t.offsetFor(9999f, range), 0.01f, "and at the end");
    }
}
