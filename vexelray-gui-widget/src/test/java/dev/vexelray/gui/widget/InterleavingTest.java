package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Demonstrates deterministic control over when input handlers fire, using the manual-drain executor. Because all
 * input rides the bus and handlers run on an injected executor, a test can hold handlers in a queue and release
 * them one at a time — the hook for reproducing specific interleavings / races without any real threads.
 */
class InterleavingTest {

    private static TextField focusedField(HeadlessGui h, String initial) {
        TextField f = new TextField(h.gui, initial);
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    @Test
    void handlersAreDeferredUntilExplicitlyReleased() {
        try (HeadlessGui h = HeadlessGui.manual()) {
            TextField f = focusedField(h, "");

            // Publish two characters and dispatch — the two insert handlers are queued, not yet applied.
            h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                    new sibarum.tactroller.api.InputEvent.CharTyped('a', 0));
            h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                    new sibarum.tactroller.api.InputEvent.CharTyped('b', 0));
            h.dispatchOnly();

            assertEquals("", f.text(), "handlers are queued, not run");
            assertEquals(2, h.tasks.pending());

            h.tasks.runOne();
            assertEquals("a", f.text(), "releasing one handler applies exactly one edit");

            h.tasks.runOne();
            assertEquals("ab", f.text(), "releasing the next applies in order");
            assertEquals(0, h.tasks.pending());
        }
    }

    @Test
    void interleavingAKeyCommandBetweenTwoQueuedInserts() {
        try (HeadlessGui h = HeadlessGui.manual()) {
            TextField f = focusedField(h, "");

            // Queue an insert of 'x'.
            h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                    new sibarum.tactroller.api.InputEvent.CharTyped('x', 0));
            h.dispatchOnly();          // 1 queued
            h.tasks.runOne();          // "x", caret after it

            // Now a Backspace command and another insert are dispatched together; releasing the backspace first
            // then the insert yields a specific, reproducible result.
            h.down(Key.BACKSPACE);
            h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT,
                    new sibarum.tactroller.api.InputEvent.CharTyped('y', 0));
            h.dispatchOnly();
            h.up(Key.BACKSPACE);

            assertEquals(2, h.tasks.pending());
            h.tasks.runOne();          // backspace → ""
            assertEquals("", f.text());
            h.tasks.runOne();          // insert 'y' → "y"
            assertEquals("y", f.text());
        }
    }
}
