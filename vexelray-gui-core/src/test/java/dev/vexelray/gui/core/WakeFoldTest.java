package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wake is a cell: a frame is owed, or it is not, and saying so twice does not owe two.
 *
 * <p>Every wake is a nudge of the host's message queue — a syscall — and it used to be one per property write.
 * The mutation mailbox folds those writes into a single slot, so the fold was reducing the cheap half of the
 * cost and leaving the expensive half untouched. These tests hold the useful half of the invariant in place:
 * a burst wakes once, and — the half that actually matters — <b>every</b> edit that arrives after a frame has
 * started still wakes, because an under-wake is a UI that quietly stops updating.
 */
class WakeFoldTest {

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    @Test
    void aBurstOfEditsWakesTheLoopOnce() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            AtomicInteger wakes = new AtomicInteger();
            Node box = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().append(box);
            gui.frame(400f, 100f, WakeFoldTest::noText);

            gui.onWork(wakes::incrementAndGet);
            for (int i = 1; i <= 10_000; i++) {
                box.width(Length.rem(i));
            }

            assertEquals(1, wakes.get(),
                    "ten thousand writes owe one frame, so they are worth one nudge of the message queue");
        }
    }

    @Test
    void theNextEditAfterAFrameWakesAgain() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            AtomicInteger wakes = new AtomicInteger();
            Node box = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().append(box);
            gui.frame(400f, 100f, WakeFoldTest::noText);
            gui.onWork(wakes::incrementAndGet);

            box.width(Length.rem(2));
            assertEquals(1, wakes.get());

            gui.frame(400f, 100f, WakeFoldTest::noText);   // the frame that was owed has been drawn

            box.width(Length.rem(3));
            assertEquals(2, wakes.get(),
                    "the wake is re-armed by the frame it asked for, so the next edit asks again");
        }
    }

    @Test
    void anEditDuringAFrameStillGetsItsOwnFrame() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node box = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().append(box);

            AtomicInteger wakes = new AtomicInteger();
            gui.onWork(wakes::incrementAndGet);
            // A resize observer that edits in response to a layout is the ordinary way this happens: the box
            // gets its first computed rect during this frame, the observer hears about it, and its edit is
            // published from inside frame() — after the wake flag was cleared at the top of it.
            gui.onResizeUi(box, layout -> box.height(Length.rem(2)));
            gui.frame(400f, 100f, WakeFoldTest::noText);

            assertTrue(wakes.get() >= 1,
                    "an edit made during a frame is not covered by the wake that asked for that frame — it "
                            + "needs one of its own, or it sits unseen until something unrelated draws");
        }
    }

    @Test
    void aWakeWhileNothingWasListeningDoesNotSuppressTheFirstRealOne() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            // A tree built before it is presented — a palette, a preferences window — edits freely with no
            // listener wired. Those wakes reached nothing.
            Node box = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().append(box);

            AtomicInteger wakes = new AtomicInteger();
            gui.onWork(wakes::incrementAndGet);
            box.width(Length.rem(4));

            assertEquals(1, wakes.get(),
                    "wiring a listener re-arms the wake: the loop that is listening now has not been told");
        }
    }
}
