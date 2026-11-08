package dev.vexelray.gui.core;

import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A drag that has travelled far enough and is waiting out its hold owes the loop frames until it begins.
 *
 * <p>Found driving a background run with ottermate: {@code down} on a row, a {@code move} shorter than the 90ms hold,
 * then nothing — and the loop parked, the tick that starts a drag never ran, and the drag appeared only when the button
 * came up. A hand on a mouse keeps moving, and each move carries its own time, so it all but never met this.
 */
class DragHoldTest {

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static void frame(Gui gui) {
        Seams.of(gui).frame(400f, 300f, DragHoldTest::noText);
    }

    private static void publish(Gui gui, InputEvent e) {
        gui.bus().publish(gui.input(), e);
        gui.wakeForInput();
    }

    @Test
    void aPathShorterThanTheHoldStillStartsItsDragWithoutAnotherEvent() throws InterruptedException {
        try (Gui gui = new Gui(Atchung.create())) {
            Node row = gui.box().width(Length.dp(200)).height(Length.dp(100));
            gui.root().append(row);
            gui.draggable(row, Payload.of(PayloadType.of("row"), "r"));
            frame(gui);

            long pressed = System.nanoTime();
            publish(gui, new InputEvent.ButtonPressed(MouseButton.LEFT, 20, 20, pressed));
            publish(gui, new InputEvent.PointerMoved(20, 60, 0, 40, pressed));
            frame(gui);
            assumeTrue(System.nanoTime() - pressed < 60_000_000L,
                    "the frame came after most of the hold had passed; nothing to observe on a machine this busy");
            assertNull(gui.dragSession(), "far enough, not yet held long enough");

            // As a parking loop runs: a frame while one is owed, and none otherwise. Nothing further is published.
            long deadline = pressed + 1_000_000_000L;
            while (gui.frameOwed() && System.nanoTime() < deadline) {
                frame(gui);
                Thread.sleep(4);
            }

            assertNotNull(gui.dragSession(), "the loop kept framing until a tick started the drag, button still down");
            assertFalse(gui.frameOwed(), "and once it had begun, holding still owed nothing more");
        }
    }
}
