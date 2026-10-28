package dev.vexelray.gui.core;

import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tween announces where it is at each step the clock drives, turns from wherever it is when sent somewhere new, and
 * takes only the part of its timing the distance left needs.
 */
class TweenTest {

    private static final Timing WHOLE = Timing.millis(160, Timing.Curve.OUT_CUBIC);

    /** A clock the test steps: each run is kept, with the timing it was asked for. */
    private static final class Steps {
        final List<Timing> timings = new ArrayList<>();
        final List<DoubleConsumer> progress = new ArrayList<>();
        final List<Runnable> done = new ArrayList<>();
    }

    private static Steps install(Gui gui) {
        Steps s = new Steps();
        Seams.of(gui).clock((timing, progress, done) -> {
            s.timings.add(timing);
            s.progress.add(progress);
            s.done.add(done);
        });
        return s;
    }

    @Test
    void announcesEachStepAndTheArrival() {
        Gui gui = new Gui();
        Steps clock = install(gui);
        List<Tween.Tweened> heard = new ArrayList<>();
        gui.bus().subscribe(Tween.VALUE, heard::add);
        Tween lift = Tween.of(gui, "lift", WHOLE);
        String at = lift.address();

        lift.toward(1f);
        clock.progress.get(0).accept(0.0);
        clock.progress.get(0).accept(0.5);
        clock.done.get(0).run();

        assertEquals(List.of(new Tween.Tweened(at, 0f, false), new Tween.Tweened(at, 0.5f, false),
                new Tween.Tweened(at, 1f, true)), heard);
        assertEquals(WHOLE, clock.timings.get(0), "the whole way takes the whole timing");
        gui.close();
    }

    @Test
    void turnsFromWhereItIsInThePartOfTheTimingLeft() {
        Gui gui = new Gui();
        Steps clock = install(gui);
        List<Tween.Tweened> heard = new ArrayList<>();
        gui.bus().subscribe(Tween.VALUE, heard::add);
        Tween lift = Tween.of(gui, "lift", WHOLE);
        String at = lift.address();

        lift.toward(1f);
        clock.progress.get(0).accept(0.25);
        lift.toward(0f);
        heard.clear();
        clock.progress.get(0).accept(0.75);   // the overtaken ramp
        clock.done.get(0).run();
        assertTrue(heard.isEmpty(), "an overtaken ramp announces nothing: " + heard);

        assertEquals(Duration.ofMillis(40), clock.timings.get(1).duration(), "a quarter of the way back, a quarter of the time");
        assertEquals(WHOLE.curve(), clock.timings.get(1).curve());
        clock.progress.get(1).accept(0.0);
        clock.progress.get(1).accept(0.5);
        clock.done.get(1).run();
        assertEquals(List.of(new Tween.Tweened(at, 0.25f, false),
                new Tween.Tweened(at, 0.125f, false),
                new Tween.Tweened(at, 0f, true)), heard, "from where it was, with no jump");
        gui.close();
    }

    @Test
    void withNoClockItLandsAtOnce() {
        Gui gui = new Gui();
        List<Tween.Tweened> heard = new ArrayList<>();
        gui.bus().subscribe(Tween.VALUE, heard::add);

        Tween lift = Tween.of(gui, "lift", WHOLE);
        String at = lift.address();
        lift.toward(3f);

        assertEquals(List.of(new Tween.Tweened(at, 1f, true)), heard, "clamped, and settled at once");
        gui.close();
    }
}
