package dev.vexelray.gui.automation;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p><b>What this run offers, and what each port has been through.</b> A run may offer both capabilities, one, or
 * neither ({@link #AVAILABLE_SETTING}); switching on one it does not offer throws. Each offered one is in one of the
 * {@link State}s, which say whether its port is open and whether anything is, or was this session, connected to it —
 * what the title bar's lamps show, so a person can see at a glance whether anything can drive the application or read
 * its diagnostics, and whether anything has.
 *
 * <p>Thread-safe. The watchers ({@link #watch}) run on the thread that made the change, after it is made — a
 * connection opening or closing among them.
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
    /** What this run may switch on; fixed at construction. */
    private final Set<Capability> available;
    /** Guarded by {@code this}. The capabilities anything has been connected to this session, open or closed since. */
    private final Set<Capability> used = EnumSet.noneOf(Capability.class);

    /**
     * Where a capability stands: its port open or not, and anything connected to it now, earlier, or never
     * (docs/reference/automation.md §5, <i>Lamps</i>). Opened and closed with nothing connected is {@link #OFF}, the
     * same as never opened: nothing happened through it.
     */
    public enum State {
        /** This run does not offer it ({@link #AVAILABLE_SETTING}): it cannot be switched on. */
        UNAVAILABLE,
        /** Closed, and nothing has connected to it this session. */
        OFF,
        /** Open, and nothing has connected to it yet. */
        LISTENING,
        /** Open, with something connected now. */
        CONNECTED,
        /** Open; something was connected, and none is now. */
        WAS_CONNECTED,
        /** Closed, after something had connected to it this session. */
        CLOSED_AFTER_USE
    }

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
        this(commands, app, title, announce, Capability.both());
    }

    /** As above, offering only {@code available}: switching on anything else throws ({@link #AVAILABLE_SETTING}). */
    public Sockets(Commands commands, String app, String title, PrintStream announce, Set<Capability> available) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.app = app;
        this.title = title;
        this.announce = announce;
        this.available = available.isEmpty() ? java.util.Collections.emptySet()
                : java.util.Collections.unmodifiableSet(EnumSet.copyOf(available));
    }

    /**
     * The setting for what a run may switch on at all: {@code all} (the default), {@code none}, {@code control} or
     * {@code diagnostics} — {@code --automation.available=none}. A run that offers neither has no automation button,
     * and switching either on throws.
     */
    public static final String AVAILABLE_SETTING = "automation.available";

    /**
     * What {@link #AVAILABLE_SETTING} offers: unset, blank, {@code all}, {@code both}, {@code on} or {@code true} is
     * both; {@code none}, {@code off} or {@code false} is neither; otherwise a comma-separated list of
     * {@link Capability#word()}s.
     *
     * @throws IllegalArgumentException for anything else; a caller that must not open what was meant to be shut treats
     *                                  that as {@code none}
     */
    public static Set<Capability> available(String setting) {
        String want = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT);
        switch (want) {
            case "", "all", "both", "on", "true" -> {
                return Capability.both();
            }
            case "none", "off", "false" -> {
                return EnumSet.noneOf(Capability.class);
            }
            default -> {
                Set<Capability> set = EnumSet.noneOf(Capability.class);
                for (String word : want.split("[,\\s]+")) {
                    set.add(Capability.named(word).orElseThrow(() -> new IllegalArgumentException("'" + setting
                            + "' is not all, none, control, diagnostics, or a list of those")));
                }
                return set;
            }
        }
    }

    /** What this run may switch on. */
    public Set<Capability> available() {
        return available;
    }

    /** Where {@code capability} stands now. */
    public synchronized State state(Capability capability) {
        if (!available.contains(capability)) {
            return State.UNAVAILABLE;
        }
        AutomationServer s = on.get(capability);
        boolean was = used.contains(capability);
        if (s == null) {
            return was ? State.CLOSED_AFTER_USE : State.OFF;
        }
        if (s.connected(capability) > 0) {
            return State.CONNECTED;
        }
        return was ? State.WAS_CONNECTED : State.LISTENING;
    }

    /** How many connections can use {@code capability} now; {@code 0} when it is off. */
    public synchronized int connected(Capability capability) {
        AutomationServer s = on.get(capability);
        return s == null ? 0 : s.connected(capability);
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
     * @throws IllegalStateException when it asks for a capability this run does not offer; nothing has been switched
     */
    public void enableRequested(Function<String, String> settings) throws IOException {
        int both = requested(settings.apply(SETTING));
        int control = both >= 0 ? both : requested(settings.apply(CONTROL_SETTING));
        int diagnostics = both >= 0 ? -1 : requested(settings.apply(DIAGNOSTICS_SETTING));
        if (control >= 0) {
            offered(Capability.CONTROL);
        }
        if (both >= 0 || diagnostics >= 0) {
            offered(Capability.DIAGNOSTICS);
        }
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
     * @throws IOException           when the port cannot be bound; nothing has changed then
     * @throws IllegalStateException when this run does not offer {@code capability} ({@link #AVAILABLE_SETTING})
     */
    public int enable(Capability capability, int port) throws IOException {
        offered(capability);
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
        if (fresh != null) {
            fresh.onConnections(this::connectionsChanged);
        }
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
        noteConnections();
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

    /**
     * Remember every capability something is connected to now. Asked on every switch as well as every connection,
     * because a socket that comes to serve both lets the connections already on it use the other one too.
     */
    private void noteConnections() {
        on.forEach((c, s) -> {
            if (s.connected(c) > 0) {
                used.add(c);
            }
        });
    }

    // --- off the lock --------------------------------------------------------------------------------------------

    /** A connection opened or closed on one of the sockets, or beside one: noted, then the watchers told. */
    private void connectionsChanged() {
        synchronized (this) {
            noteConnections();
        }
        changed();
    }

    /** Throws unless this run offers {@code capability}. */
    private void offered(Capability capability) {
        if (!available.contains(capability)) {
            throw new IllegalStateException(refusal(capability, available));
        }
    }

    /** Why {@code capability} cannot be switched on in a run offering only {@code available}. */
    public static String refusal(Capability capability, Set<Capability> available) {
        return (capability == Capability.CONTROL ? "remote control" : "diagnostics") + " is not available in this run ("
                + AVAILABLE_SETTING + "=" + (available.isEmpty() ? "none" : Capability.words(available)) + ")";
    }

    /**
     * The capabilities {@code settings} asks for, as {@link #enableRequested} reads them, so a caller can refuse what
     * the run does not offer before it builds anything.
     *
     * @throws NumberFormatException as {@link #requested} does
     */
    public static Set<Capability> asked(Function<String, String> settings) {
        Set<Capability> set = EnumSet.noneOf(Capability.class);
        if (requested(settings.apply(SETTING)) >= 0) {
            return Capability.both();
        }
        if (requested(settings.apply(CONTROL_SETTING)) >= 0) {
            set.add(Capability.CONTROL);
        }
        if (requested(settings.apply(DIAGNOSTICS_SETTING)) >= 0) {
            set.add(Capability.DIAGNOSTICS);
        }
        return set;
    }

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
