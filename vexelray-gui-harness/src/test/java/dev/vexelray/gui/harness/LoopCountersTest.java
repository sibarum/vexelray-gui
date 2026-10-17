package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.LoopCounters;
import dev.vexelray.gui.core.app.LoopCounters.Focus;
import dev.vexelray.gui.core.app.LoopCounters.Park;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop's counters (docs/plans/meter.md §3) against a real loop: each park kind counted when, and only when, its
 * bound is the one that decided the park; the frame number advancing once per frame; draws and wakes counted.
 *
 * <p>Every check is a difference between two readings, the way a driver reads them: the counters are never reset.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class LoopCountersTest {

    /** Long enough for tens of parks at the bounds below, short enough that five runs of it are a second or two. */
    private static final long WATCH_MILLIS = 300L;

    @Test
    void anUnwatchedLoopWithNoDeadlineParksForever() {
        try (HarnessApp harness = start()) {
            configure(harness, Long.MAX_VALUE, 0L, 0L, false);
            Map<Park, Long> before = parks(harness.app());
            long frame = harness.app().frame();
            sleep(WATCH_MILLIS);
            Map<Park, Long> d = since(before, harness.app());

            assertEquals(frame, harness.app().frame(), "nothing was owed, so no frame should have begun");
            assertEquals(0L, total(d), "a loop parked forever parks once, before this window opened: " + d);

            harness.app().postWake();
            assertTrue(harness.await(() -> harness.app().parks(Park.FOREVER) > before.get(Park.FOREVER), 2_000),
                    "the frame a wake earns is followed by another park forever: " + since(before, harness.app()));
            assertEquals(Map.of(Park.FOREVER, 1L), nonZero(since(before, harness.app())));
            assertFalse(harness.app().focused(), "nobody was looking");
        }
    }

    @Test
    void theApplicationsPacingIsADeadline() {
        try (HarnessApp harness = start()) {
            configure(harness, 20_000_000L, 0L, 0L, false);
            Map<Park, Long> d = watch(harness);
            assertTrue(d.get(Park.DEADLINE) >= 5, "a 20 ms pacing parks on it: " + d);
            assertEquals(Map.of(Park.DEADLINE, d.get(Park.DEADLINE)), nonZero(d), "and on nothing else: " + d);
        }
    }

    @Test
    void theIdleRefreshIsAFloorWhileFocused() {
        try (HarnessApp harness = start()) {
            configure(harness, Long.MAX_VALUE, 20_000_000L, 0L, true);
            Map<Park, Long> d = watch(harness);
            assertTrue(d.get(Park.FLOOR) >= 5, "a focused loop parks on its idle refresh: " + d);
            assertEquals(Map.of(Park.FLOOR, d.get(Park.FLOOR)), nonZero(d), "and on nothing else: " + d);
            assertTrue(harness.app().focused());
        }
    }

    @Test
    void theFrameRateCapIsACeiling() {
        try (HarnessApp harness = start()) {
            configure(harness, 0L, 0L, 20_000_000L, false);
            Map<Park, Long> d = watch(harness);
            assertTrue(d.get(Park.CEILING) >= 5, "pacing that always wants a frame parks on the ceiling: " + d);
            // A frame that took the whole gap parks for nothing; nothing else may bound one.
            assertEquals(0L, d.get(Park.FOREVER) + d.get(Park.DEADLINE) + d.get(Park.FLOOR), "" + d);
        }
    }

    @Test
    void aWakeDuringAFrameIsOwedANoParkAndASecondIsCoalesced() {
        AtomicBoolean armed = new AtomicBoolean();
        GuiApp[] app = new GuiApp[1];
        Gui gui = new Gui();
        gui.root().append(gui.text("counted"));
        // Inside the frame, after it has cleared the wake it began with: the first is owed the next frame, so the
        // loop must not park; the second finds it already pending.
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("counters", 400, 300), GuiApp.Compute.SHARED,
                () -> {
                    if (armed.getAndSet(false)) {
                        app[0].postWake();
                        app[0].postWake();
                    }
                })) {
            app[0] = harness.app();
            configure(harness, Long.MAX_VALUE, 0L, 0L, false);
            Map<Park, Long> before = parks(harness.app());
            long frame = harness.app().frame();
            long wakes = harness.app().wakes();
            long coalesced = harness.app().wakesCoalesced();

            armed.set(true);
            harness.app().postWake();
            assertTrue(harness.await(() -> harness.app().parks(Park.FOREVER) > before.get(Park.FOREVER), 2_000),
                    "the loop never parked again: " + since(before, harness.app()));

            assertEquals(Map.of(Park.ZERO, 1L, Park.FOREVER, 1L), nonZero(since(before, harness.app())));
            assertEquals(frame + 2L, harness.app().frame(), "the test's wake earned a frame, and the one owed another");
            assertEquals(wakes + 3L, harness.app().wakes());
            assertEquals(coalesced + 1L, harness.app().wakesCoalesced());
        }
    }

    @Test
    void theFocusOverrideDecidesWhetherTheFloorApplies() {
        try (HarnessApp harness = start()) {
            configure(harness, Long.MAX_VALUE, 20_000_000L, 0L, false);
            GuiApp app = harness.app();

            app.focusOverride(Focus.ON);
            app.postWake();
            Map<Park, Long> on = watch(harness);
            assertTrue(on.get(Park.FLOOR) >= 5, "simulated focus brings the floor in: " + on);
            assertTrue(app.focused(), "and the park says it decided on focus");

            app.focusOverride(Focus.OS);
            app.postWake();
            harness.settle();
            Map<Park, Long> os = watch(harness);
            assertEquals(0L, total(os), "the windows are unfocused, so the floor goes again: " + os);
            assertFalse(app.focused());

            for (HarnessWindow w : harness.windows()) {
                w.focused(true);
            }
            app.focusOverride(Focus.OFF);
            app.postWake();
            harness.settle();
            Map<Park, Long> off = watch(harness);
            assertEquals(0L, total(off), "simulated unfocus parks forever under a focused window: " + off);
            assertFalse(app.focused());
            assertEquals(Focus.OFF, app.focusOverride());
        }
    }

    @Test
    void theFrameNumberAdvancesOncePerFrameAndDrawsAreCounted() {
        Gui gui = new Gui();
        Node label = gui.text("0");
        gui.root().append(label);
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("counters", 400, 300))) {
            configure(harness, Long.MAX_VALUE, 0L, 0L, false);
            GuiApp app = harness.app();
            long frame = app.frame();
            long frames = harness.frames();
            long drawn = app.drawn();
            long presented = app.presented();

            int changes = 10;
            for (int i = 1; i <= changes; i++) {
                long was = app.presented();
                label.text("change " + i);
                assertTrue(harness.await(() -> app.presented() > was, 2_000), "change " + i + " was never presented");
            }
            harness.settle();

            assertEquals(harness.frames() - frames, app.frame() - frame,
                    "one frame number per frame the loop ran, and the main window's hook runs once in each");
            assertEquals(changes, app.drawn() - drawn, "each change is one draw");
            assertEquals(changes, app.presented() - presented, "and each draw was presented");
            long previous = -1L;
            for (long n = presented + 1; n <= app.presented(); n++) {
                long start = app.presentedStart(n);
                assertTrue(start > previous, "presented frame starts are in order: " + n);
                previous = start;
            }
            assertEquals(-1L, app.presentedStart(app.presented() + 1), "a frame not yet presented has no start");
            assertEquals(-1L, app.presentedStart(0L));
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static HarnessApp start() {
        Gui gui = new Gui();
        gui.root().append(gui.text("counted"));
        return HarnessApp.start(gui, WindowConfig.of("counters", 400, 300));
    }

    /** Set the three bounds and the windows' focus, then let the loop reach the regime they make. */
    private static void configure(HarnessApp harness, long pacing, long idleRefresh, long ceiling, boolean focused) {
        for (HarnessWindow w : harness.windows()) {
            w.focused(focused);
        }
        harness.app().pacing(() -> pacing).idleRefresh(idleRefresh).maxFrameRate(ceiling);
        harness.app().postWake();   // a parked loop reads the new bounds only at its next park
        harness.settle(150L, 1_000L);   // a floor, a deadline or a ceiling never quietens: settle gives up
        sleep(50L);   // and one park passes in the new regime before anything is measured
    }

    /** The parks, by kind, over {@link #WATCH_MILLIS} from now. */
    private static Map<Park, Long> watch(HarnessApp harness) {
        Map<Park, Long> before = parks(harness.app());
        sleep(WATCH_MILLIS);
        return since(before, harness.app());
    }

    private static Map<Park, Long> parks(LoopCounters counters) {
        Map<Park, Long> m = new EnumMap<>(Park.class);
        for (Park p : Park.values()) {
            m.put(p, counters.parks(p));
        }
        return m;
    }

    private static Map<Park, Long> since(Map<Park, Long> before, LoopCounters counters) {
        Map<Park, Long> d = new EnumMap<>(Park.class);
        for (Park p : Park.values()) {
            d.put(p, counters.parks(p) - before.get(p));
        }
        return d;
    }

    private static Map<Park, Long> nonZero(Map<Park, Long> d) {
        Map<Park, Long> m = new EnumMap<>(Park.class);
        d.forEach((p, n) -> {
            if (n != 0L) {
                m.put(p, n);
            }
        });
        return m;
    }

    private static long total(Map<Park, Long> d) {
        return d.values().stream().mapToLong(Long::longValue).sum();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
