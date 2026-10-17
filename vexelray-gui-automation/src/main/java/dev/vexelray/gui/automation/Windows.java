package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.LoopCounters;
import dev.vexelray.gui.core.app.WindowView;
import dev.vexelray.gui.recorder.Recorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <ul>
 *   <li>{@code windows} — what is open: a number, a name, the size and view factors, and {@code *} on the chosen one;</li>
 *   <li>{@code window <name|number>} — choose one, by its exact name, a unique beginning of it, or its number.</li>
 * </ul>
 *
 * <p>The flight recorder's verbs ({@code recorder}, {@code rows}, {@code gaps}, {@code frames}, {@code why}) are
 * answered here too, before any window is asked for: the ring is the process's, not a window's. See
 * {@link RecorderVerbs}.
 */
public final class Windows implements Commands {

    private static final String GONE = "err the window chosen earlier has closed; windows lists what is open";

    private static final String HELP = String.join("\n",
            "windows                  the open windows: number, name, size and view; * is the chosen one",
            "window <name|number>     choose the window the other commands act on (default: the main one)");

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
    }

    private Windows(Windows shared) {
        this.source = shared.source;
        this.timeline = shared.timeline;
        this.drivers = shared.drivers;
        this.counters = shared.counters;
        this.ring = shared.ring;
    }

    /** The loop's counters this registry was handed, or {@code null}. */
    public LoopCounters counters() {
        return counters;
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

    /** Every window's pointer: there is one hand, whichever window it was last used in. */
    @Override
    public void letGo() {
        List<Automation> all;
        synchronized (drivers) {
            all = List.copyOf(drivers.values());
        }
        all.forEach(Automation::letGo);
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
            List<WindowView> open = source.get();
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
            WindowView target = current(open);
            if (target == null) {
                return GONE;
            }
            String reply = driver(target).command(trimmed);
            return verb.equals("help") ? reply + "\n" + HELP + "\n" + RecorderVerbs.HELP : reply;
        } catch (RuntimeException e) {
            return "err " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** The chosen window, the main one if none was chosen, or {@code null} if the chosen one has closed. */
    private WindowView current(List<WindowView> open) {
        if (chosen == null) {
            return open.stream().filter(WindowView::main).findFirst().orElse(open.get(0));
        }
        return open.stream().filter(v -> v.gui() == chosen).findFirst().orElse(null);
    }

    private Automation driver(WindowView view) {
        synchronized (drivers) {
            return drivers.computeIfAbsent(view.gui(), g -> new Automation(g, view.controls(), timeline));
        }
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
