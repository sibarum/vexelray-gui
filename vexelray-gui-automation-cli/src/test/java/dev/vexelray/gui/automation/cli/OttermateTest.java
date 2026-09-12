package dev.vexelray.gui.automation.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict, end to end against a fake application.
 *
 * <p>{@code R5} is the requirement under test and the reason the tool has an exit status at all: a run that
 * returns 0 after a failed {@code shot} is a script reporting success for a picture nobody took, which is the
 * same defect {@code --capture} is being retired for.
 */
class OttermateTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void aRunThatAnsweredOkExitsZero() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> "ok C:\\tmp\\after.png 41231 bytes")) {
            assertEquals(Session.OK, run(app, "shot", "after.png"));
            assertEquals("ok C:\\tmp\\after.png 41231 bytes", stdout().strip(),
                    "a one-off prints the reply and nothing else: the command is still on screen above it, "
                            + "and echoing it back is noise a pipe would have to strip");
        }
    }

    @Test
    void aScriptEchoesEachCommandAboveItsReply(@TempDir Path dir) throws IOException {
        // The other half of the same judgement. A ladder's output read six months later is unattributable
        // without it: seven "ok" lines say nothing about which scene each one was.
        Path script = write(dir, "settle", "shot after.png");
        try (FakeApplication app = FakeApplication.answering(c -> "ok")) {
            assertEquals(Session.OK, runScript(app, script));
            assertTrue(stdout().contains("> settle"), stdout());
            assertTrue(stdout().contains("> shot after.png"), stdout());
        }
    }

    @Test
    void aRunThatAnsweredErrExitsOne() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> "err no picture appeared at after.png")) {
            assertEquals(Session.FAILED, run(app, "shot", "after.png"));
            assertTrue(stdout().contains("err no picture appeared"),
                    "the failure is printed as well as counted: " + stdout());
        }
    }

    @Test
    void aSettleThatTimedOutFailsTheRun() throws IOException {
        // Automation.settle says so rather than swallowing it, and this is the half of that promise that
        // reaches a shell. A ladder whose settle timed out photographed a tree mid-transition.
        try (FakeApplication app = FakeApplication.answering(
                c -> c.equals("settle") ? "err did not settle within 2000ms (frame still owed)" : "ok")) {
            assertEquals(Session.FAILED, run(app, "settle"));
        }
    }

    @Test
    void aScriptStopsAtTheFirstErr(@TempDir Path dir) throws IOException {
        Path script = write(dir, "find Save", "click 41", "settle", "shot after.png");
        try (FakeApplication app = failingOn("click 41")) {
            assertEquals(Session.FAILED, runScript(app, script));
            assertEquals(List.of("find Save", "click 41", "quit"), app.received(),
                    "settle and shot assumed the click landed; running them anyway photographs a window "
                            + "nobody put in that state");
            assertTrue(stderr().contains("--keep-going"), "the way to override it is worth naming: " + stderr());
        }
    }

    @Test
    void keepGoingRunsTheRestAndStillFails(@TempDir Path dir) throws IOException {
        Path script = write(dir, "find Save", "click 41", "shot after.png");
        try (FakeApplication app = failingOn("click 41")) {
            assertEquals(Session.FAILED,
                    Ottermate.run(new String[]{"--keep-going", "--port", port(app), "--script", script.toString()},
                            new PrintStream(out, true, StandardCharsets.UTF_8),
                            new PrintStream(err, true, StandardCharsets.UTF_8)));
            assertEquals(List.of("find Save", "click 41", "shot after.png", "quit"), app.received());
        }
    }

    @Test
    void blankLinesAndCommentsNeverReachTheApplication(@TempDir Path dir) throws IOException {
        Path script = write(dir, "# the smallest tree it will lay out", "", "  ", "shot smallest.png");
        try (FakeApplication app = FakeApplication.answering(c -> "ok")) {
            assertEquals(Session.OK, runScript(app, script));
            assertEquals(List.of("shot smallest.png", "quit"), app.received());
        }
    }

    @Test
    void quitInAScriptEndsTheSessionWithoutWaitingForAReply(@TempDir Path dir) throws IOException {
        // The server answers quit by hanging up, so a client that sent it and then read would wait out its
        // whole timeout for a reply the protocol says will not come.
        Path script = write(dir, "shot a.png", "quit", "shot b.png");
        try (FakeApplication app = FakeApplication.answering(c -> "ok")) {
            assertEquals(Session.OK, runScript(app, script));
            assertEquals(List.of("shot a.png", "quit"), app.received());
        }
    }

    @Test
    void quietPrintsFailuresAndNothingElse(@TempDir Path dir) throws IOException {
        Path script = write(dir, "settle", "shot after.png");
        try (FakeApplication app = FakeApplication.answering(
                c -> c.startsWith("shot") ? "err no picture appeared" : "ok v41")) {
            assertEquals(Session.FAILED,
                    Ottermate.run(new String[]{"--quiet", "--port", port(app), "--script", script.toString()},
                            new PrintStream(out, true, StandardCharsets.UTF_8),
                            new PrintStream(err, true, StandardCharsets.UTF_8)));
            assertTrue(stdout().contains("err no picture appeared"), stdout());
            assertFalse(stdout().contains("ok v41"), "quiet means only failures: " + stdout());
        }
    }

    @Test
    void commandsCanComeFromStandardInput() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> "ok")) {
            withStdin("settle\nshot after.png\n", () -> {
                assertEquals(Session.OK, Ottermate.run(new String[]{"--port", port(app)},
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8)));
            });
            assertEquals(List.of("settle", "shot after.png", "quit"), app.received());
        }
    }

    @Test
    void anApplicationThatWentAwayMidRunIsItsOwnStatus(@TempDir Path dir) throws IOException {
        Path script = write(dir, "click 41", "shot after.png");
        try (FakeApplication app = FakeApplication.answering(c -> c.equals("click 41") ? "ok" : null)) {
            assertEquals(Session.DROPPED, runScript(app, script),
                    "an application that crashed is a different finding from one that said no");
        }
    }

    @Test
    void nothingListeningIsItsOwnStatus() {
        assertEquals(Session.UNREACHABLE,
                Ottermate.run(new String[]{"--port", "1", "--timeout", "1", "tree"},
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertTrue(stderr().contains("--automation="), stderr());
    }

    @Test
    void aCommandLineThatCannotBeActedOnIsItsOwnStatus() {
        assertEquals(Session.USAGE,
                Ottermate.run(new String[]{"--potr", "7654"},
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertTrue(stderr().contains("--script"), "a usage error prints the usage: " + stderr());
    }

    @Test
    void aMissingScriptIsAUsageErrorAndNotAConnectionOne(@TempDir Path dir) {
        assertEquals(Session.USAGE,
                Ottermate.run(new String[]{"--script", dir.resolve("nope.txt").toString()},
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8)));
    }

    @Test
    void helpNamesTheStatusesItCanReturn() {
        assertEquals(Session.OK, Ottermate.run(new String[]{"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertTrue(stdout().contains("exit status"), stdout());
        assertTrue(stdout().contains("--launch"), stdout());
    }

    // --- plumbing ----------------------------------------------------------

    private static FakeApplication failingOn(String command) throws IOException {
        return FakeApplication.answering(c -> c.equals(command) ? "err nothing there" : "ok");
    }

    private int run(FakeApplication app, String... command) {
        String[] args = new String[command.length + 2];
        args[0] = "--port";
        args[1] = port(app);
        System.arraycopy(command, 0, args, 2, command.length);
        return Ottermate.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int runScript(FakeApplication app, Path script) {
        return Ottermate.run(new String[]{"--port", port(app), "--script", script.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static String port(FakeApplication app) {
        return String.valueOf(app.port());
    }

    private static Path write(Path dir, String... lines) throws IOException {
        Path script = dir.resolve("scene.txt");
        Files.write(script, List.of(lines), StandardCharsets.UTF_8);
        return script;
    }

    private static void withStdin(String text, Runnable body) {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        try {
            body.run();
        } finally {
            System.setIn(original);
        }
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
