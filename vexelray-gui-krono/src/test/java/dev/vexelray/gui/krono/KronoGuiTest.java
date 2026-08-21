package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Signal;
import sibarum.kronometer.anim.Ease;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The scheduling and automation primitives, driven headlessly.
 *
 * <p>No Vulkan, no window, no clicking: a {@code Gui} on a private bus, plus the ticks a frame loop
 * would otherwise supply. That the whole automation layer can be exercised this way is the point of the
 * driven clock, and it is the same shape a Tactroller scenario harness wants.
 *
 * <p>These deliberately do not call {@code Gui.frame}, so they exercise no routing or layout — they
 * observe what a node setter is <em>handed</em>. The retained model is {@code gui-core}'s to write, and
 * this module has no business reaching into it to check.
 */
class KronoGuiTest {

    /**
     * A Gui with no window, and the ticks a frame loop would otherwise supply.
     *
     * <p>A <em>driven</em> clock, not a virtual one, and that is the finding rather than a workaround:
     * because the caller supplies every tick value, a driven run is already fully deterministic. The
     * virtual clock exists to invent moments when nobody else will, and here the harness is the one
     * inventing them.
     */
    private static KronoGui headless() {
        Gui gui = new Gui(Atchung.create());
        return KronoGui.attach(gui, Kron.driven());
    }

    @Test
    @DisplayName("a bound signal lands on a node property once per frame")
    void bindDeliversPerFrame() {
        List<Length> observed = new ArrayList<>();
        try (KronoGui krono = headless()) {
            Kron kron = krono.kron();
            Node card = krono.gui().box();
            Cell<Length> elevation = kron.cell("elevation", Length.dp(2));

            // Record what the setter is handed rather than reading the node back: the retained model is
            // gui-core's to write, and this module has no business reaching into it.
            krono.bind(card, (node, length) -> observed.add(length), elevation);

            for (int frame = 1; frame <= 3; frame++) {
                krono.tick(ms(16).times(frame));
            }
        }
        assertEquals(List.of(Length.dp(2), Length.dp(2), Length.dp(2)), observed);
    }

    @Test
    @DisplayName("a retargeted cell animates through the frames, and lands exactly")
    void retargetAnimatesAcrossFrames() {
        List<Float> observed = new ArrayList<>();
        try (KronoGui krono = headless()) {
            Kron kron = krono.kron();
            Node card = krono.gui().box();
            Cell<Length> elevation = krono.bound("elevation", Length.dp(2), card,
                    (node, length) -> observed.add(((Length.Dp) length).v()));

            krono.retarget(elevation, Length.dp(10), ms(100), Ease.LINEAR);
            for (int frame = 1; frame <= 5; frame++) {
                krono.tick(ms(25).times(frame));
            }
        }
        // 25 ms steps across a 100 ms ramp from 2 to 10, then held.
        assertEquals(List.of(4f, 6f, 8f, 10f, 10f), observed);
    }

    @Test
    @DisplayName("retargeting mid-flight continues from the current value")
    void retargetIsContinuous() {
        List<Float> observed = new ArrayList<>();
        try (KronoGui krono = headless()) {
            Kron kron = krono.kron();
            Node card = krono.gui().box();
            Cell<Length> elevation = krono.bound("elevation", Length.dp(0), card,
                    (node, length) -> observed.add(((Length.Dp) length).v()));

            krono.retarget(elevation, Length.dp(10), ms(100), Ease.LINEAR);
            krono.tick(ms(40));                       // at 4
            krono.retarget(elevation, Length.dp(0), ms(100), Ease.LINEAR);
            krono.tick(ms(80));                       // 40 % back down from 4
            krono.tick(ms(140));

            assertEquals(3, observed.size());
        }
        assertEquals(4f, observed.get(0), 1e-4f, "40 % of the way up");
        assertTrue(observed.get(1) < 4f, "should be coming back down from 4, not from 10");
        assertTrue(observed.get(1) > 1.5f, "and continuously, not snapped to 0");
    }

    @Test
    @DisplayName("scheduling works from off the timeline, which is where handlers live")
    void schedulingFromAHandlerThread() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        try (KronoGui krono = headless()) {
            Kron kron = krono.kron();
            // A click handler runs on the GUI's worker executor. A bare Time.spork from there would be
            // refused, so everything here routes through onTimeline — this asserts that, from a genuinely
            // different thread.
            Thread handler = Thread.ofPlatform().start(() -> krono.onTimeline(fired::incrementAndGet));
            handler.join();

            krono.tick(ms(16));
            assertEquals(1, fired.get(), "the posted work should have run on the first tick");
        }
    }

    @Test
    @DisplayName("after() runs once, later")
    void afterRunsOnce() {
        List<String> log = new ArrayList<>();
        try (KronoGui krono = headless()) {
            Kron kron = krono.kron();
            krono.after(ms(50), () -> log.add("fired"));

            krono.tick(ms(30));
            assertEquals(List.of(), log, "not yet");
            krono.tick(ms(60));
            assertEquals(List.of("fired"), log);
            krono.tick(ms(200));
            assertEquals(List.of("fired"), log, "and only once");
        }
    }

    @Test
    @DisplayName("every() is drift-free, and cancellable")
    void everyIsDriftFree() {
        List<String> log = new ArrayList<>();
        try (KronoGui krono = headless()) {
            Kron kron = krono.kron();
            var ticker = krono.every(ms(30), () -> log.add("beat@" + kron.now()));
            krono.tick(ms(100));

            // Exactly on the 30 ms grid, computed from the origin rather than accumulated per beat.
            assertEquals(List.of("beat@@30ms", "beat@@60ms", "beat@@90ms"), log);

            ticker.cancel();
            krono.tick(ms(200));
            assertEquals(3, log.size(), "cancelled means cancelled");
        }
    }

    @Test
    @DisplayName("a nested tempo slows a region of the UI without touching its animations")
    void nestedTempoSlowsARegion() {
        List<Float> normal = new ArrayList<>();
        List<Float> slow = new ArrayList<>();
        try (KronoGui krono = headless()) {
            Kron kron = krono.kron();
            Node a = krono.gui().box();
            Node b = krono.gui().box();

            var bullet = krono.tempo().child(sibarum.kronometer.Ratio.of(1, 2));
            Cell<Length> fast = kron.cell("fast", Length.dp(0));
            Cell<Length> halfSpeed = kron.cell("slow", bullet, Length.dp(0));

            krono.bind(a, (n, l) -> normal.add(((Length.Dp) l).v()), fast);
            krono.bind(b, (n, l) -> slow.add(((Length.Dp) l).v()), halfSpeed);

            krono.retarget(fast, Length.dp(10), ms(100), Ease.LINEAR);
            krono.retarget(halfSpeed, Length.dp(10), ms(100), Ease.LINEAR);

            krono.tick(ms(100));
        }
        // The same 100 ms animation, declared identically, is half done in the half-speed region. Neither
        // call site mentions slow motion.
        assertEquals(10f, normal.get(0), 1e-4f);
        assertEquals(5f, slow.get(0), 1e-4f);
    }

    @Test
    @DisplayName("a bound signal derived from a curve stays predictable")
    void animationsRemainPrecomputable() {
        try (KronoGui krono = headless()) {
            Kron kron = krono.kron();
            Node card = krono.gui().box();
            Cell<Length> elevation = krono.bound("elevation", Length.dp(2), card, Node::elevation);
            Signal<Length> derived = kron.computed("shadow", elevation::get);

            krono.retarget(elevation, Length.dp(10), ms(100), Ease.OUT_CUBIC);
            krono.tick(ms(16));

            // Which is the whole reason to put the value in a signal rather than a shred: a curve's future
            // is knowable, so the frames of this animation can be computed ahead of the frame that shows
            // them. Nothing here had to ask for that.
            assertEquals(sibarum.kronometer.Moment.FOREVER, derived.horizon());
        }
    }
}
