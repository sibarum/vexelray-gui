package dev.vexelray.gui.core.app;

import dev.vexelray.os.Key;
import dev.vexelray.os.NativeWindow;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Close interception: with no handler a close closes, with one the window lives until the application answers,
 * and a window that was destroyed rather than asked is never put to a vote.
 */
class CloseGateTest {

    /** A window that records what was asked of it, and can pretend to have been destroyed. */
    private static final class FakeWindow implements NativeWindow {
        boolean destroyed;
        int cancelCalls;
        int closeRequests;

        @Override
        public boolean cancelClose() {
            cancelCalls++;
            return !destroyed;
        }

        @Override
        public void requestClose() {
            closeRequests++;
        }

        @Override
        public int width() {
            return 0;
        }

        @Override
        public int height() {
            return 0;
        }

        @Override
        public boolean pumpEvents() {
            return true;
        }

        @Override
        public void show() {
            // nothing to show
        }

        @Override
        public boolean isKeyDown(Key key) {
            return false;
        }

        @Override
        public long createVulkanSurface(long vkInstance, MemorySegment vkGetInstanceProcAddr) {
            return 0L;
        }

        @Override
        public long osHandle() {
            return 0L;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    /** Runs handlers when told to, so a test can hold a request open the way a real dialog does. */
    private static final class Later implements Executor {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            List<Runnable> now = List.copyOf(queued);
            queued.clear();
            now.forEach(Runnable::run);
        }
    }

    @Test
    void withNoHandlerACloseCloses() {
        CloseGate gate = new CloseGate();
        FakeWindow window = new FakeWindow();
        assertFalse(gate.keepAlive(window, Runnable::run), "nothing installed: the window closes");
        assertEquals(0, window.cancelCalls, "and the OS close is not even withdrawn");
    }

    @Test
    void aHandlerKeepsTheWindowAliveUntilItAnswers() {
        CloseGate gate = new CloseGate();
        FakeWindow window = new FakeWindow();
        List<CloseRequest> asked = new ArrayList<>();
        gate.handler(asked::add);
        Later later = new Later();

        assertTrue(gate.keepAlive(window, later), "the window keeps running while the question is out");
        assertEquals(1, window.cancelCalls, "the OS-level close was withdrawn");
        later.runAll();
        assertEquals(1, asked.size(), "the application was asked, on the handler executor");
        assertFalse(asked.getFirst().answered());
    }

    @Test
    void proceedClosesTheWindowAndIsNotAskedAgain() {
        CloseGate gate = new CloseGate();
        FakeWindow window = new FakeWindow();
        List<CloseRequest> asked = new ArrayList<>();
        gate.handler(asked::add);

        gate.keepAlive(window, Runnable::run);
        asked.getFirst().proceed();
        assertEquals(1, window.closeRequests, "proceeding re-issues the close through the ordinary route");
        assertNull(gate.pending(), "and nothing is outstanding any more");

        assertFalse(gate.keepAlive(window, Runnable::run), "the close that follows is let through");
        assertEquals(1, asked.size(), "an application that agreed to close is not asked twice");
        assertFalse(gate.intercepts(), "the gate is spent");
    }

    @Test
    void cancelKeepsTheWindowAndTheNextCloseAsksAgain() {
        CloseGate gate = new CloseGate();
        FakeWindow window = new FakeWindow();
        List<CloseRequest> asked = new ArrayList<>();
        gate.handler(asked::add);

        gate.keepAlive(window, Runnable::run);
        asked.getFirst().cancel();
        assertEquals(0, window.closeRequests, "cancelling asks the window for nothing");
        assertNull(gate.pending());

        assertTrue(gate.keepAlive(window, Runnable::run), "the window is still alive to be closed again");
        assertEquals(2, asked.size(), "and the next attempt is a fresh question");
    }

    @Test
    void aSecondCloseWhileAskingDoesNotAskTwice() {
        CloseGate gate = new CloseGate();
        FakeWindow window = new FakeWindow();
        List<CloseRequest> asked = new ArrayList<>();
        gate.handler(asked::add);

        assertTrue(gate.keepAlive(window, Runnable::run));
        assertTrue(gate.keepAlive(window, Runnable::run), "hammering the close button keeps the window alive");
        assertEquals(1, asked.size(), "one conversation, however many times the user clicks");
        assertSame(asked.getFirst(), gate.pending());
    }

    @Test
    void aDestroyedWindowIsNotPutToAVote() {
        CloseGate gate = new CloseGate();
        FakeWindow window = new FakeWindow();
        window.destroyed = true;
        List<CloseRequest> asked = new ArrayList<>();
        gate.handler(asked::add);

        assertFalse(gate.keepAlive(window, Runnable::run), "the window is already gone: let it go");
        assertEquals(List.of(), asked, "and do not ask a question nobody can act on");
    }

    @Test
    void answeringTwiceCountsOnce() {
        CloseGate gate = new CloseGate();
        FakeWindow window = new FakeWindow();
        List<CloseRequest> asked = new ArrayList<>();
        gate.handler(asked::add);
        gate.keepAlive(window, Runnable::run);

        CloseRequest request = asked.getFirst();
        request.cancel();
        request.proceed();
        assertTrue(request.answered());
        assertEquals(0, window.closeRequests, "the first answer is the answer; the second is ignored");
    }

    @Test
    void removingTheHandlerRestoresPlainClosing() {
        CloseGate gate = new CloseGate();
        FakeWindow window = new FakeWindow();
        gate.handler(r -> { });
        assertTrue(gate.intercepts());

        gate.handler(null);
        assertFalse(gate.intercepts());
        assertFalse(gate.keepAlive(window, Runnable::run));
        assertNotNull(window, "and the window closes as it did before interception existed");
    }
}
