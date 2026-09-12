package dev.vexelray.gui.automation.cli;

import java.util.concurrent.TimeUnit;

/**
 * A child process that behaves like a driveable application at the two points {@code --launch} cares about:
 * it announces a port on stdout the way {@code Driver.open} does, and it does not exit on its own.
 *
 * <p>A real process rather than a stub, because everything {@code Launch} can get wrong is about processes —
 * that the port line is read from a pipe before the child has finished starting, that the pipe is drained so
 * a chatty application cannot fill its buffer and stall, and that the child is actually gone afterwards. None
 * of that is exercised by anything smaller.
 */
public final class LaunchedApplication {

    /** Long enough for any test to finish driving it; a backstop against leaking one if a test dies. */
    private static final long LIFETIME_MINUTES = 2;

    private LaunchedApplication() {
    }

    /**
     * @param args {@code --mute} to start, say something that is not a port line, and exit — the application
     *             that was launched without being asked for a socket
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--mute")) {
            System.out.println("starting, and saying nothing about any socket");
            System.out.flush();
            return;
        }
        // Echoes the command back so a test can tell this application's replies from any other's, and
        // refuses anything beginning "fail" so a launched run has a way to produce a genuine err.
        try (FakeApplication app = FakeApplication.answering(
                c -> c.startsWith("fail") ? "err refused " + c : "ok " + c)) {
            System.out.println("automation: localhost:" + app.port());
            System.out.flush();
            TimeUnit.MINUTES.sleep(LIFETIME_MINUTES);
        }
    }
}
