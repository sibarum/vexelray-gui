package dev.vexelray.gui.krono;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.PaintMotion;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.anim.Ease;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/** With motion reduced every animation timed here lands on its end state at once, and the clock goes quiet. */
class ReducedMotionTest {

    private static KronoGui still() {
        return KronoGui.attach(new Gui(Atchung.create()).reducedMotion(true), Kron.driven());
    }

    @Test
    void aRampDeliversOneAndArrivesInTheFirstTick() {
        List<Double> observed = new ArrayList<>();
        AtomicInteger done = new AtomicInteger();
        try (KronoGui krono = still()) {
            krono.ramp(ms(200), Ease.LINEAR, observed::add, done::incrementAndGet);
            krono.tick(ms(16));
            assertEquals(List.of(1d), observed, "the end state, and nothing on the way to it");
            assertEquals(1, done.get());
            assertTrue(krono.isQuiescent(), "no frames are held for a ramp that has already arrived");
            krono.tick(ms(400));
            assertEquals(1, done.get(), "and arrival is reported once");
        }
    }

    @Test
    void aRetargetedCellIsAtItsTargetFromTheFirstFrame() {
        List<Float> observed = new ArrayList<>();
        try (KronoGui krono = still()) {
            Node card = krono.gui().box();
            Cell<Length> elevation = krono.bound("elevation", Length.dp(2), card,
                    (node, length) -> observed.add(((Length.Dp) length).v()));
            krono.retarget(elevation, Length.dp(10), ms(100), Ease.LINEAR);
            krono.tick(ms(16));
            krono.tick(ms(32));
        }
        assertEquals(List.of(10f, 10f), observed);
    }

    @Test
    void anAnimatedPropertyIsWrittenItsEndValueOnce() {
        List<Length> observed = new ArrayList<>();
        try (KronoGui krono = still()) {
            Node card = krono.gui().box();
            krono.animate(card, (Node node, Length l) -> observed.add(l), Length.dp(0), Length.dp(8), ms(200),
                    Ease.LINEAR);
            for (int frame = 1; frame <= 5; frame++) {
                krono.tick(ms(16).times(frame));
            }
        }
        assertEquals(List.of(Length.dp(8)), observed);
    }

    @Test
    void anEnrolledNodeIsDrawnWhereLayoutPutItTheFrameItMoves() {
        try (KronoGui krono = still()) {
            Transitions moves = Transitions.on(krono);
            Node handle = krono.gui().box();
            RetainedNode row = new RetainedNode(handle.id());
            moves.follow(handle, ms(200), Ease.OUT_CUBIC);

            moves.moved(row, 0f, 0f, 0f, 100f);

            assertEquals(0f, moves.displacementY(row), "no lag, not even for the frame a ramp would take");
            assertTrue(moves.settled());
        }
    }

    @Test
    void aRepaintIsDrawnInItsNewColourAtOnce() {
        try (KronoGui krono = still()) {
            Fades fades = Fades.on(krono, ms(200), Ease.LINEAR);
            Color red = Color.rgba(1f, 0f, 0f, 1f);
            Color blue = Color.rgba(0f, 0f, 1f, 1f);
            fades.repainted(7L, PaintMotion.Channel.BACKGROUND, red, blue);
            assertEquals(blue, fades.drawn(7L, PaintMotion.Channel.BACKGROUND, blue));
            assertTrue(fades.settled());
        }
    }

    @Test
    void turningReducedMotionOffBringsTheMotionBack() {
        List<Double> observed = new ArrayList<>();
        try (KronoGui krono = still()) {
            krono.gui().reducedMotion(false);
            assertEquals(ms(200), krono.paced(ms(200)));
            krono.ramp(ms(100), Ease.LINEAR, observed::add, () -> { });
            for (int frame = 1; frame <= 6; frame++) {
                krono.tick(ms(25).times(frame));
            }
        }
        assertTrue(observed.size() > 2, "a ramp across frames again: " + observed);
        try (KronoGui krono = still()) {
            assertEquals(Dur.ZERO, krono.paced(ms(200)));
        }
    }

    /** Flat shading is the other choice: a tree drawn without depth still moves. */
    @Test
    void flatShadingAloneLeavesMotionAlone() {
        try (KronoGui krono = KronoGui.attach(new Gui(Atchung.create()).flatShading(true), Kron.driven())) {
            assertFalse(krono.still());
            assertEquals(ms(200), krono.paced(ms(200)));
        }
        try (KronoGui krono = KronoGui.attach(new Gui(Atchung.create()).simplifiedGraphics(true), Kron.driven())) {
            assertTrue(krono.still(), "and simplified graphics is both");
        }
    }
}
