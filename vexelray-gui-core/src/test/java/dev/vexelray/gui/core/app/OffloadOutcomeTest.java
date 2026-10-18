package dev.vexelray.gui.core.app;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every outcome of offloaded work reaches exactly one of {@code landed} and {@code failed}.
 *
 * <p>The lane side caught {@code Exception}, so an {@code Error} thrown by the work reached neither, and a UI
 * waiting on the result waited forever. {@code offload} itself needs the {@code Gui} that {@code run()} binds,
 * which needs a window; the lane-side task is run here directly, with a list standing in for the posted queue.
 */
final class OffloadOutcomeTest {

    private final List<Runnable> posted = new ArrayList<>();
    private final List<String> landed = new ArrayList<>();
    private final List<Exception> failed = new ArrayList<>();

    private Runnable task(java.util.concurrent.Callable<String> work) {
        return GuiApp.offloaded(work, landed::add, failed::add, posted::add);
    }

    /** Run what the lane posted, as the GUI thread would. */
    private void drain() {
        for (Runnable r : posted) {
            r.run();
        }
        posted.clear();
    }

    @Test
    void aValueLands() {
        task(() -> "made").run();
        drain();

        assertEquals(List.of("made"), landed);
        assertTrue(failed.isEmpty(), failed.toString());
    }

    @Test
    void anExceptionFailsAsItself() {
        java.io.IOException thrown = new java.io.IOException("no such file");
        task(() -> { throw thrown; }).run();
        drain();

        assertTrue(landed.isEmpty(), landed.toString());
        assertEquals(1, failed.size());
        assertSame(thrown, failed.get(0));
    }

    @Test
    void anErrorReachesFailed() {
        AssertionError thrown = new AssertionError("a parse that was sure of itself");
        // Not a VirtualMachineError, so it stops at failed rather than also ending the pool thread.
        task(() -> { throw thrown; }).run();
        drain();

        assertTrue(landed.isEmpty(), landed.toString());
        assertEquals(1, failed.size(), "the error reached neither callback");
        Exception e = failed.get(0);
        assertTrue(e instanceof ExecutionException, e.toString());
        assertSame(thrown, e.getCause());
        // A caller that only prints the message still learns what kind of thing went wrong.
        assertTrue(e.getMessage().contains("AssertionError"), e.getMessage());
    }

    @Test
    void aVirtualMachineErrorIsDeliveredAndThenRethrown() {
        StackOverflowError thrown = new StackOverflowError();
        Runnable task = task(() -> { throw thrown; });

        // Rethrown on the lane, so the pool thread's uncaught handler sees it rather than nobody...
        assertSame(thrown, assertThrows(StackOverflowError.class, task::run));
        // ...and posted first, so the request it ended is still answered.
        drain();
        assertTrue(landed.isEmpty(), landed.toString());
        assertEquals(1, failed.size(), "the error reached neither callback");
        assertSame(thrown, failed.get(0).getCause());
    }
}
