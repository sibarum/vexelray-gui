package dev.vexelray.gui.automation;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many observers, one hand (docs/reference/automation.md §5): several connections read at once, and only the one
 * holding the hand drives.
 *
 * <p>Against a stand-in for the verbs rather than a window, because what is under test is the socket's rule and
 * not any verb: the stand-in classifies lines with the real {@link Automation#driving}, records what it ran, and
 * can hold a {@code move} open for as long as a test needs one in flight.
 */
class AutomationServerTest {

    /** The verbs, as far as the server can tell: real classification, recorded execution. */
    private static final class Recorder implements Commands {
        final List<String> ran = new CopyOnWriteArrayList<>();
        /** The cause each line ran in, beside {@link #ran}. */
        final List<Long> causes = new CopyOnWriteArrayList<>();
        final AtomicInteger letGo = new AtomicInteger();
        /** Counted down when a {@code move} starts; the move then waits for {@link #finish}. */
        volatile CountDownLatch moving = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        volatile boolean holdMoves;

        @Override
        public String command(String line) {
            ran.add(line);
            causes.add(sibarum.probe.Cause.current());
            if (line.startsWith("move") && holdMoves) {
                moving.countDown();
                try {
                    finish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "ok " + line;
        }

        @Override
        public boolean drives(String line) {
            String[] parts = line.trim().split("\\s+", 2);
            return Automation.driving(parts[0], parts.length > 1 ? parts[1] : "");
        }

        @Override
        public void letGo() {
            letGo.incrementAndGet();
        }
    }

    /** One connection, speaking the protocol: a line out, lines in up to the {@code .}. */
    private static final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        Client(AutomationServer server) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
            socket.setSoTimeout(5_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        String ask(String line) throws IOException {
            out.println(line);
            out.flush();
            StringBuilder reply = new StringBuilder();
            String got;
            while ((got = in.readLine()) != null && !got.equals(".")) {
                reply.append(reply.isEmpty() ? "" : "\n").append(got);
            }
            return reply.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * Each command line runs in a cause of its own, so the command and everything it sets off share an ID; and the
     * connection's thread is left in none between commands.
     */
    @Test
    void eachCommandIsACauseOfItsOwn() throws Exception {
        Recorder verbs = new Recorder();
        try (AutomationServer server = AutomationServer.start(verbs, 0, 4);
             Client a = new Client(server)) {
            a.ask("click 10,10");
            a.ask("type hi");
        }
        assertEquals(2, verbs.causes.size(), verbs.ran.toString());
        long first = verbs.causes.get(0);
        long second = verbs.causes.get(1);
        assertEquals(sibarum.probe.Cause.Kind.COMMAND, sibarum.probe.Cause.kindOf(first));
        assertEquals(sibarum.probe.Cause.sourceOf(first), sibarum.probe.Cause.sourceOf(second),
                "one source, the server's");
        assertEquals(sibarum.probe.Cause.sequenceOf(first) + 1, sibarum.probe.Cause.sequenceOf(second),
                "and the next command is the next cause");
    }

    @Test
    void twoConnectionsCannotInterleaveInput() throws Exception {
        Recorder verbs = new Recorder();
        try (AutomationServer server = AutomationServer.start(verbs, 0, 4);
             Client a = new Client(server);
             Client b = new Client(server)) {
            assertEquals("ok click 10,10", a.ask("click 10,10"));

            // Every input verb from the other connection is refused, and the refusal says whose hand it is.
            for (String input : List.of("move 5,5", "click 5,5", "rightclick 5,5", "drag 1,1 2,2", "down 1,1", "up",
                    "scroll 1,1 0 1", "type hi", "key ENTER", "go save", "zoom 2", "dpi 2", "resize 800x600")) {
                String reply = b.ask(input);
                assertTrue(reply.startsWith("err the hand is held by connection 1 (since +"), input + ": " + reply);
            }
            assertTrue(b.ask("hand take").startsWith("err the hand is held by connection 1"));
            assertTrue(b.ask("hand release").startsWith("err the hand is held by connection 1"),
                    "a connection cannot let go of a hand it does not hold");
            assertEquals(List.of("click 10,10"), verbs.ran, "nothing the refused connection asked for ran");

            // The holder keeps driving undisturbed.
            assertEquals("ok type more", a.ask("type more"));
            assertTrue(a.ask("hand").contains("(this one)"));
        }
    }

    @Test
    void aReadWorksOnANonHolderWhileTheHolderDrives() throws Exception {
        Recorder verbs = new Recorder();
        verbs.holdMoves = true;
        try (AutomationServer server = AutomationServer.start(verbs, 0, 4);
             Client a = new Client(server);
             Client b = new Client(server)) {
            CompletableFuture<String> moving = CompletableFuture.supplyAsync(() -> {
                try {
                    return a.ask("move 300,200");
                } catch (IOException e) {
                    return "io " + e;
                }
            });
            assertTrue(verbs.moving.await(5, TimeUnit.SECONDS), "the holder's move started");

            // While the move is in flight: reads answer, the view verbs read without a value, and input is refused.
            assertEquals("ok tree", b.ask("tree"));
            assertEquals("ok where", b.ask("where"));
            assertEquals("ok zoom", b.ask("zoom"));
            assertEquals("ok settle", b.ask("settle"));
            assertTrue(b.ask("hand").startsWith("ok connection 1 since +"));
            assertTrue(b.ask("move 1,1").startsWith("err the hand is held by connection 1"));

            verbs.finish.countDown();
            assertEquals("ok move 300,200", moving.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void theHandIsReleasedWhenItsConnectionCloses() throws Exception {
        Recorder verbs = new Recorder();
        try (AutomationServer server = AutomationServer.start(verbs, 0, 4)) {
            Client a = new Client(server);
            Client b = new Client(server);
            assertEquals("ok down 10,10", a.ask("down 10,10"));
            assertTrue(b.ask("up").startsWith("err the hand is held by connection 1"));

            // Abruptly: no quit, no release — a script that died mid-drag.
            a.socket.close();
            String hand = awaitReply(b, "hand", r -> r.startsWith("ok free"));
            assertTrue(hand.startsWith("ok free (this is connection 2)"), hand);
            assertEquals(1, verbs.letGo.get(), "letting go released the button the departed holder held down");

            assertEquals("ok click 5,5", b.ask("click 5,5"), "the hand is there to be taken");
            assertTrue(b.ask("hand").startsWith("ok connection 2 since +"));
            b.close();
        }
    }

    @Test
    void handTakeAndReleaseAreExplicit() throws Exception {
        Recorder verbs = new Recorder();
        try (AutomationServer server = AutomationServer.start(verbs, 0, 4);
             Client a = new Client(server);
             Client b = new Client(server)) {
            assertEquals("ok free (this is connection 1)", a.ask("hand"));
            assertEquals("ok connection 1 holds the hand", a.ask("hand take"));
            assertEquals("ok connection 1 holds the hand", a.ask("hand take"), "taking twice is not an error");
            assertTrue(b.ask("key ENTER").startsWith("err the hand is held by connection 1"));
            assertEquals("ok released", a.ask("hand release"));
            assertEquals("ok the hand was free", a.ask("hand release"));
            assertEquals("ok key ENTER", b.ask("key ENTER"));
            assertTrue(a.ask("hand").startsWith("ok connection 2 since +"));
            assertTrue(a.ask("hand grab").startsWith("err hand takes"));
            assertTrue(a.ask("help").contains("hand take | release"));
        }
    }

    @Test
    void theCapRefusesExtraConnectionsWithAClearMessage() throws Exception {
        Recorder verbs = new Recorder();
        try (AutomationServer server = AutomationServer.start(verbs, 0, 2);
             Client a = new Client(server);
             Client b = new Client(server)) {
            assertEquals("ok tree", a.ask("tree"));
            assertEquals("ok tree", b.ask("tree"));
            try (Client c = new Client(server)) {
                String reply = c.ask("tree");
                assertEquals("err the automation socket is full: 2 connections are open"
                        + " (-Dautomation.connections=<n> raises the cap)", reply);
            }
            assertEquals(List.of("tree", "tree"), verbs.ran, "the refused connection ran nothing");

            // A slot freed is a slot open again.
            a.close();
            awaitReply(b, "tree", r -> true);
            String after = awaitConnect(server);
            assertEquals("ok tree", after);
        }
    }

    @Test
    void theDefaultCapIsFourAndThePropertyIsHonoured() throws Exception {
        try (AutomationServer server = AutomationServer.start(new Recorder(), 0)) {
            assertEquals(AutomationServer.DEFAULT_CONNECTIONS, server.connections());
            assertEquals(4, server.connections());
        }
        System.setProperty(AutomationServer.CONNECTIONS_PROPERTY, "7");
        try (AutomationServer server = AutomationServer.start(new Recorder(), 0)) {
            assertEquals(7, server.connections());
        } finally {
            System.clearProperty(AutomationServer.CONNECTIONS_PROPERTY);
        }
    }

    /** A disconnect is noticed on the departed connection's own thread, so the hand comes free shortly after. */
    private static String awaitReply(Client c, String line, java.util.function.Predicate<String> done)
            throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        String reply = c.ask(line);
        while (!done.test(reply) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            reply = c.ask(line);
        }
        return reply;
    }

    /** Connect until a slot is free — a closed connection leaves the open set on its own thread. */
    private static String awaitConnect(AutomationServer server) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            try (Client c = new Client(server)) {
                String reply = c.ask("tree");
                if (reply.startsWith("ok") || System.nanoTime() > deadline) {
                    return reply;
                }
            }
            Thread.sleep(10);
        }
    }
}
