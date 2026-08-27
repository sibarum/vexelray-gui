package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-shot feedback on a node, and the one property that has to hold whatever the cue paints: <b>the box comes
 * back clean</b>. Everything here is a way to arrive there off the happy path — a second cue mid-cue, a ramp
 * that finishes long after it was superseded, a node with no layout yet.
 *
 * <p>No clock, because {@link Cues} names none: the ramp is a callback, so a test is just another thing that can
 * decline to call it. And no GUI at all in the cue-shape tests — a {@link Cue} is a pure function of progress and
 * a box, which is most of the point of it being one.
 */
class CueTest {

    private static final Color ACCENT = Color.rgba(0.3f, 0.7f, 1f, 0.8f);

    /** A ramp the test drives by hand: it holds progress and completion until released. */
    private static final class Manual implements Ramp {
        DoubleConsumer progress;
        Runnable done;
        int started;

        @Override
        public void run(DoubleConsumer progress, Runnable done) {
            this.progress = progress;
            this.done = done;
            this.started++;
        }

        void to(double t) {
            progress.accept(t);
        }

        void finish() {
            progress.accept(1d);
            done.run();
        }
    }

    private static Node field(HeadlessGui h) {
        Node n = h.gui.text("hello").width(Length.dp(200)).height(Length.dp(24));
        h.gui.root().append(n);
        h.frame();
        return n;
    }

    private static Picture overlayOf(HeadlessGui h, Node n) {
        return h.retained(n).overlay();
    }

    // --- the cue as a value ---------------------------------------------------------------------------------

    /**
     * A cue is a pure function, so this needs no {@code Gui}, no node and no frame — which is the property that
     * lets an application write its own and know what it draws before ever running the app.
     */
    @Test
    void aCueIsAPictureOfProgressAndABox() {
        Cue.Box box = new Cue.Box(200f, 24f, 4f, 4f);
        Cue scanline = Cue.scanline(ACCENT);

        Picture early = scanline.at(0.1d, box);
        Picture late = scanline.at(0.9d, box);

        assertFalse(early.isEmpty());
        assertFalse(late.isEmpty());
        assertTrue(headY(late) > headY(early), "the band travels down the box");
    }

    /**
     * <b>Every frame of the duration is a visible frame.</b> The regression this exists for: the band used to fly
     * in from above the box, which spent a third of the cue with nothing on screen — so a 260ms cue showed 150ms
     * of sweep, under the threshold at which anything registers as having happened. A cue you can miss is not a
     * cue, and the failure is invisible to a test that only checks that marks were produced.
     */
    @Test
    void theScanlineIsInTheBoxForTheWholeDuration() {
        Cue.Box box = new Cue.Box(200f, 24f, 0f, 0f);
        Cue scanline = Cue.scanline(ACCENT);

        assertEquals(0f, headY(scanline.at(0d, box)), 0.01f, "the line is on the top edge at the very start");
        // Up to 0.9, because the last sliver of the duration is the line leaving through the bottom — which is
        // the sweep ending, not a gap in it.
        for (double t = 0d; t <= 0.9d; t += 0.05d) {
            float y = headY(scanline.at(t, box));
            assertTrue(y >= 0f && y < box.h(), "the bright core is in the box at t=" + t + " (was " + y + ")");
        }
        assertTrue(headY(scanline.at(1d, box)) >= box.h(), "and wholly past the bottom edge at the end");
    }

    /** Reversed is the same cue mirrored — the reason direction is not a parameter on every factory. */
    @Test
    void reversedRunsTheSameCueBackwards() {
        Cue.Box box = new Cue.Box(200f, 24f, 0f, 0f);
        Cue up = Cue.scanline(ACCENT).reversed();

        assertTrue(headY(up.at(0.9d, box)) < headY(up.at(0.1d, box)));
    }

    /** A wash rises and falls and is nothing at both ends, so the cue ends by being gone rather than by cutting. */
    @Test
    void aWashIsNothingAtBothEnds() {
        Cue.Box box = new Cue.Box(200f, 24f, 4f, 4f);
        Cue wash = Cue.wash(ACCENT);

        assertTrue(wash.at(0d, box).isEmpty());
        assertTrue(wash.at(1d, box).isEmpty());
        assertFalse(wash.at(0.5d, box).isEmpty());
    }

    /** A ring hugs the box it was handed, corners included — so a rounded field does not briefly go square. */
    @Test
    void aRingTakesTheBoxesOwnCorners() {
        Cue.Box box = new Cue.Box(200f, 24f, 6f, 2f);
        Picture p = Cue.ring(ACCENT).at(0.25d, box);

        Picture.Outline ring = (Picture.Outline) p.marks().get(0);
        assertEquals(0f, ring.x());
        assertEquals(200f, ring.w());
        assertEquals(6f, ring.radiusTop());
        assertEquals(2f, ring.radiusBottom());
    }

    /** Composition rather than a factory per combination: an error is a ring and a wash, said in one call. */
    @Test
    void cuesCompose() {
        Cue.Box box = new Cue.Box(200f, 24f, 0f, 0f);
        Cue both = Cue.ring(ACCENT).with(Cue.wash(ACCENT));

        int ring = Cue.ring(ACCENT).at(0.25d, box).marks().size();
        int wash = Cue.wash(ACCENT).at(0.25d, box).marks().size();
        assertEquals(ring + wash, both.at(0.25d, box).marks().size());
    }

    /** A box with no area is not a shape to decorate, and no stock cue may invent marks for one. */
    @Test
    void nothingPaintsIntoAnEmptyBox() {
        for (Cue cue : List.of(Cue.scanline(ACCENT), Cue.wash(ACCENT), Cue.ring(ACCENT))) {
            assertTrue(cue.at(0.5d, Cue.Box.NONE).isEmpty());
        }
    }

    // --- playing one ----------------------------------------------------------------------------------------

    /** The point of the whole thing: paint while it runs, and take it back off when it is over. */
    @Test
    void aCuePaintsForItsDurationAndClearsAfterwards() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node n = field(h);
            Manual ramp = new Manual();
            Cues cues = new Cues(ramp);

            cues.play(n, Cue.scanline(ACCENT));
            h.frame();
            assertNotNull(overlayOf(h, n), "the start is delivered before any time passes");

            ramp.to(0.5d);
            h.frame();
            assertNotNull(overlayOf(h, n));
            assertTrue(cues.isPlaying(n));

            ramp.finish();
            h.frame();
            assertNull(overlayOf(h, n), "the box comes back clean");
            assertEquals(0, cues.active(), "and the cue is not still tracked");
        }
    }

    /**
     * The interruption case, which is the one a user produces by being quick: submitting twice in under a
     * duration must not leave the second cue cleared halfway through by the first one's settle.
     */
    @Test
    void asecondCueSupersedesTheFirstAndOnlyTheWinnerClears() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node n = field(h);
            Manual first = new Manual();
            Manual second = new Manual();
            Cues cues = new Cues(first);

            cues.play(n, Cue.scanline(ACCENT));
            first.to(0.4d);
            h.frame();
            Picture midFirst = overlayOf(h, n);
            assertNotNull(midFirst);

            cues.play(n, Cue.wash(ACCENT), second);
            second.to(0.5d);
            h.frame();
            Picture midSecond = overlayOf(h, n);
            assertNotNull(midSecond);

            // The superseded ramp keeps running: neither its samples nor its settle may touch the node again.
            first.to(0.9d);
            h.frame();
            assertSame(midSecond, overlayOf(h, n), "a losing sample paints nothing");

            first.finish();
            h.frame();
            assertNotNull(overlayOf(h, n), "a losing settle clears nothing");
            assertTrue(cues.isPlaying(n));

            second.finish();
            h.frame();
            assertNull(overlayOf(h, n));
            assertEquals(0, cues.active());
        }
    }

    /** Two nodes cued at once are two cues: supersession is per node, not global. */
    @Test
    void cuesOnDifferentNodesDoNotInterfere() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node a = field(h);
            Node b = field(h);
            Manual ra = new Manual();
            Manual rb = new Manual();
            Cues cues = new Cues(ra);

            cues.play(a, Cue.scanline(ACCENT));
            cues.play(b, Cue.wash(ACCENT), rb);
            ra.to(0.5d);
            rb.to(0.5d);
            h.frame();
            assertEquals(2, cues.active());

            ra.finish();
            h.frame();
            assertNull(overlayOf(h, a));
            assertNotNull(overlayOf(h, b));
        }
    }

    /** Stopping clears now; the abandoned ramp finishing later finds nothing of its own to undo. */
    @Test
    void stopClearsImmediatelyAndTheLateRampIsHarmless() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node n = field(h);
            Manual ramp = new Manual();
            Cues cues = new Cues(ramp);

            cues.play(n, Cue.scanline(ACCENT));
            ramp.to(0.5d);
            h.frame();
            assertNotNull(overlayOf(h, n));

            cues.stop(n);
            h.frame();
            assertNull(overlayOf(h, n));
            assertFalse(cues.isPlaying(n));

            ramp.finish();
            h.frame();
            assertNull(overlayOf(h, n));
            assertEquals(0, cues.active());
        }
    }

    /**
     * Reduced motion is not a fast cue — a cue exists only in the middle, so there is no end state to snap to and
     * the honest collapse is not playing it at all.
     */
    @Test
    void noneNeverPaints() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node n = field(h);
            Cues cues = Cues.none();

            cues.play(n, Cue.scanline(ACCENT));
            h.frame();

            assertFalse(cues.enabled());
            assertNull(overlayOf(h, n));
            assertEquals(0, cues.active());
        }
    }

    /** A node with no layout yet has no shape to decorate; cueing it must not paint or leave anything behind. */
    @Test
    void aNodeWithNoLayoutIsNotPainted() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node n = h.gui.text("not in the tree");     // never appended, so never laid out
            Manual ramp = new Manual();
            Cues cues = new Cues(ramp);

            cues.play(n, Cue.scanline(ACCENT));
            ramp.finish();
            h.frame();

            assertEquals(0, cues.active());
        }
    }

    /** An overlay decorates the node it is on and nothing else — in particular it is not inherited by children. */
    @Test
    void anOverlayBelongsToItsOwnNode() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node parent = h.gui.box().width(Length.dp(200)).height(Length.dp(60));
            Node child = h.gui.text("inside").width(Length.FILL).height(Length.dp(24));
            h.gui.root().append(parent);
            parent.append(child);
            h.frame();

            Manual ramp = new Manual();
            new Cues(ramp).play(parent, Cue.wash(ACCENT));
            ramp.to(0.5d);
            h.frame();

            assertNotNull(overlayOf(h, parent));
            assertNull(overlayOf(h, child));
        }
    }

    /** The y of the scanline's bright core — the mark the sweep is actually read by. */
    private static float headY(Picture p) {
        List<Picture.Mark> marks = new ArrayList<>(p.marks());
        Picture.Fill core = (Picture.Fill) marks.get(marks.size() - 1);
        return (float) core.y();
    }
}
