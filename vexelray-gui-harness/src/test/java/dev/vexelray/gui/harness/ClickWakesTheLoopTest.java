package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assertion no hand-driven frame test can make: after a click, a frame arrives on its own.
 *
 * <p>Every failure in this area has the same shape — the handler runs, the state changes, and nothing
 * asks for the frame that would show it. A test that calls {@code Gui.frame()} itself passes either way,
 * which is how five missing wakes shipped past a green suite. Here the loop is real and parked, so the
 * only thing that can produce a frame is something actually asking for one.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class ClickWakesTheLoopTest {

    @Test
    void aClickWithNoPointerMotionProducesAFrame() {
        Gui gui = new Gui();
        AtomicInteger clicks = new AtomicInteger();
        Node button = gui.box().size(Length.dp(200), Length.dp(120));
        gui.root().append(button);
        gui.onClick(gui.root(), clicks::incrementAndGet);

        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("harness", 400, 300))) {
            harness.settle();
            long before = harness.frames();

            harness.click(20, 20);

            assertTrue(harness.awaitFrame(before, 3_000),
                    "a click has to produce a frame: the loop is parked, and nothing else will draw one");
            assertTrue(harness.await(() -> clicks.get() == 1, 2_000),
                    "and the handler has to have run: " + clicks.get());
        }
    }

    @Test
    void anIdleApplicationParksRatherThanSpinning() {
        Gui gui = new Gui();
        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("harness", 400, 300))) {
            harness.app().pacing(() -> Long.MAX_VALUE).idleRefresh(0L);
            harness.settle();
            long parked = harness.frames();

            harness.window().resetBudgets();
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertEquals(parked, harness.frames(),
                    "nothing changed, so nothing should have been drawn: " + harness.window().budgets());
        }
    }
}
