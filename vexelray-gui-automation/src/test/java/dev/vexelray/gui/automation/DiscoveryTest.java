package dev.vexelray.gui.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery file (docs/plans/discovery.md): what it says, that its lock is held while it exists, that it goes
 * when the server does, and {@code hello}.
 */
class DiscoveryTest {

    private static Map<String, String> read(Path file) throws Exception {
        Map<String, String> facts = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            facts.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return facts;
    }

    @Test
    void theFileSaysWhoIsListeningWhereAndHoldsItsLock(@TempDir Path folder) throws Exception {
        try (Discovery d = Discovery.publish(folder, "Calculator", "The Calculator", 50845)) {
            assertNotNull(d);
            long pid = ProcessHandle.current().pid();
            Path info = folder.resolve("calculator-" + pid + ".auto");
            assertEquals(info, d.file());
            Map<String, String> facts = read(info);
            assertEquals(List.of("protocol", "app", "title", "pid", "started", "port", "cwd", "command", "build"),
                    new ArrayList<>(facts.keySet()), "the facts, in the order discovery.md gives them");
            assertEquals(AutomationServer.PROTOCOL, facts.get("protocol"));
            assertEquals("Calculator", facts.get("app"));
            assertEquals("The Calculator", facts.get("title"));
            assertEquals(Long.toString(pid), facts.get("pid"));
            assertEquals("50845", facts.get("port"));
            assertEquals(Path.of("").toAbsolutePath().toString(), facts.get("cwd"));
            assertFalse(Files.exists(folder.resolve("calculator-" + pid + ".auto.tmp")), "moved into place, not left");

            // The lock is held: another taker in this JVM is refused, which is what a reader in another process sees
            // as a lock it cannot take.
            Path lock = folder.resolve("calculator-" + pid + ".lock");
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                assertThrows(OverlappingFileLockException.class, ch::tryLock);
            }
        }
        try (var left = Files.list(folder)) {
            assertEquals(List.of(), left.toList(), "closing removes the file and its lock");
        }
    }

    @Test
    void liveListsWhatIsRunningAndClearsWhatIsNot(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("gone-1.auto"), "app=gone\npid=1\nport=9\n");
        Files.writeString(folder.resolve("gone-1.lock"), "");
        Files.writeString(folder.resolve("gone-1.sock"), "");
        try (Discovery d = Discovery.publish(folder, "calc", "Calc", 50845)) {
            d.advertise("stream", "uds:C:\\x\\calc.sock");
            List<Discovery.Entry> live = Discovery.live(folder);
            assertEquals(1, live.size());
            Discovery.Entry e = live.getFirst();
            assertEquals("calc", e.app());
            assertEquals(50845, e.port());
            assertEquals(ProcessHandle.current().pid(), e.pid());
            assertEquals("uds:C:\\x\\calc.sock", e.stream(), "advertised after publishing, and read back");
            assertEquals("calc-" + ProcessHandle.current().pid(), e.base());
            assertFalse(Files.exists(folder.resolve("gone-1.auto")), "the stale one is cleared");
            assertFalse(Files.exists(folder.resolve("gone-1.sock")), "with its link socket");
        }
        assertEquals(List.of(), Discovery.live(folder));
    }

    @Test
    void aNameIsMadeSafeForEveryFileSystem() {
        assertEquals("calculator", Discovery.fileName("Calculator"));
        assertEquals("my-app-2", Discovery.fileName("My App 2!"));
        assertEquals("app", Discovery.fileName("..."));
        assertEquals("app", Discovery.fileName(null));
    }

    @Test
    void theFolderIsVexelrayHomesAutomation() {
        // The surefire run sets -Dvexelray.home under target, so this test also proves a run leaves no files in the
        // real home. The environment variable, when someone has set it, wins over both.
        if (System.getenv(Discovery.HOME_ENV) == null) {
            assertEquals(Path.of(System.getProperty("vexelray.home")).toAbsolutePath().normalize().resolve("automation"),
                    Discovery.folder());
        }
    }

    @Test
    void aServerIsDiscoverableWhileItRunsAndHelloSaysWhatItOffers() throws Exception {
        Path file;
        try (AutomationServer server = AutomationServer.start(line -> "ok " + line, 0, "probe-test", "Probe Test")) {
            file = server.discoveryFile();
            assertNotNull(file, "written before start returns, so before the caller prints its port");
            assertEquals(Integer.toString(server.port()), read(file).get("port"));
            assertEquals("probe-test", read(file).get("app"));
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                s.setSoTimeout(5_000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                out.println("hello");
                out.flush();
                List<String> reply = new ArrayList<>();
                for (String l; (l = in.readLine()) != null && !l.equals("."); ) {
                    reply.add(l);
                }
                assertTrue(reply.getFirst().startsWith("ok protocol=" + AutomationServer.PROTOCOL + " app=probe-test pid="
                        + ProcessHandle.current().pid() + " port=" + server.port() + " connections=1/4 hand=free this=1"
                        + " file=" + file), reply.getFirst());
                assertEquals("see     help", reply.getLast());
            }
        }
        assertFalse(Files.exists(file), "closing the server removes its file");
    }

    @Test
    void anUnbuiltDomainSaysWhatItWaitsForInTheSameWordsHelloUses() {
        assertEquals("err not available yet: bus needs the atchung topic registry and tap", Domains.answer("bus"));
        assertTrue(Domains.line().contains(" bus=missing(the atchung topic registry and tap)"), Domains.line());
        for (String verb : Domains.MISSING.keySet()) {
            assertFalse(Automation.driving(verb, ""), verb + " reads");
        }
    }
}
