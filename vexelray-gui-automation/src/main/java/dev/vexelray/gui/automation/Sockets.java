package dev.vexelray.gui.automation;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The process's automation sockets, switched on and off while it runs: {@link Capability#CONTROL} and
 * {@link Capability#DIAGNOSTICS}, each on a port of its own or both on one (docs/reference/automation.md §5,
 * <i>Two capabilities</i>).
 *
 * {@snippet :
 * Sockets sockets = new Sockets(windows, "calculator", "Calculator");
 * sockets.attach(Capability.DIAGNOSTICS, server -> InspectorLink.start(server, windows));
 * sockets.enable(Capability.CONTROL, 0);                    // a free port
 * sockets.enable(Capability.DIAGNOSTICS, sockets.port(Capability.CONTROL));   // the same one
 * }
 *
 * <p><b>A port names a socket.</b> Asking for the port the other capability already listens on joins that socket,
 * and it serves both; asking for any other port, or {@code 0} for a free one, binds a socket of its own. Switching a
 * capability off narrows a shared socket to the other one, so nobody connected to it is cut off, and closes a socket
 * that served only it. A port that cannot be bound throws and changes nothing: what was listening still is.
 *
 * <p><b>One discovery file, whatever listens.</b> The file says {@code control=} and {@code diagnostics=} — a port,
 * or {@code off} — with {@code port=} the control socket's (the diagnostics one when control is off), so a client
 * reading only {@code port=} still finds the hand. It is written before the first socket's
 * {@code automation: localhost:<port>} line is printed, rewritten on every switch, and removed when the last socket
 * closes.
 *
 * <p><b>The probe is not switched with it.</b> {@code Probe.ON} is fixed when the probe class loads, on purpose, so
 * the JIT can fold every guard away in a run that is not profiling. Diagnostics switched on in such a run answers,
 * and its recorder verbs say the recorder is off; a run that may want them later is started with the probe armed.
 *
 * <p>Thread-safe. The watchers ({@link #watch}) run on the thread that made the change, after it is made.
 */
public final class Sockets implements AutoCloseable {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("automation.sockets");

    private final Commands commands;
    private final String app;
    private final String title;
    private final PrintStream announce;
    /** Guarded by {@code this}. One entry per capability that is on; two entries may be the same socket. */
    private final Map<Capability, AutomationServer> on = new EnumMap<>(Capability.class);
    /** Guarded by {@code this}. Started when their capability comes on, closed when it goes off. */
    private final Map<Capability, List<Function<AutomationServer, AutoCloseable>>> attachments =
            new EnumMap<>(Capability.class);
    /** Guarded by {@code this}. What {@link #attachments} started and has not closed. */
    private final Map<Capability, List<AutoCloseable>> attached = new EnumMap<>(Capability.class);
    private final List<Consumer<Sockets>> watchers = new CopyOnWriteArrayList<>();
    /** Every capability's port, or absent; read lock-free by a socket's refusal, written under {@code this}. */
    private volatile Map<Capability, Integer> ports = Map.of();
    /** Guarded by {@code this}. Present while anything listens. */
    private Discovery discovery;

    /**
     * The sockets of the application {@code app}, titled {@code title} (the names its discovery file gives it),
     * answering with {@code commands}. Nothing listens until a capability is {@link #enable enabled}. Each new socket
     * prints its {@code automation: localhost:<port>} line to standard output.
     */
    public Sockets(Commands commands, String app, String title) {
        this(commands, app, title, System.out);
    }

    /** As above, printing each socket's line to {@code announce}, or nowhere when it is {@code null}. */
    public Sockets(Commands commands, String app, String title, PrintStream announce) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.app = app;
        this.title = title;
        this.announce = announce;
    }

    /** The setting for both on one socket: {@code --automation=0}, {@code -Dautomation=7654}. */
    public static final String SETTING = "automation";

    /** The setting for remote control alone: {@code --automation.control=<port>}. */
    public static final String CONTROL_SETTING = "automation.control";

    /** The setting for diagnostics alone: {@code --automation.diagnostics=<port>}. */
    public static final String DIAGNOSTICS_SETTING = "automation.diagnostics";

    /**
     * Switch on what a launch asked for, reading each setting from {@code settings} ({@code null} for unset):
     * {@link #SETTING} puts both on one socket and wins; otherwise {@link #CONTROL_SETTING} and
     * {@link #DIAGNOSTICS_SETTING} each switch their own, and naming the same port for both is one socket. Each value
     * is read by {@link #requested}.
     *
     * @throws IOException           when a port cannot be bound; what was switched on before it stays on
     * @throws NumberFormatException when a value is not {@code off}, {@code on} or a port; nothing has been switched
     */
    public void enableRequested(Function<String, String> settings) throws IOException {
        int both = requested(settings.apply(SETTING));
        int control = both >= 0 ? both : requested(settings.apply(CONTROL_SETTING));
        int diagnostics = both >= 0 ? -1 : requested(settings.apply(DIAGNOSTICS_SETTING));
        if (control >= 0) {
            control = enable(Capability.CONTROL, control);
        }
        if (both >= 0) {
            enable(Capability.DIAGNOSTICS, control);
        } else if (diagnostics >= 0) {
            enable(Capability.DIAGNOSTICS, diagnostics);
        }
    }

    /** Whether {@code settings} asks for either capability, as {@link #enableRequested} reads them. */
    public static boolean anyRequested(Function<String, String> settings) {
        for (String key : new String[] {SETTING, CONTROL_SETTING, DIAGNOSTICS_SETTING}) {
            try {
                if (requested(settings.apply(key)) >= 0) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return true;     // asked, if badly: the run that says why is the one that tried
            }
        }
        return false;
    }

    /**
     * The port a setting asks for: {@code off}, {@code false} or blank is {@code -1}, {@code on} or {@code true} is
     * {@link AutomationServer#DEFAULT_PORT}, and a number is itself ({@code 0} for a free one).
     *
     * @throws NumberFormatException for anything else
     */
    public static int requested(String setting) {
        String want = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT);
        if (want.isEmpty() || want.equals("off") || want.equals("false")) {
            return -1;
        }
        if (want.equals("on") || want.equals("true")) {
            return AutomationServer.DEFAULT_PORT;
        }
        int port = Integer.parseInt(want);
        if (port < 0 || port > 65_535) {
            throw new NumberFormatException(setting + " is not a port");
        }
        return port;
    }

    /**
     * Start {@code what} with this socket's capability, whenever {@code capability} comes on — now, if it is on —
     * and close what it returned when it goes off. {@code null} from it means nothing started, which it has said why.
     * The Inspector link is the one: it is diagnostics, and lives beside whichever socket serves it.
     */
    public Sockets attach(Capability capability, Function<AutomationServer, AutoCloseable> what) {
        synchronized (this) {
            attachments.computeIfAbsent(capability, k -> new ArrayList<>()).add(what);
            AutomationServer s = on.get(capability);
            if (s != null) {
                start(capability, what, s);
            }
        }
        return this;
    }

    /**
     * Switch {@code capability} on at {@code port}, and answer the port it listens on. {@code 0} is a free port, or
     * the one it already has; the port the other capability listens on joins that socket. Already on at that port,
     * this changes nothing.
     *
     * @throws IOException when the port cannot be bound; nothing has changed then
     */
    public int enable(Capability capability, int port) throws IOException {
        int bound;
        synchronized (this) {
            bound = turnOn(capability, port);
        }
        changed();
        return bound;
    }

    /** Switch {@code capability} off. A socket that also serves the other one stays open, serving only that. */
    public void disable(Capability capability) {
        synchronized (this) {
            if (on.get(capability) == null) {
                return;
            }
            drop(capability);
            settle();
        }
        changed();
    }

    /** The port {@code capability} listens on, or {@code -1} when it is off. */
    public int port(Capability capability) {
        Integer p = ports.get(capability);
        return p == null ? -1 : p;
    }

    /** Whether {@code capability} is on. */
    public boolean on(Capability capability) {
        return ports.containsKey(capability);
    }

    /** The socket serving {@code capability}, or {@code null} when it is off. */
    public synchronized AutomationServer server(Capability capability) {
        return on.get(capability);
    }

    /** The discovery file, or {@code null} while nothing listens or when it could not be written. */
    public synchronized java.nio.file.Path discoveryFile() {
        return discovery == null ? null : discovery.file();
    }

    /** {@code control=localhost:61184 diagnostics=off}: one line, for a log or a status line. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Capability c : Capability.values()) {
            int p = port(c);
            sb.append(sb.isEmpty() ? "" : " ").append(c.word()).append('=').append(p < 0 ? "off" : "localhost:" + p);
        }
        return sb.toString();
    }

    /**
     * Run {@code watcher} after every switch, on the thread that made it — never under this object's lock, so it may
     * ask anything here. Returns what removes it.
     */
    public Runnable watch(Consumer<Sockets> watcher) {
        watchers.add(watcher);
        return () -> watchers.remove(watcher);
    }

    @Override
    public void close() {
        synchronized (this) {
            for (Capability c : Capability.values()) {
                if (on.get(c) != null) {
                    drop(c);
                }
            }
            settle();
        }
        changed();
    }

    // --- under the lock ------------------------------------------------------------------------------------------

    private int turnOn(Capability capability, int port) throws IOException {
        AutomationServer current = on.get(capability);
        if (current != null && (port == 0 || port == current.port())) {
            return current.port();
        }
        Capability other = other(capability);
        AutomationServer theirs = on.get(other);
        boolean join = theirs != null && theirs != current && port != 0 && port == theirs.port();
        // Bound before anything is let go of, so a port that is taken leaves the old socket listening.
        AutomationServer fresh = join ? null
                : AutomationServer.listen(commands, port, EnumSet.of(capability), app, this::where);
        if (current != null) {
            drop(capability);
        }
        AutomationServer s;
        if (join) {
            theirs.serve(Capability.both());
            s = theirs;
        } else {
            s = fresh;
        }
        on.put(capability, s);
        settle();
        if (!join && announce != null) {
            // Standard output, and not the log: this line is protocol. ottermate --launch reads the port from it.
            // After settle, so the discovery file it promises is already there.
            announce.println("automation: localhost:" + s.port() + " (" + capability.word() + ")");
            announce.flush();
        }
        for (Function<AutomationServer, AutoCloseable> what : attachments.getOrDefault(capability, List.of())) {
            start(capability, what, s);
        }
        LOG.info("{} on localhost:{}{}", capability.word(), s.port(), join ? " (sharing the " + other.word() + " socket)" : "");
        return s.port();
    }

    /** Take {@code capability} off its socket: its attachments closed, and the socket narrowed or closed. */
    private void drop(Capability capability) {
        AutomationServer s = on.remove(capability);
        for (AutoCloseable a : attached.getOrDefault(capability, List.of())) {
            try {
                a.close();
            } catch (Exception e) {
                LOG.warn("closing what " + capability.word() + " started failed: " + e.getMessage());
            }
        }
        attached.remove(capability);
        if (on.containsValue(s)) {
            s.serve(EnumSet.of(other(capability)));
        } else {
            s.close();
        }
        LOG.info("{} off", capability.word());
    }

    /** The discovery file and the port table brought into line with {@link #on}. */
    private void settle() {
        Map<Capability, Integer> now = new EnumMap<>(Capability.class);
        on.forEach((c, s) -> now.put(c, s.port()));
        ports = Map.copyOf(now);
        if (on.isEmpty()) {
            if (discovery != null) {
                discovery.close();
                discovery = null;
            }
            return;
        }
        int primary = now.getOrDefault(Capability.CONTROL, now.get(Capability.DIAGNOSTICS));
        if (discovery == null) {
            discovery = Discovery.publish(app, title, primary);
        }
        if (discovery == null) {
            return;     // said why; every socket still listens on its port
        }
        for (AutomationServer s : on.values()) {
            s.share(discovery);
        }
        discovery.advertise("port", Integer.toString(primary));
        for (Capability c : Capability.values()) {
            Integer p = now.get(c);
            discovery.advertise(c.word(), p == null ? "off" : Integer.toString(p));
        }
    }

    private void start(Capability capability, Function<AutomationServer, AutoCloseable> what, AutomationServer s) {
        try {
            AutoCloseable started = what.apply(s);
            if (started != null) {
                attached.computeIfAbsent(capability, k -> new ArrayList<>()).add(started);
            }
        } catch (RuntimeException e) {
            LOG.warn("what " + capability.word() + " starts beside its socket did not start: " + e.getMessage());
        }
    }

    // --- off the lock --------------------------------------------------------------------------------------------

    /** Where {@code capability} listens, for a socket that does not serve it to say so; {@code null} when it is off. */
    private String where(Capability capability) {
        Integer p = ports.get(capability);
        return p == null ? null : "localhost:" + p;
    }

    private void changed() {
        for (Consumer<Sockets> w : watchers) {
            try {
                w.accept(this);
            } catch (RuntimeException e) {
                LOG.warn("a sockets watcher failed: " + e.getMessage());
            }
        }
    }

    private static Capability other(Capability capability) {
        return capability == Capability.CONTROL ? Capability.DIAGNOSTICS : Capability.CONTROL;
    }
}
