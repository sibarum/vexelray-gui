package dev.vexelray.gui.core.app;

import dev.vexelray.diag.Diagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming what held the GUI thread, which is the hard half of reporting it.
 *
 * <p>The posted-task queue is where an application's structural work runs, on the thread that also draws, and
 * it is the easiest place on this stack to write an ordinary blocking call and have nothing object — the text
 * editor read and wrote files here, and the only reason anybody found out was a port that went looking.
 *
 * <p>A report that said only how long it took would leave the reader guessing, which is the state this is
 * supposed to end. A {@code Runnable} has no name, but the class of a lambda is derived from the class that
 * <em>created</em> it, so the one fact worth printing is recoverable. <b>That derivation is a JDK detail</b>,
 * which is exactly why it is pinned here: if a future release changes how lambda classes are named, this fails
 * rather than the warnings quietly turning into addresses nobody can follow.
 */
final class PostedStallTest {

    @BeforeEach
    @AfterEach
    void silence() {
        Diagnostics.reset();
    }

    @Test
    void aStalledTaskIsAttributedToTheClassThatPostedIt() {
        GuiApp.reportStall(() -> { }, TimeUnit.MILLISECONDS.toNanos(400));

        List<String> said = Diagnostics.recorded();
        assertEquals(1, said.size(), "expected one report, got " + said);
        String message = said.get(0);

        assertTrue(message.contains(PostedStallTest.class.getName()),
                "the report did not name the class that posted the task: " + message);
        // The raw lambda class name ends in something like $$Lambda/0x00007f..., which is noise at best and
        // at worst reads as a corrupted message. The address is the class; the rest is the JVM's bookkeeping.
        assertFalse(message.contains("$$Lambda"), "the lambda's internal name leaked into the report: " + message);
        assertTrue(message.contains("400 ms"), message);
        // Ends in a fix rather than a diagnosis. This is the line that makes the difference between a warning
        // somebody acts on and one they read twice and scroll past.
        assertTrue(message.contains("offload"), message);
    }

    @Test
    void anOrdinaryClassIsNamedAsItself() {
        // Not everything posted is a lambda, and a named Runnable should survive untouched.
        GuiApp.reportStall(new SlowThing(), TimeUnit.MILLISECONDS.toNanos(300));

        String message = Diagnostics.recorded().get(0);
        assertTrue(message.contains(SlowThing.class.getName()), message);
    }

    @Test
    void oneSourceIsReportedOnce() {
        for (int i = 0; i < 20; i++) {
            GuiApp.reportStall(new SlowThing(), TimeUnit.MILLISECONDS.toNanos(300));
        }

        // A task that blocks once blocks every time it runs, so this would otherwise be twenty lines a second
        // — and a channel that floods gets filtered, taking the next real warning with it.
        assertEquals(1, Diagnostics.recorded().size(), Diagnostics.recorded().toString());
    }

    private static final class SlowThing implements Runnable {
        @Override
        public void run() {
        }
    }
}
