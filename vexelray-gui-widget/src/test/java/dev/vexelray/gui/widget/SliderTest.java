package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A slider rests on its lattice, holds the thumb near each point without ever jumping it, and draws the points. */
class SliderTest {

    // --- the lattice -------------------------------------------------------------------------------------------

    @Test
    void anEvenLatticeKeepsTheFarEndEvenWhereTheStepDoesNotDivideIt() {
        Lattice l = Lattice.every(0.4);
        assertEquals(4, l.size(), "0, 0.4, 0.8 and the end");
        assertEquals(0.8f, l.point(2), 1e-6f);
        assertEquals(1f, l.point(3));
        assertEquals(1f, l.nearest(0.95f));
        assertEquals(0.8f, l.nearest(0.85f), 1e-6f);
    }

    @Test
    void aStepThatDividesTheTravelDoesNotGrowASliverPointAtTheEnd() {
        Lattice l = Lattice.intervals(3);
        assertEquals(4, l.size());
        assertEquals(1f / 3f, l.point(1), 1e-6f);
        assertEquals(2f / 3f, l.point(2), 1e-6f);
    }

    @Test
    void evenAndListedLatticesAgreeOnWhereEveryFractionFalls() {
        Lattice even = Lattice.intervals(7);
        double[] pts = new double[8];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = even.point(i);
        }
        Lattice listed = Lattice.of(pts);
        for (int k = -10; k <= 1010; k++) {
            float f = k / 1000f;
            assertEquals(listed.below(f), even.below(f), "below(" + f + ")");
            assertEquals(listed.nearest(f), even.nearest(f), "nearest(" + f + ")");
        }
    }

    @Test
    void noPointsRestsAnywhere() {
        Lattice l = Lattice.none();
        assertEquals(0, l.size());
        assertEquals(0.37f, l.nearest(0.37f));
        assertEquals(0.37f, Slider.detent(l, 0.37f));
        assertEquals(l.size(), Lattice.every(0).size(), "a zero step is no lattice");
    }

    // --- the detent --------------------------------------------------------------------------------------------

    @Test
    void theThumbNeverJumpsAndNeverRunsBackwards() {
        for (Lattice l : List.of(Lattice.intervals(3), Lattice.intervals(50), Lattice.every(0.3),
                Lattice.of(0, 0.1, 0.15, 0.9))) {
            float prev = Slider.detent(l, 0f);
            for (int k = 1; k <= 10_000; k++) {
                float now = Slider.detent(l, k / 10_000f);
                assertTrue(now >= prev, "monotone");
                // The steepest stretch crosses a gap at 1 / (1 - 2 HOLD) the pointer's rate; anything more is a jump.
                assertTrue(now - prev <= 1e-4f / (1f - 2f * Slider.HOLD) + 1e-6f, "continuous at " + k);
                prev = now;
            }
        }
    }

    @Test
    void aPointHoldsTheThumbNearItAndLetsGoBetween() {
        Lattice l = Lattice.intervals(3);
        float third = 1f / 3f;
        assertEquals(third, Slider.detent(l, third + 0.05f), 1e-6f, "held: inside a fifth of the gap");
        assertEquals(third, Slider.detent(l, third - 0.05f), 1e-6f, "held from below too");
        assertEquals(0.5f, Slider.detent(l, 0.5f), 1e-6f, "the gap's midpoint is crossed at the midpoint");
    }

    @Test
    void theValueTurnsOverWhereTheThumbIsHalfwayAcross() {
        Lattice l = Lattice.intervals(3);
        float mid = 0.5f;
        assertEquals(1f / 3f, l.nearest(mid - 1e-4f), 1e-6f);
        assertEquals(2f / 3f, l.nearest(mid + 1e-4f), 1e-6f);
        assertEquals(mid, Slider.detent(l, mid), 1e-5f);
    }

    @Test
    void ticksThinTheWayARulerDoes() {
        assertEquals(1, Slider.stride(0.3f));
        assertEquals(1, Slider.stride(1f));
        assertEquals(2, Slider.stride(1.5f));
        assertEquals(5, Slider.stride(4f));
        assertEquals(10, Slider.stride(6f));
        assertEquals(50, Slider.stride(21f));
    }

    // --- the widget --------------------------------------------------------------------------------------------

    private static Slider mount(HeadlessGui h, Lattice l) {
        Slider s = new Slider(h.gui, 0f).lattice(l);
        s.node().width(Length.dp(400));
        h.gui.root().children(s.node());
        h.frame().frame();
        return s;
    }

    /** The pointer x that puts the thumb's centre at fraction {@code f}. */
    private static float xAt(Slider s, float f) {
        Rect r = s.node().layout().rect();
        return r.x() + r.h() / 2f + f * (r.w() - r.h());
    }

    private static float yOf(Slider s) {
        Rect r = s.node().layout().rect();
        return r.y() + r.h() / 2f;
    }

    @Test
    void draggingAcrossACoarseLatticeReportsEachPointOnceAndRestsOnOne() {
        try (HeadlessGui h = new HeadlessGui()) {
            Slider s = mount(h, Lattice.intervals(3));
            List<Double> seen = new ArrayList<>();
            s.onChange(seen::add);
            int y = (int) yOf(s);
            h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, (int) xAt(s, 0f), y, 0));
            h.frame();
            for (int k = 1; k <= 20; k++) {
                int x = (int) xAt(s, k * 0.03f);
                h.bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(x, y, 1, 0, 0));
                h.frame();
            }
            h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, (int) xAt(s, 0.6f), y, 0));
            h.frame().frame();

            assertEquals(List.of((double) (1f / 3f), (double) (2f / 3f)), seen,
                    "0.6 is nearer two thirds; one report per point crossed, none per pointer move");
            assertEquals(2f / 3f, s.value(), 1e-6f);
        }
    }

    @Test
    void showingTheValueTheSliderAlreadyHoldsDoesNotTellAnyone() {
        try (HeadlessGui h = new HeadlessGui()) {
            Slider s = mount(h, Lattice.intervals(4));
            List<Double> seen = new ArrayList<>();
            s.onChange(seen::add);
            s.show(0.5f);
            s.show(0.49f);
            h.frame();
            assertEquals(0.5f, s.value());
            assertTrue(seen.isEmpty());
        }
    }

    @Test
    void arrowKeysStepOnePointAndStopAtTheEnds() {
        try (HeadlessGui h = new HeadlessGui()) {
            Slider s = mount(h, Lattice.intervals(3));
            h.focus(s.node());
            h.tap(Key.RIGHT).frame();
            assertEquals(1f / 3f, s.value(), 1e-6f);
            h.tap(Key.RIGHT).frame().tap(Key.RIGHT).frame().tap(Key.RIGHT).frame();
            assertEquals(1f, s.value());
            h.tap(Key.LEFT).frame();
            assertEquals(2f / 3f, s.value(), 1e-6f);
            h.tap(Key.HOME).frame();
            assertEquals(0f, s.value());
        }
    }

    @Test
    void aLatticeIsDrawnAndNoLatticeIsNot() {
        try (HeadlessGui h = new HeadlessGui()) {
            Slider s = mount(h, Lattice.intervals(3));
            h.frame();
            assertTrue(h.retained(s.node()).raw(dev.vexelray.gui.core.model.PropKey.PICTURE) != null);
            s.lattice(Lattice.none());
            h.frame();
            assertNull(h.retained(s.node()).raw(dev.vexelray.gui.core.model.PropKey.PICTURE));
        }
    }
}
