package dev.vexelray.gui.inspector.link;

import dev.vexelray.gui.automation.AutomationServer;
import dev.vexelray.gui.automation.Commands;
import dev.vexelray.gui.automation.Discovery;
import dev.vexelray.gui.automation.Windows;
import dev.vexelray.gui.core.app.LoopCounters;
import dev.vexelray.gui.recorder.Follower;
import dev.vexelray.gui.recorder.Recorder;
import dev.vexelray.gui.recorder.Rows;
import dev.vexelray.gui.recorder.StreamOpened;
import sibarum.elektro.queue.Action;
import sibarum.elektro.queue.Conduit;
import sibarum.elektro.queue.DefaultConduit;
import sibarum.elektro.queue.ElektroException;
import sibarum.elektro.queue.message.PeerId;
import sibarum.elektro.queue.transport.PeerListener;
import sibarum.elektro.queue.transport.Transport;
import sibarum.elektro.queue.transport.tcp.TcpTransport;
import sibarum.elektro.queue.transport.uds.UdsTransport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The Inspector's link (docs/plans/flight-recorder.md §6): the flight recorder's rows, loop snapshots and read-only
 * queries, as typed records over an elektro-Q conduit.
 *
 * <p><b>Where.</b> A Unix domain socket beside the discovery file, {@code <app>-<pid>.sock}, advertised in that file
 * as {@code stream=uds:<path>}. When the path would be longer than an {@code AF_UNIX} path may be, a loopback TCP
 * port instead, advertised as {@code stream=tcp:<port>}.
 *
 * <p><b>One sender per Inspector, and the ring is the queue.</b> {@link StreamOpen} starts a sender thread for that
 * peer, following the ring through its own {@link Follower}. Every {@link #BATCH_MS} it sends what has arrived as one
 * {@link RowBatch}, addressed to that peer and never broadcast, so a stalled Inspector stalls only its own sender.
 * The transports' send timeout drops a peer whose writes stay blocked, and a peer that leaves stops its sender.
 * Nothing is encoded on the probe's thread: that is the recorder's slot stores and nothing else.
 *
 * <p><b>Threads.</b> Senders are {@code vexelray-automation-link-<peer>} daemon threads, outside the framework's
 * lanes like the automation socket's own (flight-recorder.md Q6). elektro-Q reads on virtual threads of its own.
 */
public final class InspectorLink implements AutoCloseable {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("automation.link");

    /** How often a sender copies and sends what has arrived. */
    public static final long BATCH_MS = 10;

    /** The most rows one batch carries; more wait for the next. */
    static final int MAX_BATCH_ROWS = 4_096;

    /** How long a send may block on one Inspector before that Inspector is dropped. */
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(2);

    /** The longest {@code AF_UNIX} path, measured on Windows (atchung docs/spikes/elektroq-inspector-stream.md §1). */
    static final int MAX_SOCKET_PATH = 106;

    /** The system property that switches the link off: {@code -Dautomation.link=off}. */
    public static final String SETTING = "automation.link";

    private final Conduit conduit;
    private final String endpoint;
    private final Recorder recorder;
    private final LoopCounters counters;
    private final Commands commands;
    private final Map<PeerId, Sender> senders = new ConcurrentHashMap<>();
    private final Action<RowBatch> rows;
    private final Action<Snap> snaps;
    private volatile boolean closed;
    /** What {@link #start(AutomationServer, Windows)} advertised in and hooked onto, so closing takes both back. */
    private volatile AutomationServer advertisedIn;
    private volatile Thread closeHook;

    private InspectorLink(Conduit conduit, String endpoint, Recorder recorder, LoopCounters counters,
                          Commands commands) {
        this.conduit = conduit;
        this.endpoint = endpoint;
        this.recorder = recorder;
        this.counters = counters;
        this.commands = commands;
        this.rows = conduit.action(Wire.ROW_BATCH);
        this.snaps = conduit.action(Wire.SNAP);
    }

    /**
     * Start the link beside {@code server}, over the recorder and loop counters {@code windows} holds, answering
     * queries with {@code windows}' verbs; and advertise it in the server's discovery file. Returns {@code null}, after
     * saying why, when the link is switched off or cannot start: an application that cannot be inspected is still
     * one that can be driven.
     */
    public static InspectorLink start(AutomationServer server, Windows windows) {
        String want = System.getProperty(SETTING, "on").trim().toLowerCase(Locale.ROOT);
        if (want.equals("off") || want.equals("false")) {
            return null;
        }
        Path file = server.discoveryFile();
        Path folder = file != null ? file.getParent() : Discovery.folder();
        String base = file != null ? file.getFileName().toString().replaceFirst("\\.auto$", "")
                : server.app() + "-" + ProcessHandle.current().pid();
        try {
            InspectorLink link = start(folder.resolve(base + ".sock"), windows.recorder(), windows.counters(),
                    windows);
            server.advertise("stream", link.endpoint());
            link.advertisedIn = server;
            // A process that exits without closing the link still removes its socket file; a crash leaves it, and
            // the next reader of the folder clears it with the discovery files. Taken off again by close, because
            // diagnostics switched on and off while the application runs would otherwise pile up one per switch.
            link.closeHook = new Thread(link::close, "vexelray-automation-link-close");
            Runtime.getRuntime().addShutdownHook(link.closeHook);
            LOG.info("inspector link listening on {}", link.endpoint());
            return link;
        } catch (RuntimeException e) {
            LOG.warn("inspector link not started: " + e.getMessage() + "; the automation socket still works");
            return null;
        }
    }

    /**
     * Start the link on {@code socket} (or a loopback TCP port when that path is too long for {@code AF_UNIX}).
     *
     * @param recorder the ring to stream; {@code null} when the probe is off, and a {@link StreamOpen} is then refused
     *                 with the reason
     * @param counters the loop's counters, for snapshots; {@code null} when the host has none
     * @param commands the verbs a {@link Query} is answered with
     */
    public static InspectorLink start(Path socket, Recorder recorder, LoopCounters counters, Commands commands) {
        Transport transport;
        String endpoint;
        TcpTransport tcp = null;
        Path absolute = socket.toAbsolutePath();
        if (absolute.toString().length() <= MAX_SOCKET_PATH) {
            try {
                Files.createDirectories(absolute.getParent());
            } catch (java.io.IOException e) {
                throw new ElektroException("cannot create " + absolute.getParent() + ": " + e.getMessage(), e);
            }
            transport = UdsTransport.listening(absolute).sendTimeout(SEND_TIMEOUT);
            endpoint = "uds:" + absolute;
        } else {
            tcp = TcpTransport.listening(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .sendTimeout(SEND_TIMEOUT);
            transport = tcp;
            endpoint = null;
        }
        Conduit conduit = new DefaultConduit("inspector-link", transport, Wire.registry());
        try {
            conduit.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            conduit.close();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ElektroException("the inspector link could not listen: " + cause.getMessage(), cause);
        }
        if (tcp != null) {
            endpoint = "tcp:" + tcp.boundPort();
        }
        InspectorLink link = new InspectorLink(conduit, endpoint, recorder, counters, commands.session());
        link.listen();
        return link;
    }

    private void listen() {
        conduit.subscribe(Wire.STREAM_OPEN, (open, ctx) -> {
            dev.vexelray.gui.inspector.link.StreamOpened reply = open(ctx.source(), open);
            ctx.reply(Wire.STREAM_OPENED, reply);
            // Started only once the reply is on its way, so the Inspector hears where the stream starts before the
            // first batch of it.
            Sender sender = senders.get(ctx.source());
            if (sender != null && reply.err().isEmpty()) {
                sender.start();
            }
        });
        conduit.subscribe(Wire.STREAM_CLOSE, (close, ctx) -> stop(ctx.source(), "closed by the Inspector"));
        conduit.subscribe(Wire.QUERY, (query, ctx) -> ctx.reply(Wire.QUERY_REPLY, answer(query.line())));
        conduit.addPeerListener(new PeerListener() {
            @Override
            public void onConnected(PeerId peer) {
                LOG.debug("inspector link: peer {} connected", peer.handle());
            }

            @Override
            public void onDisconnected(PeerId peer) {
                stop(peer, "the Inspector went away");
            }
        });
    }

    /** Where Inspectors dial: {@code uds:<path>} or {@code tcp:<port>}, as the discovery file says. */
    public String endpoint() {
        return endpoint;
    }

    private dev.vexelray.gui.inspector.link.StreamOpened open(PeerId peer, StreamOpen open) {
        if (recorder == null) {
            return refused(open, "the recorder is off: start the application with -Dprobe=... "
                    + "(and -Dprobe.format=csv when naming lanes)");
        }
        if (senders.containsKey(peer)) {
            return refused(open, "a stream is already open on this connection; send StreamClose first");
        }
        Follower follower;
        try {
            follower = new Follower(recorder, open.lanes(), open.fromSeq());
        } catch (IllegalArgumentException e) {
            return refused(open, e.getMessage());
        }
        StreamOpened o = follower.opened();
        senders.put(peer, new Sender(peer, follower, Math.max(0, open.snapEveryMs())));
        return new dev.vexelray.gui.inspector.link.StreamOpened(o.lanes(), o.fromSeq(), o.startSeq(), o.oldestSeq(),
                o.headSeq(), Optional.empty());
    }

    private static dev.vexelray.gui.inspector.link.StreamOpened refused(StreamOpen open, String why) {
        return new dev.vexelray.gui.inspector.link.StreamOpened(open.lanes(), open.fromSeq(), -1, -1, -1,
                Optional.of(why));
    }

    private void stop(PeerId peer, String why) {
        Sender s = senders.remove(peer);
        if (s != null) {
            s.stop(why);
        }
    }

    /** A query's reply: any read verb, answered exactly as the automation socket answers it. */
    QueryReply answer(String line) {
        String l = line == null ? "" : line.trim();
        if (l.isEmpty()) {
            return new QueryReply(false, "err an empty query");
        }
        if (commands.drives(l)) {
            return new QueryReply(false, "err the link only reads: '" + l.split("\\s+")[0]
                    + "' drives, so send it on the automation socket, which holds the hand");
        }
        if (commands.streaming(l) != null) {
            return new QueryReply(false, "err stream is this link's StreamOpen; as a query it would never end");
        }
        String reply = commands.command(l);
        return new QueryReply(reply.startsWith("ok"), reply);
    }

    /** The dials, now. */
    Snap snap() {
        long held = recorder == null ? -1 : recorder.held();
        long lost = recorder == null ? -1 : recorder.lost();
        if (counters == null) {
            return new Snap(-1, -1, -1, -1, -1, "-", Map.of(), held, lost);
        }
        Map<String, Long> parks = new LinkedHashMap<>();
        for (LoopCounters.Park p : LoopCounters.Park.values()) {
            parks.put(p.name().toLowerCase(Locale.ROOT), counters.parks(p));
        }
        String focus = (counters.focused() ? "on" : "off")
                + (counters.focusOverride() != LoopCounters.Focus.OS ? "(simulated)" : "");
        return new Snap(counters.frame(), counters.drawn(), counters.presented(), counters.wakes(),
                counters.wakesCoalesced(), focus, parks, held, lost);
    }

    /** How many Inspectors are streaming now. */
    public int streaming() {
        return senders.size();
    }

    @Override
    public void close() {
        closed = true;
        for (PeerId peer : List.copyOf(senders.keySet())) {
            stop(peer, "the application is closing the link");
        }
        conduit.close();
        AutomationServer server = advertisedIn;
        if (server != null) {
            advertisedIn = null;
            server.withdraw("stream");
        }
        Thread hook = closeHook;
        if (hook != null && Thread.currentThread() != hook) {
            closeHook = null;
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // Already shutting down: the hook is running, or has run.
            }
        }
    }

    /** One Inspector's stream: its cursor into the ring, and the thread that sends from it. */
    private final class Sender implements Follower.Sink {

        private final PeerId peer;
        private final Follower follower;
        private final long snapEveryNanos;
        private final Thread thread;
        private final List<Row> batch = new ArrayList<>();
        private long lostFrom;
        private long lostTo;
        private volatile boolean running = true;

        Sender(PeerId peer, Follower follower, int snapEveryMs) {
            this.peer = peer;
            this.follower = follower;
            this.snapEveryNanos = snapEveryMs * 1_000_000L;
            this.thread = new Thread(this::run, "vexelray-automation-link-" + peer.handle());
            this.thread.setDaemon(true);
        }

        synchronized void start() {
            if (thread.getState() == Thread.State.NEW) {
                thread.start();
            }
        }

        void stop(String why) {
            running = false;
            LockSupport.unpark(thread);
            LOG.debug("inspector link: stream to peer {} stopped ({})", peer.handle(), why);
        }

        @Override
        public void row(Rows rows, int i) {
            batch.add(new Row(rows.seq(i), rows.monoNanos(i), rows.wallNanos(i), rows.frame(i), rows.thread(i),
                    rows.lane(i), rows.kind(i), rows.detail(i)));
            if (batch.size() >= MAX_BATCH_ROWS) {
                flush();
            }
        }

        @Override
        public void lost(long fromSeq, long toSeq) {
            // A batch carries one gap, before its rows: a second gap, or a gap after rows, starts a new batch.
            if (!batch.isEmpty() || lostTo > 0) {
                flush();
            }
            lostFrom = fromSeq;
            lostTo = toSeq;
        }

        private void flush() {
            if (batch.isEmpty() && lostTo == 0) {
                return;
            }
            RowBatch b = new RowBatch(lostFrom, lostTo, List.copyOf(batch));
            batch.clear();
            lostFrom = 0;
            lostTo = 0;
            send(() -> rows.emit(b, peer));
        }

        private void send(Runnable emit) {
            if (!running) {
                return;
            }
            try {
                emit.run();
            } catch (ElektroException e) {
                // The peer has gone, or stopped reading for longer than the send timeout and was dropped.
                running = false;
                senders.remove(peer, this);
                LOG.debug("inspector link: stream to peer {} ended: {}", peer.handle(), e.getMessage());
            }
        }

        private void run() {
            long nextSnap = System.nanoTime();
            while (running && !closed) {
                while (running && follower.poll(this)) {
                    // keep copying until the cursor catches up with the writer
                }
                flush();
                long now = System.nanoTime();
                if (snapEveryNanos > 0 && now >= nextSnap) {
                    Snap s = snap();
                    send(() -> snaps.emit(s, peer));
                    nextSnap = now + snapEveryNanos;
                }
                LockSupport.parkNanos(BATCH_MS * 1_000_000L);
            }
        }
    }
}
