package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line between what is ordered and what is deferred.
 *
 * <p>These tests used to assert the opposite: that a text edit sat in the handler queue until a test released it,
 * so a specific interleaving of two inserts could be reproduced. That was a faithful description of the design and
 * the reason it was wrong — if a test can choose the order two typed characters apply in, so can a thread pool, and
 * in production one did. Applying an edit is now an ordered stage on the frame's input drain, so the interleaving
 * the old tests reproduced no longer exists to reproduce.
 *
 * <p>What the manual executor still controls is everything that legitimately has no ordering requirement: the
 * application-facing notifications. Those stay deferred, which is the whole point of dispatching them
 * asynchronously.
 */
class InterleavingTest {

    private static TextField focusedField(HeadlessGui h, String initial) {
        TextField f = new TextField(h.gui, initial);
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    private static void type(HeadlessGui h, char c) {
        h.bus.publish(dev.vexelray.gui.core.input.InputTopics.INPUT, new InputEvent.CharTyped(c, 0));
    }

    @Test
    void editsApplyDuringDispatchEvenWhenNoHandlerIsEverReleased() {
        try (HeadlessGui h = HeadlessGui.manual()) {
            TextField f = focusedField(h, "");

            type(h, 'a');
            type(h, 'b');
            h.dispatchOnly();

            assertEquals("ab", f.text(),
                    "the edit is an ordered stage on the drain, so it does not wait on the handler executor");
        }
    }

    @Test
    void aKeyCommandBetweenTwoInsertsAppliesWhereItArrived() {
        try (HeadlessGui h = HeadlessGui.manual()) {
            TextField f = focusedField(h, "");

            type(h, 'x');
            h.dispatchOnly();
            assertEquals("x", f.text());

            // Backspace then 'y', published in that order, must apply in that order — there is no longer any way
            // for a consumer to choose otherwise.
            h.down(Key.BACKSPACE);
            type(h, 'y');
            h.dispatchOnly();
            h.up(Key.BACKSPACE);

            assertEquals("y", f.text(), "backspace consumed the 'x', then 'y' was inserted");
        }
    }

    @Test
    void applicationNotificationsStayDeferredAndArriveInOrder() {
        try (HeadlessGui h = HeadlessGui.manual()) {
            TextField f = focusedField(h, "");
            List<String> seen = new ArrayList<>();
            f.onChange(seen::add);

            type(h, 'a');
            type(h, 'b');
            h.dispatchOnly();

            assertEquals("ab", f.text(), "the document is already current");
            assertEquals(List.of(), seen, "but the application has not been told yet — that is the async part");
            assertTrue(h.tasks.pending() >= 2, "one queued notification per change");

            h.tasks.drain();
            assertEquals(List.of("a", "ab"), seen,
                    "notifications arrive as the versions that produced them, in commit order");
        }
    }
}
