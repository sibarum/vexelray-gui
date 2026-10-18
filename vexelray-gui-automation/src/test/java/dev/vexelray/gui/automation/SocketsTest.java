package dev.vexelray.gui.automation;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control and diagnostics switched on and off while the process runs: one socket when they share a port, two when
 * they do not, each refusing the other's verbs with where they are, and one discovery file that follows.
 */
class SocketsTest {

    /** {@code recorder} is diagnostics; {@code click} drives; everything answers with its own line. */
    private static final Commands VERBS = new Commands() {
        @Override
        public String command(String line) {
            return "ok " + line;
        }

        @Override
        public boolean drives(String line) {
            return line.startsWith("click");
        }

        @Override
        public Capability needs(String line) {
            return line.startsWith("recorder") ? Capability.DIAGNOSTICS : Capability.CONTROL;
        }
    };

    private static Sockets sockets() {
        return new Sockets(VERBS, "sockets-test", "Sockets Test", null);
    }

    @Test
    void sharingAPortIsOneSocketServingBoth() throws Exception {
        try (Sockets s = sockets()) {
            int control = s.enable(Capability.CONTROL, 0);
            assertEquals("err recorder is diagnostics, and this socket serves control; diagnostics is off",
                    ask(control, "recorder"));
            assertEquals("ok click save", ask(control, "click save"));

            assertEquals(control, s.enable(Capability.DIAGNOSTICS, control));
            assertSame(s.server(Capability.CONTROL), s.server(Capability.DIAGNOSTICS));
            assertEquals("ok recorder", ask(control, "recorder"));
            assertTrue(ask(control, "hello").contains(" serves=control,diagnostics "));
            assertEquals("control=localhost:" + control + " diagnostics=localhost:" + control, s.describe());
        }
    }

    @Test
    void switchingOneOffNarrowsASharedSocketRatherThanClosingIt() throws Exception {
        try (Sockets s = sockets()) {
            int port = s.enable(Capability.CONTROL, 0);
            s.enable(Capability.DIAGNOSTICS, port);
            s.disable(Capability.CONTROL);
            assertEquals(port, s.port(Capability.DIAGNOSTICS));
            assertEquals(-1, s.port(Capability.CONTROL));
            assertEquals("ok recorder", ask(port, "recorder"));
            assertEquals("err click is control, and this socket serves diagnostics; control is off",
                    ask(port, "click save"));
            assertEquals("err hand is control, and this socket serves diagnostics; control is off",
                    ask(port, "hand take"));
        }
    }

    @Test
    void separatePortsAreTwoSocketsAndEachRefusalSaysWhereTheOtherIs() throws Exception {
        try (Sockets s = sockets()) {
            int control = s.enable(Capability.CONTROL, 0);
            int diagnostics = s.enable(Capability.DIAGNOSTICS, 0);
            assertNotEquals(control, diagnostics);
            assertEquals("err recorder is diagnostics, and this socket serves control; diagnostics is on localhost:"
                    + diagnostics, ask(control, "recorder"));
            assertEquals("err click is control, and this socket serves diagnostics; control is on localhost:"
                    + control, ask(diagnostics, "click save"));

            // Moved onto the control socket: the diagnostics one closes, nobody serving only it any more.
            assertEquals(control, s.enable(Capability.DIAGNOSTICS, control));
            assertThrows(IOException.class, () -> ask(diagnostics, "hello"));
            assertEquals("ok recorder", ask(control, "recorder"));
        }
    }

    @Test
    void theDiscoveryFileFollowsTheSwitchesAndGoesWithTheLastSocket() throws Exception {
        Path file;
        try (Sockets s = sockets()) {
            assertNull(s.discoveryFile(), "nothing listens, so nothing is advertised");
            int diagnostics = s.enable(Capability.DIAGNOSTICS, 0);
            file = s.discoveryFile();
            assertNotNull(file);
            Map<String, String> facts = read(file);
            assertEquals(Integer.toString(diagnostics), facts.get("port"), "port= is diagnostics while control is off");
            assertEquals("off", facts.get("control"));
            assertEquals(Integer.toString(diagnostics), facts.get("diagnostics"));

            int control = s.enable(Capability.CONTROL, 0);
            facts = read(file);
            assertEquals(Integer.toString(control), facts.get("port"), "port= is the hand's whenever it is on");
            assertEquals(Integer.toString(control), facts.get("control"));

            s.disable(Capability.CONTROL);
            s.disable(Capability.DIAGNOSTICS);
            assertFalse(Files.exists(file), "the last socket closing removes the file");
            s.enable(Capability.CONTROL, 0);
            assertTrue(Files.exists(s.discoveryFile()), "and switching one back on writes it again");
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void aPortThatIsTakenChangesNothing() throws Exception {
        try (Sockets s = sockets(); ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int control = s.enable(Capability.CONTROL, 0);
            assertThrows(IOException.class, () -> s.enable(Capability.CONTROL, taken.getLocalPort()));
            assertEquals(control, s.port(Capability.CONTROL), "the old socket still listens");
            assertEquals("ok click save", ask(control, "click save"));
            assertThrows(IOException.class, () -> s.enable(Capability.DIAGNOSTICS, taken.getLocalPort()));
            assertFalse(s.on(Capability.DIAGNOSTICS));
        }
    }

    @Test
    void anAttachmentLivesExactlyAsLongAsItsCapability() throws Exception {
        AtomicInteger open = new AtomicInteger();
        List<String> changes = new ArrayList<>();
        try (Sockets s = sockets()) {
            s.watch(x -> changes.add(x.describe()));
            s.attach(Capability.DIAGNOSTICS, server -> {
                assertTrue(server.serves().contains(Capability.DIAGNOSTICS));
                open.incrementAndGet();
                return open::decrementAndGet;
            });
            int port = s.enable(Capability.CONTROL, 0);
            assertEquals(0, open.get(), "control alone starts nothing that is diagnostics'");
            s.enable(Capability.DIAGNOSTICS, port);
            assertEquals(1, open.get());
            s.disable(Capability.DIAGNOSTICS);
            assertEquals(0, open.get());
            s.enable(Capability.DIAGNOSTICS, 0);
            assertEquals(1, open.get());
        }
        assertEquals(0, open.get(), "closing the set closes it");
        assertEquals(5, changes.size(), changes.toString());
        assertTrue(changes.getLast().equals("control=off diagnostics=off"), changes.toString());
    }

    @Test
    void aSettingNamesAPortOrOff() {
        assertEquals(-1, Sockets.requested(null));
        assertEquals(-1, Sockets.requested("off"));
        assertEquals(-1, Sockets.requested(" FALSE "));
        assertEquals(AutomationServer.DEFAULT_PORT, Sockets.requested("on"));
        assertEquals(0, Sockets.requested("0"));
        assertEquals(7700, Sockets.requested("7700"));
        assertThrows(NumberFormatException.class, () -> Sockets.requested("wednesday"));
        assertThrows(NumberFormatException.class, () -> Sockets.requested("70000"));
    }

    @Test
    void aLaunchAsksForBothOnOneSocketOrEachOnItsOwn() throws Exception {
        try (Sockets s = sockets()) {
            s.enableRequested(Map.of("automation", "0", "automation.diagnostics", "off")::get);
            assertSame(s.server(Capability.CONTROL), s.server(Capability.DIAGNOSTICS), "automation= wins, one socket");
        }
        try (Sockets s = sockets()) {
            s.enableRequested(Map.of("automation.diagnostics", "0")::get);
            assertFalse(s.on(Capability.CONTROL));
            assertTrue(s.on(Capability.DIAGNOSTICS));
        }
        try (Sockets s = sockets()) {
            s.enableRequested(Map.of("automation.control", "0", "automation.diagnostics", "0")::get);
            assertNotEquals(s.port(Capability.CONTROL), s.port(Capability.DIAGNOSTICS), "0 twice is two free ports");
        }
        try (Sockets s = sockets()) {
            s.enableRequested(Map.<String, String>of()::get);
            assertEquals("control=off diagnostics=off", s.describe());
        }
        assertTrue(Sockets.anyRequested(Map.of("automation.control", "on")::get));
        assertTrue(Sockets.anyRequested(Map.of("automation", "garbage")::get));
        assertFalse(Sockets.anyRequested(Map.of("automation", "off")::get));
    }

    @Test
    void theRealVerbsSplitWhereTheRecorderAndTheLogAre() {
        Windows windows = new Windows(List::of);
        for (String line : List.of("recorder", "rows 5", "gaps", "frames worst 3", "why 12", "stream all for 10",
                "log debug", "mark tried it", "bus", "heap")) {
            assertEquals(Capability.DIAGNOSTICS, windows.needs(line), line);
        }
        for (String line : List.of("tree", "find Save", "click save", "type x", "shot a.png", "settle",
                "await ready yes", "windows", "window 2", "size", "zoom 1.5", "where")) {
            assertEquals(Capability.CONTROL, windows.needs(line), line);
        }
    }

    @Test
    void anUnsplitServerStillServesBoth() throws Exception {
        try (AutomationServer server = AutomationServer.start(VERBS, 0, 4)) {
            assertEquals(Set.of(Capability.CONTROL, Capability.DIAGNOSTICS), server.serves());
            assertEquals("ok recorder", ask(server.port(), "recorder"));
        }
    }

    private static String ask(int port, String line) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            out.println(line);
            out.flush();
            StringBuilder reply = new StringBuilder();
            for (String l; (l = in.readLine()) != null && !l.equals("."); ) {
                reply.append(reply.isEmpty() ? "" : "\n").append(l);
            }
            return reply.toString();
        }
    }

    private static Map<String, String> read(Path file) throws IOException {
        Map<String, String> facts = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            facts.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return facts;
    }
}
