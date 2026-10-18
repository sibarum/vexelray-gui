package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.LoopCounters;
import dev.vexelray.gui.core.app.WindowView;
import dev.vexelray.gui.core.nav.Address;
import dev.vexelray.gui.recorder.Recorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * An application's windows as one driving surface: two verbs to see and choose among them, and every other
 * command handed to the window that is chosen.
 *
 * <p><b>One {@link Automation} per window, and nothing shared.</b> Each window has its own tree, its own bus and
 * its own pointer, so a click in the settings window has to be published on the settings window's bus and
 * resolved against the settings window's layout. A single driver that "switched" between trees would have to
 * re-point all of that, and a half-switched driver clicks in one window at coordinates read in another. Here
 * the choice is only which driver receives the line.
 *
 * <p><b>The list is asked for on every command</b>, because a window opens and closes without telling anybody:
 * {@code windows} is always what is open now, and a window chosen earlier that has since closed is reported
 * as gone rather than silently replaced by another. The choice is by tree identity and not by position, so it
 * follows its window as others come and go.
 *
 * <p><b>The choice belongs to a connection; the drivers do not.</b> Each connection gets its own {@link #session},
 * so an observer looking at the settings window does not move the window the hand is driving. The drivers are
 * shared between sessions, because a window has one pointer and a second driver would be a second pointer.
 *
 * <p>Until a window is chosen, commands go to the main window — which is what a one-window application is, and
 * why nothing here changes how it is driven.
 *
 * <p><b>A target can name its window instead</b> ({@link #addressed}): {@code click viewport/view.canvas} goes to
 * the viewport's driver for that command and leaves the choice where it was, because an
 * {@link Address} is {@code window/landmark} — the same value a hyperlink and {@code GuiApp}'s routing use. Nothing
 * is bound at construction: the windows, the drivers and each driver's controls are all looked up per command, so a
 * window that opens after the socket does, or is closed and reopened under the same tree, needs nothing from its
 * application (docs/plans/todo.md §6.9).
 *
 * <ul>
 *   <li>{@code windows} — what is open: a number, a name, the size and view factors, and {@code *} on the chosen one;</li>
 *   <li>{@code window <name|number>} — choose one, by its exact name, a unique beginning of it, or its number.</li>
 * </ul>
 *
 * <p>The flight recorder's verbs ({@code recorder}, {@code rows}, {@code gaps}, {@code frames}, {@code why}, and
 * {@code stream}, which the server runs through {@link #streaming}) are
 * answered here too, before any window is asked for: the ring is the process's, not a window's. See
 * {@link RecorderVerbs}.
 */
public final class Windows implements Commands {

    private static final String GONE = "err the window chosen earlier has closed; windows lists what is open";

    /** How long a window verb waits for the application's first window before saying none is open. */
    static final long FIRST_WINDOW_TIMEOUT_MS = 30_000;

    /** How often that wait looks. A first window takes seconds, so this is far inside what anybody notices. */
    private static final long FIRST_WINDOW_POLL_MS = 50;

    private static final String HELP = String.join("\n",
            "windows                  the open windows: number, name, size and view; * is the chosen one",
            "window <name|number>     choose the window the other commands act on (default: the main one)",
            "<verb> <window>/<landmark> ...",
            "                         a target naming its window acts there, this command only (click viewport/home)");

    private final Supplier<List<WindowView>> source;
    private final Timeline timeline;
    /**
     * Shared by every {@link #session}, and guarded by itself — for the lookup only, never across a command, so a
     * thirty-second {@code await} on one connection does not stall another's {@code tree}.
     */
    private final Map<Gui, Automation> drivers;
    /** The loop's counters, or {@code null} when the host handed none: the frame stamp on the recorder's rows. */
    private final LoopCounters counters;
    /** The ring verbs, over the one recorder every session shares. */
    private final RecorderVerbs ring;
    /** Whether any window has been open yet, shared by every session: until then a window verb waits for one. */
    private final AtomicBoolean seenWindow;
    private volatile Gui chosen;

    public Windows(Supplier<List<WindowView>> source) {
        this(source, Timeline.NONE);
    }

    /** With the clock every window's {@code settle} waits out; see {@link Timeline}. */
    public Windows(Supplier<List<WindowView>> source, Timeline timeline) {
        this(source, timeline, null);
    }

    /**
     * With the loop's counters as well ({@code GuiApp} is one): what stamps each flight-recorder row with the frame
     * it was written in, so {@code frames} and {@code why} can answer. Without them the ring still records, and
     * those two verbs say why they cannot.
     *
     * <p>The recorder starts here when the probe is on, sized by {@code -Drecorder=<rows>} (default 2^18), and is
     * shared by every {@link #session}: it is the process's, like the probe that feeds it.
     */
    public Windows(Supplier<List<WindowView>> source, Timeline timeline, LoopCounters counters) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.timeline = timeline == null ? Timeline.NONE : timeline;
        this.drivers = new IdentityHashMap<>();
        this.counters = counters;
        this.ring = new RecorderVerbs(Recorder.start(Recorder.rowsFromProperty(),
                counters == null ? Recorder.NO_FRAMES : counters::frame));
        this.seenWindow = new AtomicBoolean();
    }

    private Windows(Windows shared) {
        this.source = shared.source;
        this.timeline = shared.timeline;
        this.drivers = shared.drivers;
        this.counters = shared.counters;
        this.ring = shared.ring;
        this.seenWindow = shared.seenWindow;
    }

    /** The loop's counters this registry was handed, or {@code null}. */
    public LoopCounters counters() {
        return counters;
    }

    /** The flight recorder every session shares, or {@code null} when the probe is off. */
    public Recorder recorder() {
        return ring.recorder();
    }

    /** The same windows and the same drivers, with a choice of its own that starts at the main window. */
    @Override
    public Commands session() {
        return new Windows(this);
    }

    @Override
    public boolean drives(String line) {
        String[] parts = (line == null ? "" : line.trim()).split("\\s+", 2);
        return Automation.driving(parts[0].toLowerCase(Locale.ROOT), parts.length > 1 ? parts[1] : "");
    }

    /**
     * The ring's verbs, {@code stream}, and the domains are diagnostics, as they are answered before any window is;
     * the rest is whatever the window's own driver says ({@link Automation#needs(String)}).
     */
    @Override
    public Capability needs(String line) {
        String verb = (line == null ? "" : line.trim()).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (RecorderVerbs.owns(verb) || verb.equals("stream") || Domains.owns(verb)) {
            return Capability.DIAGNOSTICS;
        }
        return Automation.verbNeeds(verb);
    }

    /** Every window's pointer: there is one hand, whichever window it was last used in. */
    @Override
    public void letGo() {
        List<Automation> all;
        synchronized (drivers) {
            all = List.copyOf(drivers.values());
        }
        all.forEach(Automation::letGo);
    }

    /**
     * {@code hello}'s lines for this host: the windows open now, whether the probe is on, the recorder's state, and
     * each domain — present, or {@code missing(...)} with what it waits for, from the same table the unbuilt verbs
     * answer from ({@link Domains}).
     */
    @Override
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        List<WindowView> open = source.get();
        lines.add("windows " + (open.isEmpty() ? "-" : String.join(",", names(open))));
        lines.add("probe   " + (sibarum.probe.Probe.ON ? "on" : "off"));
        Recorder r = ring.recorder();
        lines.add("recorder " + (r == null ? "off" : "on rows=" + r.held() + " capacity_rows=" + r.capacity()
                + " lost_total=" + r.lost() + " frame_stamp=" + (r.stamped() ? "yes" : "no")));
        lines.add("domain  loop=" + (counters != null ? "yes" : "no") + " recorder=" + (r != null ? "yes" : "no")
                + " stream=" + (r != null ? "text" : "no") + Domains.line());
        return lines;
    }

    /** {@code stream}, over the ring every session shares; see {@link RecorderVerbs#stream}. */
    @Override
    public Streaming streaming(String line) {
        String[] parts = (line == null ? "" : line.trim()).split("\\s+", 2);
        if (!parts[0].equalsIgnoreCase("stream")) {
            return null;
        }
        return ring.stream(parts.length > 1 ? parts[1].trim() : "");
    }

    @Override
    public String command(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        try {
            // The ring is the process's, not a window's: it answers before any window is asked for, or open.
            if (RecorderVerbs.owns(verb)) {
                return ring.answer(verb, rest);
            }
            if (Domains.owns(verb)) {
                return Domains.answer(verb);
            }
            List<WindowView> open = firstWindows();
            synchronized (drivers) {
                drivers.keySet().removeIf(gui -> open.stream().noneMatch(v -> v.gui() == gui));
            }
            if (open.isEmpty()) {
                return "err no window is open yet";
            }
            if (verb.equals("windows")) {
                return list(open);
            }
            if (verb.equals("window")) {
                return choose(open, rest);
            }
            WindowView target = addressed(open, verb, rest);
            if (target == null) {
                target = current(open);
            }
            if (target == null) {
                return GONE;
            }
            String reply = driver(target).command(trimmed);
            return verb.equals("help") ? reply + "\n" + HELP + "\n" + RecorderVerbs.HELP : reply;
        } catch (RuntimeException e) {
            return "err " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * What is open now — except before the first window has ever opened, when it waits for one, up to
     * {@link #FIRST_WINDOW_TIMEOUT_MS}.
     *
     * <p><b>The socket opens before the window does.</b> {@code Driver.open} prints its port as it binds, and the
     * first window comes up seconds later, so a client that starts driving on the port line — {@code ottermate
     * --launch}, or a script attached to an application still starting — met {@code err no window is open yet} on
     * every window verb, {@code settle} included. Waiting here serves both, where waiting in the client would serve
     * only the launch. Once any window has been seen an empty list is answered at once: every window closing is a
     * fact about the application, not a start that is still on its way.
     */
    private List<WindowView> firstWindows() {
        List<WindowView> open = source.get();
        long deadline = System.nanoTime() + FIRST_WINDOW_TIMEOUT_MS * 1_000_000L;
        while (open.isEmpty() && !seenWindow.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(FIRST_WINDOW_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return open;
            }
            open = source.get();
        }
        if (!open.isEmpty()) {
            seenWindow.set(true);
        }
        return open;
    }

    /** The chosen window, the main one if none was chosen, or {@code null} if the chosen one has closed. */
    private WindowView current(List<WindowView> open) {
        if (chosen == null) {
            return open.stream().filter(WindowView::main).findFirst().orElse(open.get(0));
        }
        return open.stream().filter(v -> v.gui() == chosen).findFirst().orElse(null);
    }

    /**
     * The window a command's own target names, for that command only — or {@code null} when it names none, and the
     * session's choice applies.
     *
     * <p>A target written as an {@link Address} — {@code click viewport/view.canvas}, {@code go prefs/theme.accent},
     * {@code await viewport/view.state ready} — carries its window with it, the same {@code window/landmark} a
     * hyperlink and {@code GuiApp}'s routing already use. So it goes to the driver of the window it names, and
     * {@code window} is needed only for the verbs with no target ({@code tree}, {@code shot}, {@code size}). The
     * session's choice is not moved: a command that names its window says nothing about the next one.
     *
     * <p>Only the arguments that are targets are read as addresses: a {@code shot} path or a {@code type} text may
     * contain a slash too. A window name that is not open is left to the chosen window's driver, whose
     * {@code go} forwards it on the bus — which is how a closed named window gets opened — and whose other verbs
     * say it is another window's.
     */
    private WindowView addressed(List<WindowView> open, String verb, String rest) {
        int targets = switch (verb) {
            case "move", "click", "rightclick", "down", "up", "scroll", "touch", "tap", "go", "await" -> 1;
            case "drag" -> 2;
            default -> 0;
        };
        String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        WindowView found = null;
        for (int i = 0; i < Math.min(targets, args.length); i++) {
            int slash = args[i].indexOf(Address.SEPARATOR);
            if (slash <= 0) {
                continue;
            }
            String window = args[i].substring(0, slash);
            WindowView named = open.stream().filter(v -> window.equals(v.name())).findFirst().orElse(null);
            if (named == null) {
                continue;
            }
            if (found != null && found != named) {
                throw new IllegalArgumentException("a " + verb + " cannot start in one window and end in another");
            }
            found = named;
        }
        return found;
    }

    /**
     * The driver for a window's tree, made once and kept while the tree is open. Its controls are looked up each
     * time it is asked for them rather than taken from this view: a named window closed and reopened between two
     * commands is the same tree with new controls, and a driver holding the old ones would photograph a window
     * that is gone.
     */
    private Automation driver(WindowView view) {
        Gui gui = view.gui();
        synchronized (drivers) {
            return drivers.computeIfAbsent(gui, g -> Automation.following(g, () -> controlsOf(g), timeline));
        }
    }

    /** The controls of the window showing {@code gui} now, or {@code null} when none is. */
    private WindowControls controlsOf(Gui gui) {
        return source.get().stream().filter(v -> v.gui() == gui).map(WindowView::controls).findFirst().orElse(null);
    }

    private String list(List<WindowView> open) {
        List<String> names = names(open);
        WindowView now = current(open);
        StringBuilder sb = new StringBuilder("ok ").append(open.size());
        for (int i = 0; i < open.size(); i++) {
            WindowView v = open.get(i);
            String view = driver(v).size().substring("ok ".length());
            sb.append('\n').append(i + 1).append(' ').append(names.get(i)).append(' ').append(view);
            if (v == now) {
                sb.append(" *");
            }
        }
        return sb.toString();
    }

    private String choose(List<WindowView> open, String which) {
        List<String> names = names(open);
        if (which.isEmpty()) {
            WindowView now = current(open);
            return now == null ? GONE : "ok " + names.get(open.indexOf(now));
        }
        int at;
        if (which.chars().allMatch(Character::isDigit)) {
            int n = Integer.parseInt(which);
            at = n >= 1 && n <= open.size() ? n - 1 : -1;
        } else {
            at = names.indexOf(which);
            if (at < 0) {
                String lower = which.toLowerCase(Locale.ROOT);
                List<Integer> beginnings = new ArrayList<>();
                for (int i = 0; i < names.size(); i++) {
                    if (names.get(i).toLowerCase(Locale.ROOT).startsWith(lower)) {
                        beginnings.add(i);
                    }
                }
                if (beginnings.size() > 1) {
                    return "err '" + which + "' begins more than one window name; windows lists them";
                }
                at = beginnings.isEmpty() ? -1 : beginnings.get(0);
            }
        }
        if (at < 0) {
            return "err no window '" + which + "'; windows lists what is open";
        }
        chosen = open.get(at).gui();
        return "ok " + names.get(at);
    }

    /** Names made unique: a second window of one name is {@code name#2}, so a name always picks exactly one. */
    private static List<String> names(List<WindowView> open) {
        List<String> names = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (WindowView v : open) {
            String base = v.name() == null || v.name().isBlank() ? "window" : v.name().trim().replaceAll("\\s+", "-");
            int n = seen.merge(base, 1, Integer::sum);
            names.add(n == 1 ? base : base + "#" + n);
        }
        return names;
    }
}
