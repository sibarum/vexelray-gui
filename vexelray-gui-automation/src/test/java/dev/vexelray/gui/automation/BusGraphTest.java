package dev.vexelray.gui.automation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declared bus graph (vexelray-inspector docs/bus-circuit.md §1 and §4): refused at the door unless it is
 * version 1, and answered by {@code bus graph} line for line, or with the reason there is none.
 */
class BusGraphTest {

    static final String GRAPH = String.join("\n",
            "# vexelray bus graph 1",
            "component  dev.example.Worker      lane=worker",
            "publisher  dev.example.Ui          lane=-",
            "mailbox    dev.example.Worker#onJob  topic=witness.job  type=dev.example.Job  overflow=BLOCK  capacity=8",
            "publishes  dev.example.Ui           topic=witness.job") + "\n";

    @AfterEach
    void forget() {
        BusGraph.clear();
    }

    @Test
    void nothingDeclaredMeansNone() {
        assertEquals(Optional.empty(), BusGraph.text());
        assertTrue(BusGraph.answer().startsWith("err no bus graph: "), BusGraph.answer());
    }

    @Test
    void aVersionOneGraphIsKeptAsGivenWithoutItsTrailingLineEnd() {
        BusGraph.declare(GRAPH);
        assertEquals(Optional.of(GRAPH.stripTrailing()), BusGraph.text());
        assertEquals(5, BusGraph.lines().size());
    }

    @Test
    void anythingButVersionOneIsRefusedWhenDeclared() {
        for (String bad : new String[]{null, "", "component dev.example.Worker lane=worker\n",
                "# vexelray bus graph 2\ncomponent dev.example.Worker lane=worker\n", "# vexelray bus graph 1\r\n",
                "# vexelray bus graph 1\n.\n"}) {
            assertThrows(IllegalArgumentException.class, () -> BusGraph.declare(bad), String.valueOf(bad));
        }
        assertEquals(Optional.empty(), BusGraph.text(), "a refused graph leaves none declared");
    }

    @Test
    void busGraphIsADiagnosticsReadAndTheRestOfBusIsStillMissing() {
        Windows windows = new Windows(List::of);
        assertEquals(Capability.DIAGNOSTICS, windows.needs("bus graph"));
        assertFalse(windows.drives("bus graph"));
        assertTrue(windows.command("bus graph").startsWith("err no bus graph: "));
        assertEquals("err not available yet: bus needs the atchung topic registry and tap", windows.command("bus"));
        assertEquals(Domains.answer("bus"), windows.command("bus tap"));

        BusGraph.declare(GRAPH);
        assertEquals("ok bus graph lines=5\n" + GRAPH.stripTrailing(), windows.command("bus graph"));
        assertEquals(windows.command("bus graph"), windows.command("BUS Graph"));
    }

    @Test
    void helloListsTheGraphOnItsOwnBesideTheMissingBusDomain() {
        Windows windows = new Windows(List::of);
        String domain = domainLine(windows);
        assertTrue(domain.contains(" bus.graph=no bus=missing(the atchung topic registry and tap)"), domain);
        BusGraph.declare(GRAPH);
        assertTrue(domainLine(windows).contains(" bus.graph=yes "), domainLine(windows));
    }

    @Test
    void theSocketAnswersWithTheCountThenTheLines() throws Exception {
        try (AutomationServer server = AutomationServer.start(new Windows(List::of), 0, 2);
             Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            List<String> none = ask(in, out, "bus graph");
            assertEquals(1, none.size(), none.toString());
            assertTrue(none.getFirst().startsWith("err no bus graph: "), none.getFirst());

            BusGraph.declare(GRAPH);
            List<String> reply = ask(in, out, "bus graph");
            assertEquals("ok bus graph lines=5", reply.getFirst());
            assertEquals(List.of(GRAPH.split("\n")), reply.subList(1, reply.size()));
        }
    }

    private static String domainLine(Windows windows) {
        return windows.describe().stream().filter(l -> l.startsWith("domain ")).findFirst().orElseThrow();
    }

    private static List<String> ask(BufferedReader in, PrintWriter out, String line) throws Exception {
        out.println(line);
        out.flush();
        List<String> reply = new ArrayList<>();
        for (String l; (l = in.readLine()) != null && !l.equals("."); ) {
            reply.add(l);
        }
        return reply;
    }
}
