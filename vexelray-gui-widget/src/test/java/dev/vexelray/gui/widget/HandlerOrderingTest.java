package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Input arrives at the dispatcher totally ordered — one thread, one FIFO drain — and a widget whose state
 * transitions depend on that order must still see it after the handoff to application handlers.
 *
 * <p>This is the one test that runs handlers on {@link Gui}'s real worker pool. Every other test in this module
 * uses a same-thread or manually-drained executor, which is what makes them deterministic and also what makes
 * them incapable of observing the handoff at all: a defect that only exists when two handlers run concurrently
 * is invisible to a harness that never runs two handlers concurrently.
 *
 * @see HeadlessGui#threaded()
 */
class HandlerOrderingTest {

    /** Long enough that a single reordered pair anywhere in the burst fails the assertion. */
    private static final int BURST = 64;

    /** How long to let the worker pool finish before reading the result. */
    private static final long SETTLE_MILLIS = 2000L;

    @Test
    void aBurstOfTypedCharactersLandsInTheOrderItWasTyped() throws InterruptedException {
        try (HeadlessGui h = HeadlessGui.threaded()) {
            TextField f = new TextField(h.gui, "");
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());

            String typed = burst();
            h.type(typed);
            settle(f, typed.length());

            assertEquals(typed, f.text(),
                    "typed characters are delivered to the dispatcher in order and must be applied in order; "
                            + "handing each one to a pool separately orders the invocations but not the effects");
        }
    }

    /**
     * The same property for edit commands rather than typed text: a run of backspaces has to consume the document
     * from the right, one character per press, whatever thread each press lands on.
     */
    @Test
    void aBurstOfBackspacesRemovesExactlyOneCharacterEach() throws InterruptedException {
        try (HeadlessGui h = HeadlessGui.threaded()) {
            String initial = burst();
            TextField f = new TextField(h.gui, initial);
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());

            int deletions = BURST / 2;
            for (int i = 0; i < deletions; i++) {
                h.down(sibarum.tactroller.api.Key.BACKSPACE);
            }
            h.frame();
            h.up(sibarum.tactroller.api.Key.BACKSPACE);
            settle(f, initial.length() - deletions);

            assertEquals(initial.substring(0, initial.length() - deletions), f.text(),
                    "each backspace removes the character before the caret, so N presses remove exactly N");
        }
    }

    /** {@value #BURST} distinct characters, so a reordering shows up as a wrong string rather than a wrong count. */
    private static String burst() {
        StringBuilder sb = new StringBuilder(BURST);
        for (int i = 0; i < BURST; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    /** Wait for the worker pool to reach {@code expectedLength}, then a beat longer for any straggler. */
    private static void settle(TextField f, int expectedLength) throws InterruptedException {
        long deadline = System.nanoTime() + SETTLE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline && f.text().length() != expectedLength) {
            Thread.sleep(5L);
        }
        Thread.sleep(50L);
    }
}
