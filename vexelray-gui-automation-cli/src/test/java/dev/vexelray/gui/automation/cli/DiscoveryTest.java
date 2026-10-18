package dev.vexelray.gui.automation.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the discovery folder (vexelray-gui {@code docs/plans/discovery.md}): which files are live, that stale
 * ones are cleared, and which application a command line attaches to.
 *
 * <p>The files here are written the way {@code dev.vexelray.gui.automation.Discovery} writes them — that class's
 * own test checks the same layout from the other side — and a live one is kept live by holding its lock in this
 * JVM, which is what the reader sees of a lock held by another process.
 */
class DiscoveryTest {

    /** Write an application's file and its lock; hold the lock when {@code live}. Returns the lock, or null. */
    private static FileLock app(Path folder, String app, long pid, int port, String protocol, boolean live)
            throws Exception {
        Files.createDirectories(folder);
        String base = app + "-" + pid;
        Path lock = folder.resolve(base + ".lock");
        Files.writeString(folder.resolve(base + ".auto"), String.join("\n",
                "protocol=" + protocol, "app=" + app, "title=" + app, "pid=" + pid,
                "started=2026-10-17T19:0" + (pid % 10) + ":00Z", "port=" + port, "cwd=C:\\work\\" + app,
                "command=java -Dautomation=0 x=y", "build=vexelray-gui-automation -") + "\n");
        if (!live) {
            Files.writeString(lock, "");
            return null;
        }
        FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return ch.lock();
    }

    private static void release(FileLock... locks) throws Exception {
        for (FileLock l : locks) {
            l.release();
            l.channel().close();
        }
    }

    @Test
    void liveFilesAreListedAndStaleOnesCleared(@TempDir Path folder) throws Exception {
        FileLock calc = app(folder, "calculator", 18244, 50845, "1.0", true);
        FileLock vex = app(folder, "vexplore", 20001, 50900, "1.0", true);
        app(folder, "mainframe", 9999, 51000, "1.0", false);           // its process ended: nobody holds the lock
        Files.writeString(folder.resolve("pix-4242.auto"), "app=pix\nport=1\n");   // no lock file at all
        try {
            List<Discovery.Entry> live = Discovery.live(folder);
            assertEquals(List.of("vexplore", "calculator"), live.stream().map(Discovery.Entry::app).toList(),
                    "oldest first, by started");
            Discovery.Entry c = live.get(1);
            assertEquals(18244, c.pid());
            assertEquals(50845, c.port());
            assertEquals("1", c.major());
            assertEquals("java -Dautomation=0 x=y", c.get("command"), "split on the first = only");
            assertFalse(Files.exists(folder.resolve("mainframe-9999.auto")), "a stale file is deleted");
            assertFalse(Files.exists(folder.resolve("mainframe-9999.lock")), "with its lock");
            assertFalse(Files.exists(folder.resolve("pix-4242.auto")), "an info file with no lock is stale too");
            assertTrue(Files.exists(folder.resolve("calculator-18244.auto")), "a live one is left alone");
        } finally {
            release(calc, vex);
        }
    }

    @Test
    void aMissingFolderIsNothingRunning(@TempDir Path folder) {
        assertEquals(List.of(), Discovery.live(folder.resolve("nowhere")));
        assertEquals("ok apps=0 folder=" + folder, Discovery.table(List.of(), folder));
    }

    @Test
    void listIsAFirstLineOfFactsAndATable(@TempDir Path folder) throws Exception {
        FileLock calc = app(folder, "calculator", 18244, 50845, "1.0", true);
        try {
            String table = Discovery.table(Discovery.live(folder), folder);
            List<String> lines = table.lines().toList();
            assertEquals("ok apps=1 folder=" + folder, lines.get(0));
            assertTrue(lines.get(1).startsWith("app         pid    port   diagnostics  protocol  started"), lines.get(1));
            // A file from before the split has no diagnostics= line: its one socket serves both.
            assertTrue(lines.get(2).startsWith(
                    "calculator  18244  50845  50845        1.0       2026-10-17T19:04:00Z  C:\\work\\"), lines.get(2));
        } finally {
            release(calc);
        }
    }

    private static Discovery.Entry entry(String app, long pid, int port, String protocol) {
        return new Discovery.Entry(Path.of(app + "-" + pid + ".auto"), Map.of("app", app, "pid", Long.toString(pid),
                "port", Integer.toString(port), "protocol", protocol, "started", "t" + pid));
    }

    private static final Discovery.Entry CALC = entry("calculator", 18244, 50845, "1.0");
    private static final Discovery.Entry CALC2 = entry("calculator", 18300, 50850, "1.0");
    private static final Discovery.Entry VEX = entry("vexplore", 20001, 50900, "1.0");

    private record Run(Ottermate.Target target, String err) {
    }

    private static Run target(List<Discovery.Entry> live, String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Ottermate.Target t = Ottermate.target(Options.parse(args), live,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(t, err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void withNothingSaidTheOneRunningApplicationIsUsedAndNamed() {
        Run r = target(List.of(CALC), "tree");
        assertEquals(new Ottermate.Target(50845, Session.OK), r.target());
        assertTrue(r.err().contains("driving calculator (pid 18244, port 50845)"), r.err());
        assertEquals("", target(List.of(CALC), "--quiet", "tree").err(), "--quiet keeps it to err replies");
    }

    @Test
    void diagnosticsChoosesTheDiagnosticsSocketAndSaysWhenItIsOff() {
        Discovery.Entry split = new Discovery.Entry(Path.of("calculator-18244.auto"), Map.of("app", "calculator",
                "pid", "18244", "port", "50845", "control", "50845", "diagnostics", "50846", "protocol", "1.1",
                "started", "t"));
        Discovery.Entry off = new Discovery.Entry(Path.of("calculator-18244.auto"), Map.of("app", "calculator",
                "pid", "18244", "port", "50845", "control", "50845", "diagnostics", "off", "protocol", "1.1",
                "started", "t"));
        assertEquals(new Ottermate.Target(50846, Session.OK), target(List.of(split), "--diagnostics", "recorder").target());
        assertEquals(new Ottermate.Target(50845, Session.OK), target(List.of(split), "tree").target());
        assertEquals(new Ottermate.Target(50846, Session.OK),
                target(List.of(split, VEX), "--app", "calculator", "--diagnostics", "rows").target());
        assertEquals(new Ottermate.Target(50845, Session.OK), target(List.of(CALC), "--diagnostics", "rows").target(),
                "a server from before the split serves both on its one port");

        Run r = target(List.of(off), "--diagnostics", "recorder");
        assertEquals(Session.UNREACHABLE, r.target().status());
        assertTrue(r.err().contains("has diagnostics off; switch it on from the automation button"), r.err());
        assertEquals(Session.UNREACHABLE, target(List.of(), "--diagnostics", "recorder").target().status());
        assertThrows(UsageException.class, () -> Options.parse(new String[] {"--diagnostics", "--port", "1", "rows"}));
    }

    @Test
    void withNothingSaidAndNothingRunningItIsTheDefaultPort() {
        assertEquals(new Ottermate.Target(AutomationClient.DEFAULT_PORT, Session.OK), target(List.of(), "tree").target());
    }

    @Test
    void withNothingSaidSeveralRunningIsARefusalListingThem() {
        Run r = target(List.of(CALC, VEX), "tree");
        assertEquals(Session.USAGE, r.target().status());
        assertTrue(r.err().contains("2 applications are running; choose one with --app or --pid"), r.err());
        assertTrue(r.err().contains("ottermate --pid 18244    calculator (pid 18244, port 50845)"), r.err());
        assertTrue(r.err().contains("ottermate --pid 20001    vexplore (pid 20001, port 50900)"), r.err());
    }

    @Test
    void appChoosesByNameAndPidByProcess() {
        assertEquals(new Ottermate.Target(50900, Session.OK), target(List.of(CALC, VEX), "--app", "vexplore", "tree").target());
        assertEquals(new Ottermate.Target(50845, Session.OK), target(List.of(CALC, VEX), "--app", "Calculator", "tree").target());
        assertEquals(new Ottermate.Target(50850, Session.OK), target(List.of(CALC, CALC2, VEX), "--pid", "18300", "tree").target());
    }

    @Test
    void appMatchingTwoIsARefusalNamingThePidForEach() {
        Run r = target(List.of(CALC, CALC2), "--app", "calculator", "tree");
        assertEquals(Session.USAGE, r.target().status());
        assertTrue(r.err().contains("2 running applications match --app calculator; choose one"), r.err());
        assertTrue(r.err().contains("ottermate --pid 18300"), r.err());
    }

    @Test
    void appMatchingNothingIsNothingToDrive() {
        Run r = target(List.of(CALC), "--app", "pix", "tree");
        assertEquals(Session.UNREACHABLE, r.target().status());
        assertTrue(r.err().contains("no running application matches --app pix; 'ottermate list' shows what is running"),
                r.err());
    }

    @Test
    void anUnknownProtocolMajorIsRefusedBeforeAnythingIsSent() {
        Discovery.Entry future = entry("calculator", 18244, 50845, "2.3");
        Run r = target(List.of(future), "tree");
        assertEquals(Session.UNREACHABLE, r.target().status());
        assertTrue(r.err().contains("speaks protocol 2.3; this ottermate speaks 1.x, so it sends nothing rather than guess"),
                r.err());
        assertEquals(Session.UNREACHABLE, target(List.of(future), "--port", "50845", "tree").target().status(),
                "a port given outright is checked too, when its file says what it speaks");
        assertEquals(new Ottermate.Target(50845, Session.OK), target(List.of(), "--port", "50845", "tree").target(),
                "and taken on trust when there is no file");
    }
}
