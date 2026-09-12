package dev.vexelray.gui.automation.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starting an application, learning its port from what it says, and taking it down again.
 *
 * <p>This is the affordance that made {@code --capture} attractive, and the reason retiring it is not a
 * regression in convenience: one command line in, pictures out.
 */
class LaunchTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    @Timeout(60)
    void launchesDrivesAndShutsDown() {
        assertEquals(Session.OK, ottermate(driveWith("shot after.png")));
        assertTrue(stdout().contains("ok shot after.png"),
                "the reply came from the launched application, so the port was read from its own output: "
                        + stdout());
        assertTrue(stderr().contains("automation: localhost:"),
                "the child's output is relayed to stderr, which is where a failed launch is diagnosed: "
                        + stderr());
    }

    @Test
    @Timeout(60)
    void theVerdictSurvivesALaunchedRun() {
        // The whole point of R5 reaching this far: `ottermate --launch ...` in a build script has to fail the
        // build when the application refused, not merely when it would not start.
        assertEquals(Session.FAILED, ottermate(driveWith("fail on purpose")));
    }

    @Test
    @Timeout(60)
    void anApplicationThatAnnouncesNoPortFailsAtOnceRatherThanOnTheTimeout() {
        long started = System.nanoTime();
        List<String> args = new ArrayList<>(List.of("--launch-timeout", "45", "tree", "--launch"));
        args.addAll(child("--mute"));
        assertEquals(Session.UNREACHABLE, ottermate(args));
        long tookSeconds = (System.nanoTime() - started) / 1_000_000_000L;
        assertTrue(tookSeconds < 40, "the child's output ended, so nothing further can be announced and "
                + "waiting out the timeout would only delay the same answer; took " + tookSeconds + "s");
        assertTrue(stderr().contains("--automation=0"),
                "an application that announced nothing was nearly always launched without being asked for a "
                        + "socket, so the message names the flag: " + stderr());
    }

    @Test
    @Timeout(60)
    void aCommandThatCannotBeStartedIsUnreachableAndNotACrash() {
        assertEquals(Session.UNREACHABLE,
                ottermate(List.of("tree", "--launch", "no-such-program-anywhere-on-this-machine")));
    }

    // --- plumbing ----------------------------------------------------------

    /** {@code ottermate <command> --launch java -cp <tests> LaunchedApplication}. */
    private List<String> driveWith(String command) {
        List<String> args = new ArrayList<>(List.of(command, "--launch"));
        args.addAll(child());
        return args;
    }

    /**
     * This JVM, running the fake application off this test run's own classpath. Taking the executable from
     * {@code java.home} and the classpath from the running process is what keeps this from being a statement
     * about how the machine's {@code PATH} happens to be set.
     */
    private static List<String> child(String... childArgs) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString(),
                "-cp", System.getProperty("java.class.path"),
                LaunchedApplication.class.getName()));
        command.addAll(List.of(childArgs));
        return command;
    }

    private int ottermate(List<String> args) {
        return Ottermate.run(args.toArray(String[]::new),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
