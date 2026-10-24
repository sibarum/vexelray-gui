package dev.vexelray.gui.automation;

import dev.vexelray.gui.recorder.Recorder;
import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code stream} over a real socket: its words, the shape of what it writes, how each kind of stream ends, and that
 * the connection streaming does not hold up the others.
 *
 * <p>Against a hand-fed {@link Recorder} rather than the probe, so the rows are known; the verbs are the real
 * {@link RecorderVerbs}, behind a stand-in that routes {@code stream} the way {@link Windows} does.
 */
class TextStreamTest {

    /** The ring verbs, and nothing else: what a host with a recorder and no window answers. */
    private static final class Ring implements Commands {
        final RecorderVerbs verbs;

        Ring(Recorder recorder) {
            verbs = new RecorderVerbs(recorder);
        }

        @Override
        public String command(String line) {
            String[] parts = line.trim().split("\\s+", 2);
            return RecorderVerbs.owns(parts[0]) ? verbs.answer(parts[0], parts.length > 1 ? parts[1] : "")
                    : "ok " + line;
        }

        @Override
        public Streaming streaming(String line) {
            String[] parts = line.trim().split("\\s+", 2);
            return parts[0].equals("stream") ? verbs.stream(parts.length > 1 ? parts[1] : "") : null;
        }
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        Client(AutomationServer server) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
            socket.setSoTimeout(10_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void send(String line) {
            out.println(line);
            out.flush();
        }

        /** Lines up to the terminator, not including it. */
        List<String> reply() throws IOException {
            List<String> lines = new ArrayList<>();
            String got;
            while ((got = in.readLine()) != null && !got.equals(".")) {
                lines.add(got);
            }
            return lines;
        }

        List<String> ask(String line) throws IOException {
            send(line);
            return reply();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static void write(Recorder r, long seq, Lane lane) {
        r.row(seq, seq * 1_000_000L, seq, "main", lane, "k", "d" + seq);
    }

    @Test
    void aBoundedStreamBackfillsReportsGapsAndEndsWithWhatItDelivered() throws Exception {
        Recorder ring = new Recorder(64, () -> 7);
        write(ring, 1, Lane.GPU);
        write(ring, 2, Lane.BUS);
        write(ring, 3, Lane.GPU);
        write(ring, 6, Lane.GPU);    // 4 and 5 never arrived
        try (AutomationServer server = AutomationServer.start(new Ring(ring), 0, 4);
             Client c = new Client(server)) {
            List<String> reply = c.ask("stream gpu from 1 for 50");
            assertEquals("ok stream lanes=gpu from_seq=1 start_seq=1 oldest_seq=1 head_seq=6", reply.get(0));
            assertEquals("seq,t_mono_ns,t_wall,thread,lane,kind,detail,frame", reply.get(1));
            assertTrue(reply.get(2).startsWith("1,1000000,"), reply.get(2));
            assertTrue(reply.get(2).endsWith(",main,gpu,k,d1,7"), reply.get(2));
            assertTrue(reply.get(3).startsWith("3,3000000,"), reply.get(3));
            assertEquals("# lost 4..5 rows=2", reply.get(4));
            assertTrue(reply.get(5).startsWith("6,6000000,"), reply.get(5));
            assertEquals("# end rows=3 over=+0.001s..+0.006s lost=2 seq=1..6 why=for elapsed", reply.get(6));
            assertEquals(7, reply.size());
            // The connection is an ordinary one again afterwards.
            assertEquals(List.of("ok free (this is connection 1)"), c.ask("hand"));
        }
    }

    @Test
    void anUnboundedStreamEndsWhenALineArrivesAndThatLineIsAnsweredNext() throws Exception {
        Recorder ring = new Recorder(64, () -> 1);
        try (AutomationServer server = AutomationServer.start(new Ring(ring), 0, 4);
             Client c = new Client(server)) {
            c.send("stream");
            assertEquals("ok stream lanes=all from_seq=- start_seq=- oldest_seq=- head_seq=-", c.in.readLine());
            assertEquals("seq,t_mono_ns,t_wall,thread,lane,kind,detail,frame", c.in.readLine());
            write(ring, 1, Lane.FRAME);
            String row = c.in.readLine();
            assertTrue(row.startsWith("1,1000000,"), "a row published after it started arrives live: " + row);
            c.send("recorder");
            List<String> rest = c.reply();
            assertEquals("# end rows=1 over=+0.001s..+0.001s lost=0 seq=1..1 why=a line arrived", rest.getLast());
            List<String> next = c.reply();
            assertTrue(next.getFirst().startsWith("ok rows=1 capacity_rows=64"),
                    "the line that ended the stream is answered as a command: " + next);
        }
    }

    @Test
    void aQuietStreamSaysItIsIdle() throws Exception {
        Recorder ring = new Recorder(64, () -> 1);
        try (AutomationServer server = AutomationServer.start(new Ring(ring), 0, 4);
             Client c = new Client(server)) {
            List<String> reply = c.ask("stream for " + (TextStream.IDLE_MS + 600));
            assertTrue(reply.stream().anyMatch(l -> l.startsWith("# idle quiet_ms=")), String.join("\n", reply));
            assertEquals("# end rows=0 over=- lost=0 seq=- why=for elapsed", reply.getLast());
        }
    }

    @Test
    void streamingOnOneConnectionDoesNotHoldUpAnother() throws Exception {
        Recorder ring = new Recorder(64, () -> 1);
        write(ring, 1, Lane.APP);
        try (AutomationServer server = AutomationServer.start(new Ring(ring), 0, 4);
             Client streaming = new Client(server);
             Client other = new Client(server)) {
            streaming.send("stream");
            streaming.in.readLine();
            long t0 = System.nanoTime();
            assertTrue(other.ask("recorder").getFirst().startsWith("ok rows=1"));
            assertTrue(other.ask("click 1,1").getFirst().startsWith("ok click"), "nor does it take the hand");
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000);
            streaming.send("quit");
        }
    }

    @Test
    void aReaderThatGoesAwayEndsItsStream() throws Exception {
        Recorder ring = new Recorder(1 << 12, () -> 1);
        AtomicBoolean writing = new AtomicBoolean(true);
        try (AutomationServer server = AutomationServer.start(new Ring(ring), 0, 1)) {
            Client c = new Client(server);
            c.send("stream");
            c.in.readLine();
            c.close();
            Thread writer = new Thread(() -> {
                long s = 1;
                while (writing.get()) {
                    write(ring, s++, Lane.APP);
                    Thread.onSpinWait();
                }
            });
            writer.start();
            // With a cap of one, a new connection is admitted only once the streaming one has noticed and closed.
            long until = System.nanoTime() + 10_000_000_000L;
            boolean admitted = false;
            while (!admitted && System.nanoTime() < until) {
                try (Client again = new Client(server)) {
                    admitted = again.ask("recorder").getFirst().startsWith("ok rows=");
                }
                if (!admitted) {
                    Thread.sleep(100);
                }
            }
            writing.set(false);
            writer.join();
            assertTrue(admitted, "the streaming connection let go of its slot when its reader went away");
        }
    }

    @Test
    void badArgumentsAreAnErrReply() throws Exception {
        Recorder ring = new Recorder(64, () -> 1);
        try (AutomationServer server = AutomationServer.start(new Ring(ring), 0, 4);
             Client c = new Client(server)) {
            assertEquals(List.of("err there is no lane 'paint'; the lanes are bus,state,time,anim,input,frame,layout,"
                    + "draw,gpu,shader,app,trace"), c.ask("stream paint"));
            assertEquals(List.of("err for takes 1 to 600000 ms, not 0"), c.ask("stream for 0"));
            assertEquals(List.of("err 'soon' is not a seq"), c.ask("stream from soon"));
            assertEquals(List.of("err stream takes [lanes|all] [from <seq>] [for <ms>], not 'gpu'"),
                    c.ask("stream all gpu"));
        }
        assertFalse(Automation.driving("stream", ""), "stream reads");
    }

    @Test
    void withTheProbeOffStreamSaysHowToStartIt() throws Exception {
        try (AutomationServer server = AutomationServer.start(new Ring(null), 0, 4);
             Client c = new Client(server)) {
            assertEquals(List.of("err the recorder is off: start with -Dprobe=..."), c.ask("stream for 10"));
        }
    }
}
