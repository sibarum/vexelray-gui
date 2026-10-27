package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Input arrives at the dispatcher totally ordered, one thread and one FIFO drain, and a widget whose state transitions
 * depend on that order must see it.
 *
 * <p>In 0.1 this was the one test that ran handlers on {@code Gui}'s real worker pool, because handing each event to a
 * pool separately ordered the invocations but not their effects, and a typed burst could apply scrambled. 0.2 has no
 * pool: handlers run in the frame, on the GUI thread (vexelray-framework's {@code docs/threading.md}, T1.1). The
 * property is kept, because it is the one an editor is judged by, and it is now held on the only path there is.
 */
class HandlerOrderingTest {

    /** Long enough that a single reordered pair anywhere in the burst fails the assertion. */
    private static final int BURST = 64;

    @Test
    void aBurstOfTypedCharactersLandsInTheOrderItWasTyped() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "");
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());

            String typed = burst();
            h.type(typed);

            assertEquals(typed, f.text(), "typed characters are delivered in order and must be applied in order");
        }
    }

    /** The same property for edit commands: a run of backspaces consumes the document from the right, one each. */
    @Test
    void aBurstOfBackspacesRemovesExactlyOneCharacterEach() {
        try (HeadlessGui h = new HeadlessGui()) {
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
}
