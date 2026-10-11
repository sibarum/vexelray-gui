package dev.vexelray.gui.harness;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With a second window open and nobody looking, a wake that lands mid-frame still gets its frame.
 *
 * <p>Found by {@code vexelray-framework}'s two-window witness (W1): the loop parked {@code forever} with the main
 * tree's frame owed, and every wake after that was suppressed because one was already owed. The wake is a message
 * on the thread's queue, and with two windows each one pumps that queue for its own frame, so a wake posted after
 * the main tree was drained can be taken by the second window's pump before the loop parks. Nobody focused means no
 * idle floor to end the park, so nothing does.
 *
 * <p>A race, so it is run many times: each round wakes the parked loop, then changes the tree again a moment later,
 * which often lands inside the frame the first change caused. Every round must end with nothing owed.
 *
 * <p>Needs a Vulkan device; this is an integration test, not a unit test.
 */
class TwoWindowsWakeTest {

    private static final int ROUNDS = 400;

    @Test
    void aWakeDuringAFrameIsNotLostToTheOtherWindowsPump() {
        Gui gui = new Gui();
        Node label = gui.text("0");
        gui.root().append(label);
        Gui other = new Gui();
        other.root().append(other.text("the other window"));

        try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("main", 400, 300))) {
            harness.app().pacing(() -> Long.MAX_VALUE).idleRefresh(0L);
            harness.app().window("other", () -> WindowSpec.of(WindowConfig.of("other", 300, 200), other)).show();
            assertTrue(harness.await(() -> harness.windows().size() == 2, 5_000), "the second window did not open");
            // Nobody looking, so no window is focused and no floor ends a park: a lost wake is a hang, not a delay.
            for (HarnessWindow w : harness.windows()) {
                w.focused(false);
            }
            harness.settle();

            for (int round = 1; round <= ROUNDS; round++) {
                label.text(round + "a");
                long until = System.nanoTime() + ThreadLocalRandom.current().nextLong(2_000_000L);
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                label.text(round + "b");
                int at = round;
                assertTrue(harness.await(() -> !gui.frameOwed(), 3_000),
                        "round " + at + ": the loop parked with a frame owed, and nothing will ever wake it");
            }
        }
    }
}
