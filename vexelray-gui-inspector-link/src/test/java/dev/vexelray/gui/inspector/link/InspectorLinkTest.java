package dev.vexelray.gui.inspector.link;

import dev.vexelray.gui.automation.AutomationServer;
import dev.vexelray.gui.automation.Commands;
import dev.vexelray.gui.automation.Windows;
import dev.vexelray.gui.recorder.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sibarum.probe.Lane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link end to end over a real socket: the stream's start, its rows and gaps in order, refusals, snapshots,
 * queries, a peer leaving, the TCP fallback, and the discovery file advertising it. Against a hand-fed
 * {@link Recorder}, so the rows are known.
 */
class InspectorLinkTest {

    private Path dir;
    private final List<AutoCloseable> open = new ArrayList<>();

    private Path socket() throws Exception {
        dir = Files.createTempDirectory("il");     // short: AF_UNIX paths are length-limited
        return dir.resolve("a.sock");
    }

    private <T extends AutoCloseable> T keep(T c) {
        open.add(c);
        return c;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
        if (dir != null) {
            try (var left = Files.list(dir)) {
                for (Path p : left.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    private static void write(Recorder r, long seq, Lane lane) {
        r.row(seq, seq * 1_000_000L, seq, "main", lane, "k" + seq, "d" + seq);
    }

    private static void await(BooleanSupplier done, String what) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    /** Rows and gaps as the Inspector saw them, in order: "seq" for a row, "lost a..b" for a gap. */
    private static final class Seen {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<Snap> snaps = new CopyOnWriteArrayList<>();

        void rows(RowBatch b) {
            if (b.lost()) {
                events.add("lost " + b.lostFrom() + ".." + b.lostTo());
            }
            b.rows().forEach(r -> events.add(Long.toString(r.seq())));
        }
    }

    private static final Commands READS = line -> "ok " + line;

    @Test
    void theStreamBackfillsThenFollowsLiveWithGapsWhereTheyHappened() throws Exception {
        Recorder ring = new Recorder(64, () -> 3);
        write(ring, 1, Lane.GPU);
        write(ring, 2, Lane.BUS);
        write(ring, 3, Lane.GPU);
        InspectorLink link = keep(InspectorLink.start(socket(), ring, null, READS));
        assertTrue(link.endpoint().startsWith("uds:"), link.endpoint());
        LinkClient client = keep(LinkClient.connect(link.endpoint()));
        Seen seen = new Seen();
        StreamOpened opened = client.open(new StreamOpen(List.of("gpu"), 1, 0), seen::rows, seen.snaps::add);
        assertEquals(new StreamOpened(List.of("gpu"), 1, 1, 1, 3, Optional.empty()), opened);
        await(() -> seen.events.size() >= 2, "the backfill");
        write(ring, 6, Lane.GPU);      // 4 and 5 never arrived
        await(() -> seen.events.size() >= 4, "the live row and its gap");
        assertEquals(List.of("1", "3", "lost 4..5", "6"), seen.events);
        assertEquals(1, link.streaming());
    }

    @Test
    void aRowArrivesWithEveryFieldTheRecorderHeld() throws Exception {
        Recorder ring = new Recorder(64, () -> 41);
        ring.row(9, 90, 63, "main", Lane.GPU, "dxgi wait", "16.61ms\nsecond line");
        InspectorLink link = keep(InspectorLink.start(socket(), ring, null, READS));
        LinkClient client = keep(LinkClient.connect(link.endpoint()));
        List<Row> rows = new CopyOnWriteArrayList<>();
        client.open(new StreamOpen(List.of(), 0, 0), b -> rows.addAll(b.rows()), s -> { });
        await(() -> !rows.isEmpty(), "the row");
        assertEquals(new Row(9, 90, 63, 41, "main", "gpu", "dxgi wait", "16.61ms\nsecond line"), rows.getFirst(),
                "detail travels raw, newline and all, where the CSV has to flatten it");
    }

    @Test
    void aStreamIsRefusedWithItsReason() throws Exception {
        Recorder ring = new Recorder(64, () -> 1);
        InspectorLink link = keep(InspectorLink.start(socket(), ring, null, READS));
        LinkClient client = keep(LinkClient.connect(link.endpoint()));
        StreamOpened bad = client.open(new StreamOpen(List.of("paint"), -1, 0), b -> { }, s -> { });
        assertTrue(bad.err().orElse("").startsWith("there is no lane 'paint'; the lanes are bus,"), bad.toString());
        assertEquals(0, link.streaming());
        StreamOpened ok = client.open(new StreamOpen(List.of(), -1, 0), b -> { }, s -> { });
        assertTrue(ok.err().isEmpty());
        StreamOpened twice = client.open(new StreamOpen(List.of(), -1, 0), b -> { }, s -> { });
        assertEquals(Optional.of("a stream is already open on this connection; send StreamClose first"), twice.err());
    }

    @Test
    void withTheProbeOffTheStreamSaysHowToStartIt() throws Exception {
        InspectorLink link = keep(InspectorLink.start(socket(), null, null, READS));
        LinkClient client = keep(LinkClient.connect(link.endpoint()));
        StreamOpened off = client.open(new StreamOpen(List.of(), -1, 0), b -> { }, s -> { });
        assertTrue(off.err().orElse("").startsWith("the recorder is off: start the application with -Dprobe="),
                off.toString());
    }

    @Test
    void snapshotsArriveOnTheirScheduleWithLoopFieldsAbsentWithoutCounters() throws Exception {
        Recorder ring = new Recorder(64, () -> 1);
        write(ring, 1, Lane.APP);
        InspectorLink link = keep(InspectorLink.start(socket(), ring, null, READS));
        LinkClient client = keep(LinkClient.connect(link.endpoint()));
        Seen seen = new Seen();
        client.open(new StreamOpen(List.of(), -1, 50), seen::rows, seen.snaps::add);
        await(() -> seen.snaps.size() >= 3, "three snapshots");
        Snap s = seen.snaps.getFirst();
        assertEquals(-1, s.frame(), "no loop counters: said, not zero");
        assertEquals(1, s.ringHeld());
        assertEquals(0, s.ringLost());
    }

    @Test
    void aQueryIsAnyReadVerbAndDrivingIsRefused() throws Exception {
        Commands verbs = new Commands() {
            @Override
            public String command(String line) {
                return line.startsWith("why") ? "err frame 3 is not in the ring" : "ok " + line;
            }

            @Override
            public boolean drives(String line) {
                return line.startsWith("click");
            }

            @Override
            public Streaming streaming(String line) {
                return line.startsWith("stream") ? (in, out) -> { } : null;
            }
        };
        InspectorLink link = keep(InspectorLink.start(socket(), new Recorder(8, () -> 1), null, verbs));
        LinkClient client = keep(LinkClient.connect(link.endpoint()));
        assertEquals(new QueryReply(true, "ok frames worst 5"), client.query("frames worst 5"));
        assertEquals(new QueryReply(false, "err frame 3 is not in the ring"), client.query("why 3"));
        QueryReply click = client.query("click 41");
        assertFalse(click.ok());
        assertTrue(click.text().startsWith("err the link only reads: 'click' drives"), click.text());
        assertFalse(client.query("stream gpu").ok());
    }

    @Test
    void anInspectorThatLeavesStopsItsSender() throws Exception {
        Recorder ring = new Recorder(64, () -> 1);
        InspectorLink link = keep(InspectorLink.start(socket(), ring, null, READS));
        LinkClient client = LinkClient.connect(link.endpoint());
        client.open(new StreamOpen(List.of(), -1, 0), b -> { }, s -> { });
        assertEquals(1, link.streaming());
        client.close();
        await(() -> link.streaming() == 0, "the sender to stop");
    }

    @Test
    void closingTheStreamKeepsTheConnectionForQueries() throws Exception {
        Recorder ring = new Recorder(64, () -> 1);
        InspectorLink link = keep(InspectorLink.start(socket(), ring, null, READS));
        LinkClient client = keep(LinkClient.connect(link.endpoint()));
        client.open(new StreamOpen(List.of(), -1, 0), b -> { }, s -> { });
        client.closeStream("done");
        await(() -> link.streaming() == 0, "the stream to close");
        assertTrue(client.query("recorder").ok());
    }

    @Test
    void aPathTooLongForAfUnixFallsBackToLoopbackTcp() throws Exception {
        Path deep = socket().getParent().resolve("x".repeat(120)).resolve("a.sock");
        Recorder ring = new Recorder(64, () -> 1);
        write(ring, 1, Lane.APP);
        InspectorLink link = keep(InspectorLink.start(deep, ring, null, READS));
        assertTrue(link.endpoint().startsWith("tcp:"), link.endpoint());
        LinkClient client = keep(LinkClient.connect(link.endpoint()));
        Seen seen = new Seen();
        client.open(new StreamOpen(List.of(), 0, 0), seen::rows, s -> { });
        await(() -> !seen.events.isEmpty(), "a row over tcp");
        assertEquals("1", seen.events.getFirst());
    }

    @Test
    void theDiscoveryFileAdvertisesTheLinkAndAClientFindsItThere() throws Exception {
        Windows windows = new Windows(List::of);
        AutomationServer server = keep(AutomationServer.start(windows, 0, "linktest", "Link Test"));
        InspectorLink link = keep(InspectorLink.start(server, windows));
        Path file = server.discoveryFile();
        assertTrue(Files.readAllLines(file).contains("stream=" + link.endpoint()), Files.readString(file));
        try (LinkClient client = LinkClient.connect(file)) {
            QueryReply recorder = client.query("recorder");
            assertEquals("err the recorder is off: start with -Dprobe=...", recorder.text(),
                    "the probe is off in this JVM, and the link's query says so in the socket's words");
        }
    }
}
