package dev.vexelray.gui.automation;

import sibarum.probe.Cause;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A line protocol on a loopback socket, so an agent outside the process can drive the application inside it.
 *
 * <p>One command per line in, one reply out, terminated by a line containing only {@code .} — a terminator
 * rather than a length, because the thing on the other end is often a person or a shell one-liner and a length
 * prefix makes {@code nc} useless. A reply starts with {@code ok} or {@code err}, so a script can branch on the
 * first two characters without parsing anything.
 *
 * <pre>
 * $ printf 'find Save\nclick 41\nsettle\n' | nc localhost 7654
 * </pre>
 *
 * <p><b>Loopback only, and deliberately.</b> This hands anyone who can reach it full control of the
 * application's input — it is a debugging instrument, not a service. Binding it to a routable address would
 * make every window on the machine a remote-control target, so the address is not configurable.
 *
 * <p><b>Many observers, one hand.</b> Several connections may be open at once ({@link #DEFAULT_CONNECTIONS}, or
 * {@code -Dautomation.connections}), and every one of them may read. Only the connection holding <b>the hand</b>
 * may run a line that {@link Commands#drives drives}: a pointer is one hand, and two agents interleaving paths
 * would produce a gesture neither of them asked for, and a log of a run that nobody performed. The first driving
 * line from a connection takes the hand if it is free; {@code hand take} and {@code hand release} do it
 * explicitly; a driving line from anyone else is refused with the holder's name. Closing the connection lets go,
 * so a script that crashes cannot leave the pointer locked — and letting go releases any button it held down.
 *
 * <p>Each connection runs on its own {@code vexelray-automation-<n>} daemon thread, never on the GUI thread.
 * Everything a command touches is either a lock-free snapshot or the bus, and the one thing that is not — a
 * screenshot — is posted to the frame loop by {@code WindowControls} rather than performed here.
 */
public final class AutomationServer implements AutoCloseable {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("automation.server");
    /** The source of every command's cause (atchung's docs/correlation.md). */
    private static final Cause.Source COMMAND = Cause.source("automation.command", Cause.Kind.COMMAND);

    /** The port, when nobody says otherwise. Arbitrary, high, and unregistered. */
    public static final int DEFAULT_PORT = 7654;

    /**
     * How many connections may be open at once, when nobody says otherwise: the Inspector, an agent driving, and
     * room for a person to look in on both. A bound rather than a policy — each connection is a thread.
     */
    public static final int DEFAULT_CONNECTIONS = 4;

    /**
     * The system property that sets the cap for {@link #start(Commands, int)}, so every host — the framework's
     * driver, the demos — honours it without plumbing a setting of its own.
     */
    public static final String CONNECTIONS_PROPERTY = "automation.connections";

    /**
     * The protocol this server speaks, {@code major.minor} (docs/plans/discovery.md §4). The minor rises with every
     * addition — a verb, a field, a form — and the major with anything that could break a client that worked
     * before, which refuses a major it does not know rather than guessing. {@code hello} and the discovery file
     * both report it. {@code ottermate} restates the major it speaks, as it restates {@link #DEFAULT_PORT}.
     */
    public static final String PROTOCOL = "1.2";

    /** How long a refused connection is answered before it is closed, if it does not close first. */
    private static final int REFUSED_LINGER_MS = 5_000;

    private static final String HAND_HELP = String.join("\n",
            "hand                     who holds the hand (drives input), and which connection this is",
            "hand take | release      take the hand if it is free, or let it go (closing the connection also lets go)",
            "hello                    the protocol version, this process, and what it offers now");

    private final Commands automation;
    private final int cap;
    private final ServerSocket socket;
    private final String app;
    /** The discovery file; a {@link Sockets} listener's is handed over by {@link #share} once it has a port to write. */
    private volatile Discovery discovery;
    /** Whether {@link #discovery} is this server's own to remove, or a {@link Sockets}' shared by its listeners. */
    private final boolean ownsDiscovery;
    /** What this socket serves; widened and narrowed in place by {@link Sockets}, so a shared port is one socket. */
    private volatile Set<Capability> serves;
    /** Where a capability this socket does not serve can be found instead, for the refusal; {@code null} for nowhere. */
    private final java.util.function.Function<Capability, String> elsewhere;
    private final Thread thread;
    private final long started = System.nanoTime();
    private final Set<Connection> open = ConcurrentHashMap.newKeySet();
    /** Connections for a capability made beside this socket rather than to it: the Inspector link's peers. */
    private final java.util.Map<Capability, java.util.concurrent.atomic.AtomicInteger> linked =
            java.util.Map.of(Capability.CONTROL, new java.util.concurrent.atomic.AtomicInteger(),
                    Capability.DIAGNOSTICS, new java.util.concurrent.atomic.AtomicInteger());
    /** Told after a connection opens or closes, here or beside ({@link #linked}); {@link Sockets} sets it. */
    private volatile Runnable onConnections = () -> { };
    private int numbered;
    private volatile boolean running = true;

    /** Guards {@link #holder} and {@link #heldSince}: who has the hand, and from when. */
    private final Object hand = new Object();
    private Connection holder;
    private long heldSince;

    /** Start listening on {@link #DEFAULT_PORT}. */
    public static AutomationServer start(Commands automation) throws IOException {
        return start(automation, DEFAULT_PORT);
    }

    /**
     * Start listening on {@code port}; pass 0 to be given a free one, then ask {@link #port()}. The connection cap
     * is {@code -Dautomation.connections}, or {@link #DEFAULT_CONNECTIONS}.
     */
    public static AutomationServer start(Commands automation, int port) throws IOException {
        return start(automation, port, Integer.getInteger(CONNECTIONS_PROPERTY, DEFAULT_CONNECTIONS));
    }

    /** Start listening on {@code port}, admitting at most {@code connections} at once. */
    public static AutomationServer start(Commands automation, int port, int connections) throws IOException {
        return new AutomationServer(automation, port, connections, null, null, Capability.both(), true, null);
    }

    /**
     * Start listening on {@code port} as the application {@code app}, titled {@code title}: the names its discovery
     * file and {@code hello} give it ({@code @VexelApp}'s name and title, for an application on the framework).
     * The other starts name it after its main class.
     */
    public static AutomationServer start(Commands automation, int port, String app, String title) throws IOException {
        return new AutomationServer(automation, port, Integer.getInteger(CONNECTIONS_PROPERTY, DEFAULT_CONNECTIONS),
                app, title, Capability.both(), true, null);
    }

    /**
     * One of a {@link Sockets}' listeners: serving {@code serves}, writing into the discovery file the set shares
     * ({@link #share}) rather than one of its own, and naming {@code elsewhere} in a refusal.
     */
    static AutomationServer listen(Commands automation, int port, Set<Capability> serves, String app,
                                   java.util.function.Function<Capability, String> elsewhere)
            throws IOException {
        return new AutomationServer(automation, port, Integer.getInteger(CONNECTIONS_PROPERTY, DEFAULT_CONNECTIONS),
                app, null, serves, false, elsewhere);
    }

    private AutomationServer(Commands automation, int port, int connections, String app, String title,
                             Set<Capability> serves, boolean publish,
                             java.util.function.Function<Capability, String> elsewhere) throws IOException {
        if (connections < 1) {
            throw new IllegalArgumentException("connections must be at least 1, not " + connections);
        }
        this.automation = java.util.Objects.requireNonNull(automation, "automation");
        this.cap = connections;
        this.socket = new ServerSocket(port, connections, InetAddress.getLoopbackAddress());
        this.app = app == null || app.isBlank() ? mainClassName() : app;
        // Before the caller prints its "automation: localhost:<port>" line, so anything that saw the line can rely on
        // the file being there too.
        this.serves = java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(serves));
        this.elsewhere = elsewhere;
        this.ownsDiscovery = publish;
        this.discovery = publish ? Discovery.publish(this.app, title, socket.getLocalPort()) : null;
        this.thread = new Thread(this::serve, "vexelray-automation");
        this.thread.setDaemon(true);      // an instrument must never be the reason a process will not exit
        this.thread.start();
    }

    /** The port actually bound — worth asking when 0 was requested. */
    public int port() {
        return socket.getLocalPort();
    }

    /** What this socket serves now. */
    public Set<Capability> serves() {
        return serves;
    }

    /** Write into {@code shared}, the discovery file of the {@link Sockets} this listener belongs to. */
    void share(Discovery shared) {
        discovery = shared;
    }

    /** Serve {@code now} from here on: a line already running finishes, the next is judged against this. */
    void serve(Set<Capability> now) {
        serves = java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(now));
    }

    /**
     * How many connections can use {@code capability} now: those open on this socket while it serves it, and those
     * made beside it for it ({@link #linked}). What the title bar's lamps read.
     */
    public int connected(Capability capability) {
        return (serves.contains(capability) ? open.size() : 0) + linked.get(capability).get();
    }

    /**
     * Count a connection made beside this socket for {@code capability} — the Inspector link's peer — as one of its
     * own, {@code opened} or closed, so the lamps show it as they show a connection to the socket.
     */
    public void linked(Capability capability, boolean opened) {
        linked.get(capability).addAndGet(opened ? 1 : -1);
        connectionsChanged();
    }

    /** Run {@code r} after every connection opens or closes; one at a time, the last set wins. */
    void onConnections(Runnable r) {
        onConnections = r == null ? () -> { } : r;
    }

    private void connectionsChanged() {
        try {
            onConnections.run();
        } catch (RuntimeException e) {
            LOG.warn("a connections watcher failed: " + e.getMessage());
        }
    }

    /** How many connections may be open at once. */
    public int connections() {
        return cap;
    }

    /** The discovery file this server wrote, or {@code null} when it could not write one. */
    public java.nio.file.Path discoveryFile() {
        return discovery == null ? null : discovery.file();
    }

    /**
     * Add a fact to this process's discovery file, such as a further endpoint (the Inspector link's {@code stream});
     * see {@link Discovery#advertise}. Nothing happens when there is no file.
     */
    public void advertise(String key, String value) {
        if (discovery != null) {
            discovery.advertise(key, value);
        }
    }

    /** Take a fact {@link #advertise} added back out of the discovery file; see {@link Discovery#withdraw}. */
    public void withdraw(String key) {
        if (discovery != null) {
            discovery.withdraw(key);
        }
    }

    /** The application's name, as the discovery file and {@code hello} give it. */
    public String app() {
        return app;
    }

    /** An application nobody named, named after its main class: {@code dev.vexelray.gui.demo.Demo} is "demo". */
    private static String mainClassName() {
        String command = System.getProperty("sun.java.command", "").trim();
        String main = command.isEmpty() ? "app" : command.split("\\s+", 2)[0];
        if (main.endsWith(".jar")) {
            main = main.substring(0, main.length() - 4);
        }
        int cut = Math.max(main.lastIndexOf('.'), Math.max(main.lastIndexOf('/'), main.lastIndexOf('\\')));
        String name = main.substring(cut + 1);
        return name.isEmpty() ? "app" : name.toLowerCase(Locale.ROOT);
    }

    private void serve() {
        while (running) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (running) {
                    LOG.warn("accept failed: " + e.getMessage());
                }
                continue;
            }
            // Only this thread adds, so the count it reads cannot grow under it; a connection closing meanwhile
            // only makes the answer conservative for one accept.
            Connection c = new Connection(++numbered, client, automation.session());
            boolean admitted = open.size() < cap;
            if (admitted) {
                open.add(c);
            }
            Thread t = new Thread(admitted ? () -> converse(c) : () -> refuse(c), "vexelray-automation-" + c.number);
            t.setDaemon(true);
            t.start();
        }
    }

    private void converse(Connection c) {
        mark("agent.connection", c, "open");
        LOG.debug("connection {} open", c.number);
        connectionsChanged();
        try (Socket client = c.socket) {
            BufferedReader in = reader(client);
            PrintWriter out = writer(client);
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.trim().equalsIgnoreCase("quit")) {
                    return;
                }
                // Each command its own cause, so the command and everything it set off in the application share an
                // ID: its row, its log lines, the input it injected and the writes that input made.
                boolean more;
                long previous = Cause.enter(COMMAND.next());
                try {
                    more = command(c, line, in, out);
                } finally {
                    Cause.exit(previous);
                }
                if (!more) {
                    return;
                }
            }
        } catch (IOException e) {
            if (running) {
                // One client going away is not the end of the session; the others, and the next, are still welcome.
                LOG.warn("connection " + c.number + " ended: " + e.getMessage());
            }
        } finally {
            release(c, "disconnect");
            open.remove(c);
            mark("agent.connection", c, "closed");
            LOG.debug("connection {} closed", c.number);
            connectionsChanged();
        }
    }

    /** Answer one command line, inside its cause. False when the connection is done: its reader went away mid-stream. */
    private boolean command(Connection c, String line, BufferedReader in, PrintWriter out) throws IOException {
        // The agent's own commands, in the same file and the same order as their effects — which is what
        // lets a run be read back without the conversation that produced it. The number says whose.
        mark("agent.command", c, line);
        LOG.debug("command #{}: {}", c.number, line);
        Commands.Streaming streaming = c.session.streaming(line);
        String unserved = streaming == null ? null : unserved(c, line);
        if (unserved != null) {
            out.println(unserved);
            out.println(".");
            out.flush();
            return true;
        }
        if (streaming != null) {
            // Written as it happens, on this connection's thread: the connection is its sender. It reads,
            // so it needs no hand, and the others carry on meanwhile.
            streaming.run(in, out);
            out.println(".");
            out.flush();
            return !out.checkError();     // false: the reader went away mid-stream; nothing more to say to it
        }
        String reply = answer(c, line);
        if (LOG.isDebug()) {
            int end = reply.indexOf('\n');
            LOG.debug("reply #{}: {}", c.number,
                    end < 0 ? reply : reply.substring(0, end) + " ... (" + reply.lines().count() + " lines)");
        }
        out.println(reply);
        out.println(".");
        out.flush();     // per reply: the other end is waiting on this one before it sends the next
        return true;
    }

    private String answer(Connection c, String line) {
        String[] parts = line.trim().split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        if (verb.equals("hello")) {
            return hello(c);
        }
        if (verb.equals("help")) {
            return c.session.command(line) + "\n" + HAND_HELP + "\nthis socket serves " + Capability.words(serves)
                    + "; a verb it does not serve is refused";
        }
        if (verb.equals("hand")) {
            return serves.contains(Capability.CONTROL)
                    ? hand(c, parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "")
                    : refusal(verb, Capability.CONTROL);
        }
        String unserved = unserved(c, line);
        if (unserved != null) {
            return unserved;
        }
        if (c.session.drives(line)) {
            String refused = take(c);
            if (refused != null) {
                return refused;
            }
        }
        return c.session.command(line);
    }

    /** The refusal for a line needing a capability this socket does not serve, or {@code null} when it serves it. */
    private String unserved(Connection c, String line) {
        Capability needed = c.session.needs(line);
        if (serves.contains(needed)) {
            return null;
        }
        return refusal(line.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT), needed);
    }

    /**
     * {@code err recorder is diagnostics, and this socket serves control; diagnostics is on localhost:61185} — the
     * verb, what it needs, and where that is, so a client that asked the wrong socket learns which to ask.
     */
    private String refusal(String verb, Capability needed) {
        String where = elsewhere == null ? null : elsewhere.apply(needed);
        return "err " + verb + " is " + needed.word() + ", and this socket serves " + Capability.words(serves)
                + "; " + needed.word() + (where == null ? " is off" : " is on " + where);
    }

    private String hand(Connection c, String what) {
        switch (what) {
            case "" -> {
                synchronized (hand) {
                    if (holder == null) {
                        return "ok free (this is connection " + c.number + ")";
                    }
                    return "ok connection " + holder.number + " since " + since()
                            + (holder == c ? " (this one)" : " (this is connection " + c.number + ")");
                }
            }
            case "take" -> {
                String refused = take(c);
                return refused != null ? refused : "ok connection " + c.number + " holds the hand";
            }
            case "release" -> {
                synchronized (hand) {
                    if (holder != null && holder != c) {
                        return refusal();
                    }
                }
                return release(c, "release") ? "ok released" : "ok the hand was free";
            }
            default -> {
                return "err hand takes take, release, or nothing";
            }
        }
    }

    /**
     * {@code hello}: the protocol, this process and its connections on the first line, then what the host offers
     * now, one line per subject ({@link Commands#describe}). It reads, so any connection may ask, and it is what a
     * client asks once to decide whether it can talk to this server at all (docs/plans/discovery.md §4).
     */
    private String hello(Connection c) {
        String held;
        synchronized (hand) {
            held = holder == null ? "free" : holder.number + "(since " + since() + ")";
        }
        StringBuilder sb = new StringBuilder("ok protocol=").append(PROTOCOL)
                .append(" app=").append(app)
                .append(" pid=").append(ProcessHandle.current().pid())
                .append(" port=").append(port())
                .append(" serves=").append(Capability.words(serves))
                .append(" connections=").append(open.size()).append('/').append(cap)
                .append(" hand=").append(held)
                .append(" this=").append(c.number)
                .append(" file=").append(discovery == null ? "-" : discovery.file());
        // What this process was built from, exactly: the commit plus any uncommitted change, and how many stamped parts
        // of the stack it was built with. The discovery file lists the parts.
        sb.append("\nbuild   ").append(Discovery.buildLine()).append(" stack=")
                .append(Discovery.build().getOrDefault("stack.count", "0"));
        try {
            for (String line : c.session.describe()) {
                sb.append('\n').append(line);
            }
        } catch (RuntimeException e) {
            sb.append("\nunknown ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        }
        return sb.append("\nsee     help").toString();
    }

    /** Take the hand for {@code c} if it is free or already {@code c}'s; otherwise the refusal to reply with. */
    private String take(Connection c) {
        synchronized (hand) {
            if (holder == c) {
                return null;
            }
            if (holder != null) {
                return refusal();
            }
            holder = c;
            heldSince = System.nanoTime();
        }
        mark("agent.hand", c, "take");
        LOG.debug("connection {} took the hand", c.number);
        return null;
    }

    /**
     * Let go of the hand if {@code c} holds it, releasing any button left down first. Called only on {@code c}'s
     * own thread, so nothing of {@code c}'s is driving meanwhile, and nobody else can take the hand until it is
     * free — which is after the buttons are up.
     */
    private boolean release(Connection c, String why) {
        synchronized (hand) {
            if (holder != c) {
                return false;
            }
        }
        try {
            c.session.letGo();
        } catch (RuntimeException e) {
            LOG.warn("letting go for connection " + c.number + " failed: " + e.getMessage());
        }
        synchronized (hand) {
            holder = null;
        }
        mark("agent.hand", c, "release (" + why + ")");
        LOG.debug("connection {} let go of the hand ({})", c.number, why);
        return true;
    }

    /** Called holding {@link #hand}, with a holder. */
    private String refusal() {
        return "err the hand is held by connection " + holder.number + " (since " + since() + ")";
    }

    /** When the hand was taken, on this server's clock — the same {@code +s} for every connection that asks. */
    private String since() {
        return String.format(Locale.ROOT, "+%.1fs", (heldSince - started) / 1e9);
    }

    /**
     * Answer every line from a connection over the cap with the refusal, until it closes or goes quiet. Answered
     * rather than closed at once: a client that has already written its first command would have that write
     * reset, and on Windows a reset discards a reply it had not read yet — so it would see a broken connection
     * where it should see a reason.
     */
    private void refuse(Connection c) {
        String why = "err the automation socket is full: " + cap + " connections are open"
                + " (-D" + CONNECTIONS_PROPERTY + "=<n> raises the cap)";
        LOG.warn("connection " + c.number + " refused: " + cap + " already open");
        try (Socket client = c.socket) {
            client.setSoTimeout(REFUSED_LINGER_MS);
            BufferedReader in = reader(client);
            PrintWriter out = writer(client);
            String line;
            while (running && (line = in.readLine()) != null && !line.trim().equalsIgnoreCase("quit")) {
                out.println(why);
                out.println(".");
                out.flush();
            }
        } catch (SocketTimeoutException e) {
            // Gone quiet: it has had its answer, or never asked.
        } catch (IOException e) {
            // A refused client going away is what was wanted.
        }
    }

    private static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    private static PrintWriter writer(Socket s) throws IOException {
        return new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static void mark(String name, Connection c, String detail) {
        if (Probe.ON) {
            Probe.mark(Lane.APP, name, "#" + c.number + " " + detail);
        }
    }

    @Override
    public void close() {
        running = false;
        if (discovery != null && ownsDiscovery) {
            discovery.close();
        }
        try {
            socket.close();
        } catch (IOException e) {
            // Closing a socket that is already gone is not a failure worth reporting from a close().
        }
        for (Connection c : open) {
            try {
                c.socket.close();     // ends its read, and its thread lets go of the hand on the way out
            } catch (IOException e) {
                // As above.
            }
        }
        thread.interrupt();
    }

    /** One client: its number (what the hand and the log call it), its socket, and its own view of the commands. */
    private record Connection(int number, Socket socket, Commands session) {
    }
}
