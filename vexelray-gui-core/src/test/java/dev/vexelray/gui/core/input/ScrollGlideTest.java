package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wheel as motion: notches add up, the glide never overshoots or stops short, and it yields to anyone else. */
class ScrollGlideTest {

    private static final long MS = 1_000_000L;

    private static RetainedNode scroller() {
        RetainedNode n = new RetainedNode(1);
        n.viewH = 100f;
        n.contentH = 1000f;
        return n;
    }

    @Test
    @DisplayName("with no half-life, a notch moves the view at once — the default, and the reduced-motion setting")
    void instantByDefault() {
        ScrollGlide glide = new ScrollGlide();
        RetainedNode n = scroller();

        assertTrue(glide.by(n, 0f, 48f, 0L));
        assertEquals(48f, n.scrollY);
        assertFalse(glide.step(16 * MS, x -> { }), "nothing left to glide");
    }

    @Test
    @DisplayName("a glide closes half the distance per half-life, moves every frame, and lands exactly")
    void halfPerHalfLife() {
        ScrollGlide glide = new ScrollGlide();
        glide.halfLife(40 * MS);
        RetainedNode n = scroller();

        glide.by(n, 0f, 100f, 0L);
        List<Float> seen = new ArrayList<>();
        for (long t = 0; t <= 1000 * MS; t += 16 * MS) {
            glide.step(t, x -> { });
            seen.add(n.scrollY);
        }
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i) >= seen.get(i - 1), "never backs up: " + seen);
            assertTrue(seen.get(i) <= 100f, "never overshoots: " + seen);
        }
        assertTrue(seen.get(0) > 0f, "the notch moves the view in the frame it arrives in");
        assertEquals(100f, n.scrollY, "and arrives on the target, not a hair short of it");
    }

    @Test
    @DisplayName("notches add up: a second one moves the target, and the view simply has further to go")
    void notchesAccumulate() {
        ScrollGlide glide = new ScrollGlide();
        glide.halfLife(40 * MS);
        RetainedNode n = scroller();

        glide.by(n, 0f, 48f, 0L);
        glide.step(16 * MS, x -> { });
        float mid = n.scrollY;
        glide.by(n, 0f, 48f, 16 * MS);
        for (long t = 32 * MS; t <= 1000 * MS; t += 16 * MS) {
            glide.step(t, x -> { });
        }

        assertTrue(mid > 0f && mid < 48f);
        assertEquals(96f, n.scrollY, "two notches, twice the distance, from wherever the first had got to");
    }

    @Test
    @DisplayName("a glide clamps to what the content allows")
    void clamped() {
        ScrollGlide glide = new ScrollGlide();
        glide.halfLife(40 * MS);
        RetainedNode n = scroller();

        glide.by(n, 0f, 5000f, 0L);
        for (long t = 0; t <= 2000 * MS; t += 16 * MS) {
            glide.step(t, x -> { });
        }
        assertEquals(900f, n.scrollY);
    }

    @Test
    @DisplayName("anyone else moving the view takes it: the glide gives up rather than pulling against them")
    void yieldsToOtherWriters() {
        ScrollGlide glide = new ScrollGlide();
        glide.halfLife(40 * MS);
        RetainedNode n = scroller();

        glide.by(n, 0f, 300f, 0L);
        glide.step(16 * MS, x -> { });
        n.scrollY = 10f;   // the thumb, a reveal, an application's scrollTo
        glide.step(32 * MS, x -> { });

        assertEquals(10f, n.scrollY);
        assertFalse(glide.step(48 * MS, x -> { }), "and is gone");
    }
}
