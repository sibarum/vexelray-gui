package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutSnapshot;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.layout.SemanticSnapshot;
import dev.vexelray.gui.core.model.SemanticNode;
import sibarum.probe.Lane;
import sibarum.probe.Level;
import sibarum.probe.LogConfig;
import sibarum.probe.Logging;
import sibarum.probe.Probe;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;
import sibarum.tactroller.api.TouchpadContact;

import java.util.List;
import java.util.Locale;

/**
 * The commands an agent drives the application with, and the only place a <b>ref</b> becomes a place on screen.
 *
 * <p>A ref is a node id. That is the point of addressing by ref rather than by coordinate: the agent says
 * <em>which</em> thing, and this resolves where it is at the moment of acting. A coordinate an agent read one
 * frame and clicked in another is the classic automation flake, and there is no way to write the retry that
 * fixes it — the click has already landed somewhere.
 *
 * <p>Every read joins the two published read-models by node id and by version
 * ({@link SemanticSnapshot} for what a thing is, {@link LayoutSnapshot} for where it is), so a lookup can never
 * pair meaning from one frame with geometry from another.
 *
 * <p>Everything that acts goes through {@link Cursor}, so it goes out on the ordinary input topic and the
 * pointer travels. Nothing here has a shortcut for "just click it".
 *
 * <p>Safe to call from several threads at once, as long as only one of them {@link #drives drives}: it reads
 * snapshots lock-free and publishes to the bus, and never touches GUI-thread state. {@link AutomationServer}
 * keeps that condition by giving the driving verbs only to the connection holding the hand.
 */
public final class Automation implements Commands {

    /** How long {@link #settle} waits before giving up and saying so. */
    private static final long SETTLE_TIMEOUT_MS = 2_000L;

    /**
     * How long {@link #shot} waits for the picture to land before calling it a failure.
     *
     * <p>Longer than {@link #SETTLE_TIMEOUT_MS} on purpose: a capture builds an offscreen pass and pipeline the
     * first time it is asked at a given size, and it queues behind whatever the frame loop is already doing.
     */
    private static final long SHOT_TIMEOUT_MS = 5_000L;

    /** How long {@code record stop} waits for the file to be finished: the frames still queued, encoded. */
    private static final long RECORD_STOP_TIMEOUT_MS = 30_000L;

    /** How often {@link #shot} looks. Off the frame loop, so this costs the application nothing. */
    private static final long SHOT_POLL_MS = 10L;

    /**
     * How long {@link #await} waits for an application to say it is ready.
     *
     * <p>The most generous of the three, because it is the one waiting on application work rather than on the
     * frame loop: compiling a shader for new geometry is the case it was added for, and that is seconds on a
     * cold pipeline cache.
     */
    private static final long AWAIT_TIMEOUT_MS = 30_000L;

    /**
     * How long {@link #settle} waits for the {@link Timeline} to go quiet, on top of the frame loop.
     *
     * <p>Longer than {@link #SETTLE_TIMEOUT_MS}, because it waits on motion a designer chose the length of rather
     * than on the loop catching up: the transition that found this was three seconds long. Shorter than
     * {@link #AWAIT_TIMEOUT_MS}, because a timeline that is still busy after this long is running something that
     * never ends — a repeating cue — and the honest answer then is to say so.
     */
    private static final long TIMELINE_TIMEOUT_MS = 10_000L;

    /** How often {@link #settle} looks at the timeline. Off the frame loop, so this costs the application nothing. */
    private static final long TIMELINE_POLL_MS = 10L;

    /** A width and a height, each a number with an optional px or em, parted by x or by space. */
    private static final java.util.regex.Pattern SIZE = java.util.regex.Pattern.compile(
            "(\\d+(?:\\.\\d+)?(?:px|em)?)\\s*(?:x|\\s)\\s*(\\d+(?:\\.\\d+)?(?:px|em)?)");

    private final Gui gui;
    /**
     * Asked on every use, never held: a window's controls do not exist until its OS window does, and a named window
     * closed and reopened is handed a fresh set for the same tree (docs/plans/todo.md §6.9).
     */
    private final java.util.function.Supplier<WindowControls> controls;
    private final Timeline timeline;
    private final long timelineTimeoutMs;
    private final Cursor cursor;
    /** The host's pointer warp while this driver holds the pointer, {@code null} when it does not. See {@link #holdPointer}. */
    private dev.vexelray.gui.core.input.PointerWarp hostWarp;
    /**
     * The keys {@code keydown} pressed and nothing has released yet, in the order they went down. Its own lock: the
     * holder's thread presses, and {@link #letGo} releases from whichever thread ends the session.
     */
    private final List<Key> heldKeys = new java.util.ArrayList<>();

    public Automation(Gui gui) {
        this(gui, WindowControls.NONE);
    }

    public Automation(Gui gui, WindowControls controls) {
        this(gui, controls, Timeline.NONE);
    }

    /**
     * A driver whose {@link #settle} also waits for {@code timeline} to go quiet — what a host with a clock hands
     * over, so a transition in flight is not photographed as finished. See {@link Timeline}.
     */
    public Automation(Gui gui, WindowControls controls, Timeline timeline) {
        this(gui, constant(controls), timeline, TIMELINE_TIMEOUT_MS);
    }

    /**
     * A driver that looks its window's controls up per command — what {@link Windows} hands every window, and what
     * a host whose window opens after the driver, or reopens under the same tree, needs. A supplier answering
     * {@code null} means the window is not open now, and {@code shot} and {@code resize} then say so.
     */
    public static Automation following(Gui gui, java.util.function.Supplier<WindowControls> controls,
                                       Timeline timeline) {
        return new Automation(gui, controls, timeline, TIMELINE_TIMEOUT_MS);
    }

    /** With a shorter timeline bound, so a test of the never-quiet case does not take ten seconds to fail. */
    Automation(Gui gui, WindowControls controls, Timeline timeline, long timelineTimeoutMs) {
        this(gui, constant(controls), timeline, timelineTimeoutMs);
    }

    private Automation(Gui gui, java.util.function.Supplier<WindowControls> controls, Timeline timeline,
                       long timelineTimeoutMs) {
        this.gui = java.util.Objects.requireNonNull(gui, "gui");
        java.util.Objects.requireNonNull(controls, "controls");
        this.controls = () -> {
            WindowControls now = controls.get();
            return now == null ? WindowControls.NONE : now;
        };
        this.timeline = timeline == null ? Timeline.NONE : timeline;
        this.timelineTimeoutMs = timelineTimeoutMs;
        // Waking, because this host loop is entitled to be parked: injected input is neither a mutation nor an
        // OS event, so it is the one reason a frame can be owed that nothing else reports. See
        // Gui.wakeForInput, and §6's promise that this module has no interesting failure modes of its own.
        this.cursor = new Cursor(gui.bus(), gui::wakeForInput);
    }

    private static java.util.function.Supplier<WindowControls> constant(WindowControls controls) {
        WindowControls fixed = controls == null ? WindowControls.NONE : controls;
        return () -> fixed;
    }

    /** The pointer this driver moves. One hand. */
    public Cursor cursor() {
        return cursor;
    }

    /**
     * Run one command line and return what to print. Never throws: a driver that dies on a bad command is a
     * driver that loses the session it was in the middle of investigating.
     */
    @Override
    public String command(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1] : "";
        try {
            if (driving(verb, rest)) {
                holdPointer();
            }
            return run(verb, rest);
        } catch (RuntimeException e) {
            return "err " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @Override
    public boolean drives(String line) {
        String trimmed = line == null ? "" : line.trim();
        String[] parts = trimmed.split("\\s+", 2);
        return driving(parts[0].toLowerCase(Locale.ROOT), parts.length > 1 ? parts[1] : "");
    }

    @Override
    public void letGo() {
        cursor.letGo();
        releaseHeldKeys();
        synchronized (this) {
            if (hostWarp != null) {
                gui.onPointerWarp(hostWarp);
                hostWarp = null;
            }
        }
    }

    /**
     * From the first line that drives until {@link #letGo}, a {@code PointerRide} carries this driver's pointer
     * and not the OS one (docs/plans/todo.md §9.5). The pointer the application sees is the one this cursor
     * publishes, so a cancelled drag flying home has to move <em>it</em>: moving the real cursor instead jumped the
     * mouse of whoever sits at the desk into the window for the length of the flight, and the next poll reported the
     * jump as motion. The host's warp is kept and put back when the hand is let go, so a person who takes over
     * afterwards gets their rides again.
     */
    private synchronized void holdPointer() {
        if (hostWarp == null) {
            hostWarp = gui.pointerWarp();
            gui.onPointerWarp(cursor::carry);
        }
    }

    /**
     * Whether {@code verb} acts on the application rather than reading it: the pointer and keyboard verbs, the view
     * verbs when given a value, and {@code go}. {@code go} is not input, but it moves focus and scrolls, which is
     * where the holder's next {@code type} lands and what its next {@code click} aims through — an observer that
     * could do it would interleave with the holder exactly as a second pointer would.
     *
     * <p>Everything else reads, waits ({@code settle}, {@code await}), photographs ({@code shot}), annotates the
     * log ({@code mark}), or turns logging up and down ({@code log}), none of which changes what the application
     * does next. Static so {@link Windows} can answer without choosing a window first.
     */
    static boolean driving(String verb, String rest) {
        return switch (verb) {
            case "move", "click", "rightclick", "drag", "down", "up", "scroll", "touch", "tap", "type", "key",
                 "keydown", "keyup", "go", "hide", "show" -> true;
            case "zoom", "dpi", "resize" -> !rest.isBlank();
            default -> false;
        };
    }

    /**
     * {@code log} and {@code mark} are diagnostics: they read and annotate what the process records, and say
     * nothing about where the hand goes. Everything else here is control — the hand, and the reads it is aimed
     * through. Static for the reason {@link #driving} is.
     */
    static Capability verbNeeds(String verb) {
        return verb.equals("log") || verb.equals("mark") ? Capability.DIAGNOSTICS : Capability.CONTROL;
    }

    @Override
    public Capability needs(String line) {
        return verbNeeds((line == null ? "" : line.trim()).split("\\s+", 2)[0].toLowerCase(Locale.ROOT));
    }

    private String run(String verb, String rest) {
        return switch (verb) {
            case "tree" -> tree();
            case "find" -> find(rest);
            case "where" -> "ok " + cursor.x() + "," + cursor.y();
            case "move" -> act(rest, (x, y) -> cursor.moveTo(x, y));
            case "click" -> act(rest, (x, y) -> cursor.click(x, y));
            case "rightclick" -> act(rest, (x, y) -> cursor.click(x, y, MouseButton.RIGHT));
            case "drag" -> drag(rest);
            case "down" -> act(rest, (x, y) -> cursor.moveTo(x, y).press(MouseButton.LEFT));
            case "up" -> rest.isBlank()
                    ? release()
                    : act(rest, (x, y) -> cursor.moveTo(x, y).release(MouseButton.LEFT));
            case "scroll" -> scroll(rest);
            case "touch" -> touch(rest);
            case "tap" -> tap(rest);
            case "type" -> type(rest);
            case "key" -> key(rest);
            case "keydown" -> keyDown(rest);
            case "keyup" -> keyUp(rest);
            case "go" -> go(rest);
            case "settle" -> settle();
            case "await" -> await(rest);
            case "shot" -> shot(rest);
            case "record" -> record(rest);
            case "size" -> size();
            case "zoom" -> zoom(rest);
            case "dpi" -> dpi(rest);
            case "resize" -> resize(rest);
            case "hide" -> visibility(false);
            case "show" -> visibility(true);
            case "mark" -> mark(rest);
            case "log" -> log(rest);
            case "help" -> HELP;
            default -> "err no command '" + verb + "'; try help";
        };
    }

    // --- reading -----------------------------------------------------------

    /**
     * The tree an agent addresses, as an indented outline: ref, role, name, and box.
     *
     * <p>Only what has a role, a name, or children worth descending into. A dump of every box in the tree is
     * technically more information and practically less: the thing being looked for is buried in it.
     */
    public String tree() {
        SemanticSnapshot sem = gui.semanticSnapshot();
        LayoutSnapshot layout = gui.layoutSnapshot();
        if (!sem.root().present()) {
            return "ok (no tree yet)";
        }
        StringBuilder sb = new StringBuilder("ok v").append(sem.version()).append('\n');
        describe(sem, layout, sem.rootId(), 0, sb);
        return sb.toString().stripTrailing();
    }

    private void describe(SemanticSnapshot sem, LayoutSnapshot layout, long id, int depth, StringBuilder sb) {
        SemanticNode n = sem.node(id);
        if (!n.present()) {
            return;
        }
        // A landmark makes a node interesting on its own. Being named is the strongest possible statement that
        // someone meant this node to be addressed from outside, so a bare box that has been given a name --
        // a plot canvas, a drop zone -- was the one kind of node this outline would not list. A scroller is listed
        // too: the wheel goes to it, and an unnamed one inside another was a second scrollbar nobody could find
        // here (docs/reference/scrolling.md §3).
        NodeLayout scrolls = layout.node(id);
        boolean interesting = !n.role().isEmpty() || !n.name().isEmpty() || n.focusable()
                || !n.landmark().isEmpty() || scrolls.overflowX() || scrolls.overflowY();
        if (interesting) {
            sb.append("  ".repeat(Math.min(depth, 12)))
                    .append(id).append(' ')
                    .append(n.role().isEmpty() ? n.kind().name().toLowerCase(Locale.ROOT) : n.role());
            if (!n.name().isEmpty()) {
                sb.append(" \"").append(n.name()).append('"');
            }
            if (!n.landmark().isEmpty()) {
                // Shown so the agent reaches for the durable name first. An id is minted per run; a landmark
                // means the same thing tomorrow, and unlike an id it can be navigated to.
                sb.append(" @").append(n.landmark());
            }
            NodeLayout box = layout.node(id);
            if (box.present()) {
                Rect r = box.rect();
                sb.append(" [").append(round(r.x())).append(',').append(round(r.y()))
                        .append(' ').append(round(r.w())).append('x').append(round(r.h())).append(']');
                sb.append(clipping(box)).append(scrolling(box));
            }
            if (!n.visible()) {
                // Said explicitly, because a rect is published for a hidden node too and finding one proves
                // nothing about whether it can be seen or clicked (docs/reference/semantic-read-model.md §6).
                sb.append(" hidden");
            }
            if (n.disabled()) {
                // Said for the same reason: a disabled control draws, lays out and is hit by the pointer, and
                // nothing in its rect says that a click on it does nothing.
                sb.append(" disabled");
            }
            if (n.focused()) {
                sb.append(" focused");
                if (n.ringShown()) {
                    // Said apart from focus, because the ring can be off for a node that holds it (a clicked
                    // button under a keyboard-only ring, a node that opted out), and that is what a reader of a
                    // screenshot would otherwise have to judge from pixels.
                    sb.append(" ring");
                }
            }
            sb.append('\n');
        }
        for (long child : n.children()) {
            describe(sem, layout, child, interesting ? depth + 1 : depth, sb);
        }
    }

    /**
     * Refs whose role, name or <b>landmark</b> contains {@code query}, case-insensitively.
     *
     * <p>The landmark is matched and not merely displayed, and leaving it out was a hole rather than a
     * simplification: a landmark exists precisely to name a node that cannot otherwise be picked out, so the
     * nodes that most need finding are the ones with no role and no text. A ray-marched canvas is a bare box —
     * {@code find} could not see it — and a caption button labelled {@code ↗} has a name no one will type. So
     * the durable name this document tells agents to write down was the one thing {@code find} would not
     * accept, and an agent following the advice got "nothing matches" for a node that was right there.
     */
    public String find(String query) {
        if (query.isBlank()) {
            return "err find needs something to look for";
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        SemanticSnapshot sem = gui.semanticSnapshot();
        LayoutSnapshot layout = gui.layoutSnapshot();
        StringBuilder sb = new StringBuilder();
        int found = 0;
        for (SemanticNode n : sem.nodes().values()) {
            if (!n.role().toLowerCase(Locale.ROOT).contains(needle)
                    && !n.name().toLowerCase(Locale.ROOT).contains(needle)
                    && !n.landmark().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            found++;
            sb.append(n.id()).append(' ').append(n.role().isEmpty() ? "-" : n.role())
                    .append(" \"").append(n.name()).append('"');
            if (!n.landmark().isEmpty()) {
                sb.append(" @").append(n.landmark());
            }
            NodeLayout box = layout.node(n.id());
            if (box.present()) {
                Rect r = box.rect();
                sb.append(" [").append(round(r.x())).append(',').append(round(r.y()))
                        .append(' ').append(round(r.w())).append('x').append(round(r.h())).append(']');
                sb.append(clipping(box)).append(scrolling(box));
            }
            if (!n.visible()) {
                sb.append(" hidden");
            }
            if (n.disabled()) {
                sb.append(" disabled");   // as tree says it: a find that hides it answers the wrong question
            }
            sb.append('\n');
        }
        return found == 0 ? "ok (nothing matches '" + query.trim() + "')"
                : "ok " + found + "\n" + sb.toString().stripTrailing();
    }

    // --- acting ------------------------------------------------------------

    /** A target: either {@code x,y} or a ref, resolved against the layout snapshot at the moment of acting. */
    private int[] target(String spec) {
        String s = spec.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("needs a ref or x,y");
        }
        if (s.indexOf(',') >= 0) {
            String[] xy = s.split(",", 2);
            return new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())};
        }
        long id = s.chars().allMatch(Character::isDigit) ? Long.parseLong(s) : landmarkId(here(s));
        SemanticNode what = gui.semanticSnapshot().node(id);
        if (!what.present()) {
            throw new IllegalArgumentException("no node " + s);
        }
        String why = unreachable(id);
        if (why != null) {
            // Not a refusal if the framework can do something about it. A landmark is *reachable*: navigating
            // to one asks every container concealing it to un-conceal it (Reveal — the selected tab, the
            // collapsed branch, the closed drawer) and scrolls it into view. That is the hardest problem in UI
            // automation, already solved here, and re-solving it from the outside would mean this class
            // enumerating the containers that can hide something — an enumeration that is never finished.
            if (what.landmark().isEmpty()) {
                throw new IllegalArgumentException("node " + id + " " + why + ", and has no landmark to "
                        + "navigate to; only a named node can ask to be revealed");
            }
            go(what.landmark());
            String still = awaitReachable(id);
            if (still != null) {
                throw new IllegalArgumentException("navigated to '" + what.landmark()
                        + "' and it " + still);
            }
        }
        // The centre of what is *on screen*, not of where the node was put. For an unclipped node the two are
        // the same point; for a row scrolled half out of its list they are not, and the difference is a click
        // that lands on the row against one that lands on whatever the clip put there instead.
        Rect r = gui.layoutSnapshot().node(id).visibleRect();
        return new int[]{Math.round(r.centreX()), Math.round(r.centreY())};
    }

    /**
     * Why {@code id} cannot be acted on where it is, as a phrase that completes "node 42 …" — or {@code null}
     * when it can.
     *
     * <p>Three ways to be out of reach and one answer to all three, because the caller does the same thing
     * about each: ask the framework to reveal it, and refuse if it will not. They are asked in the order they
     * can be known — a node absent from the semantic snapshot has no geometry to ask about — and the phrase
     * says which one it was, since "not visible" and "scrolled out of view" are different bugs when the reason
     * an agent is here is that its click went somewhere else.
     */
    private String unreachable(long id) {
        SemanticNode what = gui.semanticSnapshot().node(id);
        if (!what.visible()) {
            return "is not visible";
        }
        NodeLayout box = gui.layoutSnapshot().node(id);
        if (!box.present()) {
            return "has no laid-out box";
        }
        if (box.clippedAway()) {
            // The state a virtualised list is full of: the row exists, it has a rect, and every pixel of that
            // rect belongs to something else. Clicking its centre is how an agent sorts a table it meant to
            // select a row of, and gets `ok` for it.
            return "is scrolled out of view (its box is clipped away by an ancestor)";
        }
        return null;
    }

    /**
     * Wait, bounded, for a navigation's effect to arrive — {@code null} once {@code id} can be acted on, and
     * the reason it still cannot if it never becomes so.
     *
     * <p>Arrival says the walk is over, not that the frame carrying its last act has been published: that act
     * is a scroll, and a scroll is not true until it has been laid out. Rechecking against whatever snapshot
     * happened to be current would refuse a node that had in fact just arrived — and only sometimes, which is
     * the worst way for an instrument to be wrong.
     *
     * <p>Waits on the condition rather than on {@code frameOwed}: by this point the mutation has drained and
     * nothing is owed, so {@link #settle} is exact and answers about the wrong question. It is the same shape
     * as {@link #await} for the same reason — the only honest signal is the thing being waited for.
     */
    private String awaitReachable(long id) {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT_MS * 1_000_000L;
        java.util.concurrent.Semaphore published = new java.util.concurrent.Semaphore(0);
        String why;
        try (var sub = gui.layout().onCommit(v -> published.release())) {
            // Subscribed before the condition is read, never after: the frame carrying the scroll can land in
            // between, and layout does not republish when nothing has moved — so a wait armed afterwards is a
            // wait for a commit that is never coming again.
            why = unreachable(id);
            while (why != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0
                        || !published.tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    break;
                }
                why = unreachable(id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "was interrupted while being waited for";
        }
        return why;
    }

    /**
     * The landmark an address names, once it is known to be in this window: {@code view.canvas} and
     * {@code viewport/view.canvas} are the same node to the viewport's driver. An address naming another window is
     * refused rather than looked up here, where the same name may be a different landmark; {@link Windows} hands
     * such a command to that window's driver before it gets this far.
     */
    private String here(String spec) {
        if (spec.indexOf(dev.vexelray.gui.core.nav.Address.SEPARATOR) < 0) {
            return spec;
        }
        var address = dev.vexelray.gui.core.nav.Address.parse(spec);
        String mine = gui.windowKey();
        if (address.windowNamed() && !mine.isEmpty() && !address.window().equals(mine)) {
            throw new IllegalArgumentException("'" + spec + "' is in window '" + address.window()
                    + "', and this driver is window '" + mine + "'");
        }
        return address.landmark();
    }

    /** The node registered under {@code landmark}, or a complaint naming what is available. */
    private long landmarkId(String landmark) {
        for (SemanticNode n : gui.semanticSnapshot().nodes().values()) {
            if (n.landmark().equals(landmark)) {
                return n.id();
            }
        }
        throw new IllegalArgumentException("no landmark '" + landmark + "'; try tree");
    }

    /**
     * Navigate to a landmark, revealing and scrolling to it, and wait for it to arrive.
     *
     * <p>The framework's own navigation, unchanged — the same call a hyperlink makes. An {@code Address} is a
     * value precisely so that a link, a macro and a test all end in the same place, and a driver that walked
     * the tree itself would be a fourth route that agrees with the other three only by luck.
     */
    public String go(String address) {
        String spec = address.trim();
        if (spec.isEmpty()) {
            return "err go needs an address";
        }
        var navigation = gui.navigate(dev.vexelray.gui.core.nav.Address.parse(spec));
        try {
            navigation.arrival().get(SETTLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "err interrupted";
        } catch (java.util.concurrent.ExecutionException e) {
            return "err " + e.getCause().getMessage();
        } catch (java.util.concurrent.TimeoutException e) {
            return "err did not arrive at '" + spec + "' within " + SETTLE_TIMEOUT_MS + "ms";
        }
        settle();
        return "ok " + spec;
    }

    private String act(String spec, java.util.function.BiConsumer<Integer, Integer> what) {
        int[] xy = target(spec);
        what.accept(xy[0], xy[1]);
        return "ok " + cursor.x() + "," + cursor.y();
    }

    private String drag(String rest) {
        String[] args = rest.trim().split("\\s+");
        if (args.length != 2) {
            return "err drag needs a source and a target";
        }
        int[] from = target(args[0]);
        int[] to = target(args[1]);
        cursor.moveTo(from[0], from[1]);
        cursor.drag(to[0], to[1], MouseButton.LEFT);
        return "ok " + cursor.x() + "," + cursor.y();
    }

    /**
     * Let go where the pointer is: the end of a {@code down}. Apart from {@code drag} because a gesture held open
     * is one that can be looked at — a card half-carried, a gap half-open — and {@code drag} is over by the time
     * anything could photograph it.
     */
    private String release() {
        cursor.release(MouseButton.LEFT);
        return "ok " + cursor.x() + "," + cursor.y();
    }

    private String scroll(String rest) {
        String[] args = rest.trim().split("\\s+");
        if (args.length != 3) {
            return "err scroll needs a target and dx dy";
        }
        int[] at = target(args[0]);
        cursor.moveTo(at[0], at[1]);
        cursor.scroll(Double.parseDouble(args[1]), Double.parseDouble(args[2]));
        return "ok";
    }

    /** Where synthesized fingers are put down on a pretend touchpad 100 x 70 mm, and how far apart, in mm. */
    private static final double PAD_W = 100;
    private static final double PAD_H = 70;
    private static final double FINGER_RADIUS = 12;
    /** How often a touchpad reports while fingers move: about what a precision touchpad does. */
    private static final long TOUCH_FRAME_MS = 8;

    /**
     * Fingers on the touchpad, with the pointer over a target: {@code touch <ref|x,y> <fingers> <dx> <dy> [scale]
     * [turn] [ms]}. They land in a ring about the pad's centre, then over {@code ms} (200 by default) their centre
     * moves {@code (dx, dy)} millimetres, their spread grows by {@code scale} and they turn {@code turn} degrees
     * clockwise, and they lift. A swipe is {@code touch @x 3 -30 0}; a pinch, {@code touch @x 2 0 0 2}.
     *
     * <p>Never a gesture event made up here: the contacts are published as the touchpad's driver path publishes
     * them, so what is exercised is the recognizer, the dispatcher's claims and the widget, end to end. In real
     * time, a report every few milliseconds, so the velocities they lift with are real ones.
     */
    private String touch(String rest) {
        String[] a = rest.trim().split("\\s+");
        if (a.length < 4) {
            return "err touch needs a target, fingers, and dx dy in mm";
        }
        int[] at = target(a[0]);
        int n = Integer.parseInt(a[1]);
        double dx = Double.parseDouble(a[2]);
        double dy = Double.parseDouble(a[3]);
        double scale = a.length > 4 ? Double.parseDouble(a[4]) : 1;
        double turn = a.length > 5 ? Math.toRadians(Double.parseDouble(a[5])) : 0;
        long ms = a.length > 6 ? Long.parseLong(a[6]) : 200;
        if (n < 1 || n > 10 || !(scale > 0) || ms < 0) {
            return "err touch needs 1 to 10 fingers, a positive scale and a time";
        }
        cursor.moveTo(at[0], at[1]);
        int steps = (int) Math.max(2, ms / TOUCH_FRAME_MS);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            touchpad(fingers(n, dx * t, dy * t, Math.pow(scale, t), turn * t));
            pause(ms / steps);
        }
        touchpad(List.of());
        return "ok " + cursor.x() + "," + cursor.y();
    }

    /**
     * Fingers touch down together and lift without moving, {@code times} over (once by default), the pointer over
     * a target: {@code tap <ref|x,y> <fingers> [times]}. Two times is a double tap.
     */
    private String tap(String rest) {
        String[] a = rest.trim().split("\\s+");
        if (a.length < 2) {
            return "err tap needs a target and fingers";
        }
        int[] at = target(a[0]);
        int n = Integer.parseInt(a[1]);
        int times = a.length > 2 ? Integer.parseInt(a[2]) : 1;
        if (n < 1 || n > 10 || times < 1) {
            return "err tap needs 1 to 10 fingers, at least once";
        }
        cursor.moveTo(at[0], at[1]);
        for (int i = 0; i < times; i++) {
            touchpad(fingers(n, 0, 0, 1, 0));
            pause(60);
            touchpad(List.of());
            pause(120);
        }
        return "ok " + cursor.x() + "," + cursor.y();
    }

    /** {@code n} fingers in a ring about the pad's centre moved {@code (dx, dy)}, spread {@code s}, turned {@code r}. */
    private static List<TouchpadContact> fingers(int n, double dx, double dy, double s, double r) {
        List<TouchpadContact> out = new java.util.ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            double angle = r + Math.PI + 2 * Math.PI * k / n;   // two fingers side by side, left one first
            double x = PAD_W / 2 + dx + (n == 1 ? 0 : FINGER_RADIUS * s * Math.cos(angle));
            double y = PAD_H / 2 + dy + (n == 1 ? 0 : FINGER_RADIUS * s * Math.sin(angle));
            out.add(new TouchpadContact(k + 1, x, y, x / PAD_W, y / PAD_H));
        }
        return out;
    }

    private void touchpad(List<TouchpadContact> contacts) {
        gui.bus().publish(InputTopics.INPUT, new InputEvent.TouchpadChanged(contacts, System.nanoTime()));
        gui.wakeForInput();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Type {@code text} one code point at a time, through the ordinary character channel.
     *
     * <p>Never a set-the-text shortcut: what is being exercised is the field, and a field that is written to
     * rather than typed into has skipped every claim, every caret move and every edit the typing would have
     * caused.
     */
    private String type(String text) {
        text.codePoints().forEach(cp -> {
            gui.bus().publish(InputTopics.INPUT, new InputEvent.CharTyped(cp, 0));
            gui.wakeForInput();
            if (Probe.ON) {
                Probe.mark(Lane.INPUT, "type", new String(Character.toChars(cp)));
            }
        });
        return "ok " + text.codePointCount(0, text.length());
    }

    /**
     * Press and release a named key — {@code key ENTER}, {@code key TAB} — or a chord, {@code key CTRL+SHIFT+W}.
     *
     * <p>A chord is sent the way a keyboard sends it: each modifier's left-hand key goes down, then the key goes
     * down and up, then the modifiers come up in reverse. There is no modifier field on a key event to fill in
     * instead — the dispatcher derives what is held from the modifier keys it has seen pressed, so a chord sent
     * any other way would reach a claim as the bare key.
     */
    private String key(String rest) {
        Chord chord = chord(rest);
        if (chord.error() != null) {
            return chord.error();
        }
        if (isHeld(chord.key())) {
            return "err " + chord.key().name() + " is held by keydown, so it cannot be pressed again; keyup it first";
        }
        // A modifier already held by keydown stays held: pressing it again is not what a hand on a keyboard can do,
        // and releasing it at the end of the chord would let go of something this command did not take hold of.
        List<Key> mods = new java.util.ArrayList<>();
        for (Key mod : chord.modifiers()) {
            if (!isHeld(mod)) {
                mods.add(mod);
            }
        }
        for (Key mod : mods) {
            press(mod);
        }
        press(chord.key());
        release(chord.key());
        for (int i = mods.size() - 1; i >= 0; i--) {
            release(mods.get(i));
        }
        String named = chord.named();
        if (Probe.ON) {
            Probe.mark(Lane.INPUT, "key", named);
        }
        return "ok " + named;
    }

    /**
     * Press keys and keep them down until {@link #keyUp}, or until the hand is let go: {@code keydown SHIFT},
     * {@code keydown CTRL+ALT}. The same names and the same order as {@link #key}, and nothing is released.
     *
     * <p>For an application that reads a held modifier as intent — a shift-click that extends a selection, a
     * preview of what a held Control would do — where the thing worth photographing is the state <em>while</em> the
     * key is down. A non-modifier key held this way is held as a real one is, so the dispatcher's auto-repeat fires
     * it on its own clock until it comes back up.
     *
     * <p>Refuses a key already held, before pressing anything: a key cannot go down twice, and a chord that went
     * part-way down would leave the caller guessing which part.
     */
    private String keyDown(String rest) {
        Chord chord = chord(rest);
        if (chord.error() != null) {
            return chord.error();
        }
        List<Key> keys = chord.keys();
        synchronized (heldKeys) {
            for (Key k : keys) {
                if (heldKeys.contains(k)) {
                    return "err " + k.name() + " is already held; held: " + heldNames();
                }
            }
            for (Key k : keys) {
                heldKeys.add(k);
                press(k);
            }
        }
        if (Probe.ON) {
            Probe.mark(Lane.INPUT, "keydown", chord.named());
        }
        return "ok " + chord.named() + " held: " + heldNames();
    }

    /**
     * Release keys {@link #keyDown} is holding, in the reverse of the order written ({@code keyup CTRL+ALT} lets go of
     * ALT, then CONTROL); with nothing named, release everything held, last pressed first.
     *
     * <p>Refuses a key that is not held, before releasing anything: a release with no press is an event no keyboard
     * produces, and a dispatcher that saw one would be reasoning about a key it was never told went down.
     */
    private String keyUp(String rest) {
        if (rest.isBlank()) {
            List<Key> released = releaseHeldKeys();
            StringBuilder named = new StringBuilder();
            for (Key k : released) {
                named.append(named.isEmpty() ? "" : "+").append(k.name());
            }
            return released.isEmpty() ? "ok nothing was held" : "ok released " + named;
        }
        Chord chord = chord(rest);
        if (chord.error() != null) {
            return chord.error();
        }
        List<Key> keys = chord.keys();
        synchronized (heldKeys) {
            for (Key k : keys) {
                if (!heldKeys.contains(k)) {
                    return "err " + k.name() + " is not held; held: " + heldNames();
                }
            }
            for (int i = keys.size() - 1; i >= 0; i--) {
                heldKeys.remove(keys.get(i));
                release(keys.get(i));
            }
        }
        if (Probe.ON) {
            Probe.mark(Lane.INPUT, "keyup", chord.named());
        }
        return "ok " + chord.named() + " held: " + heldNames();
    }

    /**
     * Release everything {@link #keyDown} holds, last pressed first, and say what that was. What {@link #letGo} does,
     * so a session that ends — {@code quit}, a dropped connection, {@code hand release} — never leaves a key down in
     * the application for whoever sits at it next.
     */
    private List<Key> releaseHeldKeys() {
        synchronized (heldKeys) {
            List<Key> released = new java.util.ArrayList<>(heldKeys.size());
            for (int i = heldKeys.size() - 1; i >= 0; i--) {
                Key k = heldKeys.get(i);
                release(k);
                released.add(k);
            }
            heldKeys.clear();
            if (!released.isEmpty() && Probe.ON) {
                Probe.mark(Lane.INPUT, "keyup", "released on letting go");
            }
            return released;
        }
    }

    private boolean isHeld(Key k) {
        synchronized (heldKeys) {
            return heldKeys.contains(k);
        }
    }

    /** Called holding {@link #heldKeys}. */
    private String heldNames() {
        if (heldKeys.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Key k : heldKeys) {
            sb.append(sb.isEmpty() ? "" : "+").append(k.name());
        }
        return sb.toString();
    }

    /**
     * A key with any modifiers before it, {@code MOD+...+KEY}, case-insensitive; or the refusal to reply with, in
     * {@code error}. A modifier named twice is held once.
     */
    private static Chord chord(String rest) {
        String spec = rest.trim();
        String[] parts = spec.split("\\+", -1);
        List<Key> held = new java.util.ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            Key mod = modifierKey(parts[i]);
            if (mod == null) {
                return Chord.refused("err no modifier named '" + parts[i].trim() + "' in '" + spec
                        + "'; try CONTROL (CTRL), SHIFT, ALT or SUPER (META, WIN, CMD)");
            }
            if (!held.contains(mod)) {
                held.add(mod);
            }
        }
        String last = parts[parts.length - 1].trim();
        Key k;
        try {
            k = Key.valueOf(last.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            k = modifierKey(last);    // a modifier on its own, or last in its chord: key SHIFT, key CTRL+ALT
            if (k == null) {
                return Chord.refused("err no key named '" + last + "'");
            }
        }
        return new Chord(List.copyOf(held), k, null);
    }

    /** The modifiers in the order written, then the key; or, with neither, the refusal. */
    private record Chord(List<Key> modifiers, Key key, String error) {

        static Chord refused(String error) {
            return new Chord(List.of(), null, error);
        }

        /** Every key, in the order it goes down, each once: {@code CTRL+CONTROL} is one key, not one pressed twice. */
        List<Key> keys() {
            java.util.LinkedHashSet<Key> all = new java.util.LinkedHashSet<>(modifiers);
            all.add(key);
            return List.copyOf(all);
        }

        /** {@code LEFT_CONTROL+LEFT_SHIFT+W}: what the reply and the probe say went down. */
        String named() {
            StringBuilder sb = new StringBuilder();
            for (Key mod : modifiers) {
                sb.append(mod.name()).append('+');
            }
            return sb.append(key.name()).toString();
        }
    }

    private void press(Key k) {
        gui.bus().publish(InputTopics.INPUT, new InputEvent.KeyPressed(k, 0));
        gui.wakeForInput();
    }

    private void release(Key k) {
        gui.bus().publish(InputTopics.INPUT, new InputEvent.KeyReleased(k, 0));
        gui.wakeForInput();
    }

    /** The key that holds a modifier named as {@code Modifier} names it, or by a common alias; else null. */
    private static Key modifierKey(String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "CONTROL", "CTRL" -> Key.LEFT_CONTROL;
            case "SHIFT" -> Key.LEFT_SHIFT;
            case "ALT", "OPTION" -> Key.LEFT_ALT;
            case "SUPER", "META", "WIN", "CMD", "COMMAND" -> Key.LEFT_SUPER;
            default -> null;
        };
    }

    // --- synchronising -----------------------------------------------------

    /**
     * Wait until the loop has caught up with everything published so far.
     *
     * <p>Off what the loop owes, not off a sleep — automation flakiness is overwhelmingly "did I wait long
     * enough?", and that failure mode is absent when there is a count to wait for. {@link Gui#frameOwed} counts
     * the wake, the frame that took it until it has published, and every handler that frame dispatched until it
     * has returned — so the click's own handler, which posts its mutation after the frame that dispatched it,
     * is waited for too. It used to read the wake flag alone, which the frame clears at its top, and answered
     * {@code ok} whenever it asked between there and the publish.
     *
     * <p><b>Then it waits for the {@link Timeline}</b>, when the host handed one over: a fade in flight with no
     * frame owed is not settled, and answering at once is how a panel was photographed part-way through its
     * transition and reported {@code ok}. Then the loop once more, because the tick that finished the motion
     * posted its last frame's worth of mutations. A timeline that never goes quiet — a repeating cue — is
     * reported by name rather than waited out.
     *
     * <p><b>What it cannot see</b> is work the application started for itself — on {@code Gui.async}, on a
     * thread of its own — which is not the loop's debt ({@link Gui#frameOwed()} says so from the other side).
     * This is therefore exact about the frame loop, its handlers and the clock, and blind to application work
     * still in flight. An agent that needs the second has to wait on something the application names.
     */
    public String settle() {
        String loop = settleLoop();
        if (loop != null) {
            return loop;
        }
        if (timeline.quiet()) {
            return "ok v" + gui.semanticSnapshot().version();
        }
        String clock = settleTimeline();
        if (clock != null) {
            return clock;
        }
        loop = settleLoop();
        return loop != null ? loop : "ok v" + gui.semanticSnapshot().version();
    }

    /**
     * Poll the timeline until it is quiet, bounded — {@code null} once it is, the refusal if it never is.
     *
     * <p>Polled rather than signalled, and not a sleep in disguise: the condition is read each time, and the
     * interval only bounds how late the answer is. A signal would need the clock to announce going quiet, and a
     * fade that changes only paint publishes no layout commit to wake on.
     */
    private String settleTimeline() {
        long deadline = System.nanoTime() + timelineTimeoutMs * 1_000_000L;
        // Asked on every poll and read at the deadline: a clock answers from a tick after the question, so asking
        // once at the start read back the moment the wait began — a page's crossfade, still in flight from the
        // click, named as the reason a page never went quiet ten seconds later.
        while (!timeline.quiet()) {
            List<String> sinceLastPoll = timeline.whyBusy();
            if (System.nanoTime() >= deadline) {
                return "err the timeline did not go quiet within " + timelineTimeoutMs + "ms — "
                        + (sinceLastPoll.isEmpty() ? "an animation or a repeating cue is still running"
                                                   : String.join("; ", sinceLastPoll))
                        + "; wait on a landmark with await instead";
            }
            try {
                Thread.sleep(TIMELINE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "err interrupted";
            }
        }
        return null;
    }

    /** The frame loop half of {@link #settle}: {@code null} once nothing is owed, the refusal if it stays owed. */
    private String settleLoop() {
        // Waited on the debt being paid, not on the next layout commit. A frame that changed nothing publishes
        // nothing, so a wait for a commit ran to its timeout and then answered from whatever it found; and the
        // debt counts what a commit cannot — the frame still dispatching, the handler still on its worker.
        try {
            if (gui.awaitNothingOwed(SETTLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "err interrupted";
        }
        // Said rather than swallowed. A settle that times out silently and returns is how an agent comes to
        // report a confident wrong answer about a UI that never actually updated.
        return "err did not settle within " + SETTLE_TIMEOUT_MS + "ms (" + gui.whatIsOwed() + ")";
    }

    /**
     * Wait until the node at {@code landmark} is named something containing {@code text}.
     *
     * <p><b>The half of synchronising that {@link #settle} is structurally unable to do.</b> Settle is exact
     * about the frame loop and blind to work in flight: a handler on a worker has published nothing, so nothing
     * reports it owed. That is not a defect to be fixed there — a frame loop genuinely does not know what the
     * application is still thinking about — and the note on {@code settle} has always ended by saying an agent
     * needing "the application has finished thinking" must wait on something the application names.
     *
     * <p>This is how it waits on it, and the point is that nothing here is application-specific. An accessible
     * name is already published for every node, and a landmark is already the durable way to say which node; so
     * an application declares readiness by <em>writing it down where it is legible</em> — a landmark whose name
     * says what state it is in — and needs no hook, no registry and no verb of its own. The alternative on the
     * table was a readiness predicate supplied by the host, which would have made every application's
     * synchronisation a private arrangement with this class rather than a fact in the read-model that
     * {@code tree} and {@code find} can already see.
     *
     * <p>Bounded, and it says so when it gives up — a wait that returns quietly is how an agent comes to report
     * confidently on a picture that was never drawn. Answers immediately when the condition already holds, so
     * it is not a sleep even in the case where nothing further will be published.
     *
     * @param rest {@code <landmark> <text>} — the text is the whole remainder, spaces and all; or {@code <landmark>}
     *             alone, to wait for it to exist ({@link #awaitExists})
     */
    public String await(String rest) {
        String[] parts = rest.trim().split("\\s+", 2);
        if (parts[0].isBlank()) {
            return "err await needs a landmark, and optionally the text to wait for";
        }
        String landmark = here(parts[0]);
        if (parts.length < 2) {
            return awaitExists(landmark);
        }
        String needle = parts[1].toLowerCase(Locale.ROOT);
        long deadline = System.nanoTime() + AWAIT_TIMEOUT_MS * 1_000_000L;
        java.util.concurrent.Semaphore published = new java.util.concurrent.Semaphore(0);
        // Subscribed before the first look, or the publish that satisfies the wait can land in the gap between
        // looking and subscribing -- and then nothing further is published, and the wait runs to its timeout
        // having been true the whole time.
        try (var sub = gui.layout().onCommit(v -> published.release())) {
            while (true) {
                String name = named(landmark);
                if (name != null && name.toLowerCase(Locale.ROOT).contains(needle)) {
                    return "ok " + name;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return name == null
                            ? "err no landmark '" + landmark + "' within " + AWAIT_TIMEOUT_MS + "ms; try tree"
                            : "err " + landmark + " is \"" + name + "\" after " + AWAIT_TIMEOUT_MS + "ms, "
                                    + "which does not contain '" + parts[1] + "'";
                }
                if (!published.tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    // Timed out on the wait rather than on the deadline: look once more before saying so, since
                    // the loop is entitled to have published nothing while still having settled.
                    String last = named(landmark);
                    if (last != null && last.toLowerCase(Locale.ROOT).contains(needle)) {
                        return "ok " + last;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "err interrupted";
        }
    }

    /**
     * {@code await <landmark>} with no text: wait until a node registered under {@code landmark} exists at all.
     *
     * <p>The first thing a scene waits for, before there is any state to read off a name: the tree is built on the
     * first frame, so a {@code settle} straight after launch can answer before the landmark is in it, and a {@code
     * find} or {@code click} then says it is not there. Witnesses used to poll {@code find} for this. Exists means
     * published in the semantic snapshot, as {@code tree} would list it — hidden included, since being hidden is
     * itself a state a scene may be waiting to see; a {@code click} on it reveals it or says why it cannot.
     *
     * <p>The same bound, the same wake (a layout commit, subscribed before the first look) and the same last look
     * on a quiet loop as the form with text. Answers {@code ok <ref> "<name>" @<landmark>}, so the ref is in hand.
     */
    private String awaitExists(String landmark) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT_MS * 1_000_000L;
        java.util.concurrent.Semaphore published = new java.util.concurrent.Semaphore(0);
        try (var sub = gui.layout().onCommit(v -> published.release())) {
            while (true) {
                SemanticNode n = landmarked(landmark);
                if (n != null) {
                    return "ok " + n.id() + " \"" + n.name() + "\" @" + landmark;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return "err no landmark '" + landmark + "' within " + AWAIT_TIMEOUT_MS + "ms; try tree";
                }
                published.tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "err interrupted";
        }
    }

    /** The accessible name of the node registered under {@code landmark}, or null if there is no such node. */
    private String named(String landmark) {
        SemanticNode n = landmarked(landmark);
        return n == null ? null : n.name();
    }

    /** The node registered under {@code landmark}, or null if there is no such node. */
    private SemanticNode landmarked(String landmark) {
        for (SemanticNode n : gui.semanticSnapshot().nodes().values()) {
            if (n.landmark().equals(landmark)) {
                return n;
            }
        }
        return null;
    }

    // --- the view ----------------------------------------------------------

    /**
     * The window as a camera would meet it: its size in pixels and the three factors an em resolves against.
     * Everything {@link #zoom}, {@link #dpi} and {@link #resize} can change, read back in one line.
     */
    public String size() {
        float zoom = gui.zoom().value();
        float dpi = gui.dpi().value();
        WindowControls controls = this.controls.get();
        return "ok " + controls.width() + "x" + controls.height() + " zoom=" + number(zoom) + " dpi=" + number(dpi)
                + " em=" + number(gui.rootEmPx() * zoom * dpi) + "px";
    }

    /**
     * Set the zoom factor, wait for the tree to be laid out at it, and say what it became.
     *
     * <p>Answers with the <b>actual</b> factor and says so when it is not the one asked for: the application
     * declares the range it allows ({@link Gui#zoomRange}), and a photograph at a zoom the application would
     * refuse is a picture of something no user can reach. So an out-of-range request is clamped exactly as the
     * application's own zoom control would clamp it, and reported rather than granted.
     */
    private String zoom(String rest) {
        if (rest.isBlank()) {
            return "ok zoom=" + number(gui.zoom().value());
        }
        float asked = factor(rest, "zoom");
        gui.zoom(asked);
        String failed = relayout();
        float got = gui.zoom().value();
        return failed != null ? failed : "ok zoom=" + number(got) + clampNote(asked, got, "zoom");
    }

    /**
     * Set the display density, wait for the tree to be laid out at it, and say what it became.
     *
     * <p>Density here is the framework's factor and not a real surface: the window's pixel size is whatever it
     * is, so {@code dpi 2} makes every em twice as many pixels in the <em>same</em> window. A picture at a
     * denser display's proportions wants {@link #resize} as well.
     */
    private String dpi(String rest) {
        if (rest.isBlank()) {
            return "ok dpi=" + number(gui.dpi().value());
        }
        float asked = factor(rest, "dpi");
        gui.dpi(asked);
        String failed = relayout();
        float got = gui.dpi().value();
        return failed != null ? failed : "ok dpi=" + number(got) + clampNote(asked, got, "dpi");
    }

    /**
     * {@code hide} and {@code show}: take this window off the screen and bring it back, as the application's own
     * {@code AppWindow.hide()} and {@code show()} do, so what an application does with a hidden window — the mark it
     * wears, the notification-area icon of a background run — can be driven and checked. The window stays open and
     * driven while hidden, like a minimized one: {@code tree}, {@code shot} and the rest still answer.
     */
    private String visibility(boolean show) {
        WindowControls controls = this.controls.get();
        if (controls.width() <= 0) {
            return "err there is no window to " + (show ? "show" : "hide");
        }
        if (show) {
            controls.show();
        } else {
            controls.hide();
        }
        return show ? "ok shown" : "ok hidden";
    }

    /**
     * Size the window's drawable area: {@code resize 1024x768}, {@code resize 1024 768}, or with units --
     * {@code resize 46em 30em}, {@code resize 800px 40em}. No unit means pixels.
     *
     * <p><b>Pixels and em are both allowed from here, and only here.</b> An application's own layout is declared
     * in em so that it means the same thing at every zoom; a caller outside it is choosing a window, and a window
     * is pixels. An em is resolved against the current zoom and density, so {@code 46em} is the size a person at
     * this zoom would get by dragging to 46 ems, and "the tree at its declared minimum" can be asked for without
     * the caller redoing the em-to-pixel arithmetic.
     *
     * <p>Answers with the size the window <b>became</b>, and says so when it is not the one asked for: the
     * application's minimum and the desktop both have a say.
     */
    private String resize(String rest) {
        if (rest.isBlank()) {
            return "err resize needs a size, like 1024x768 or 46em 30em";
        }
        WindowControls controls = this.controls.get();
        if (controls.width() <= 0) {
            return "err there is no window to resize";
        }
        if (controls.minimized()) {
            // Said apart from "the window would not go there", which is what the wait below would have concluded:
            // a minimized window ignores the request, and that reads as a size the application refused. Everything
            // else — the tree, input, shot — goes on working at the size it will be restored to.
            return "err the window is minimized, so it has no size to set (it is still laid out and photographed at "
                    + controls.width() + "x" + controls.height() + ")";
        }
        java.util.regex.Matcher m = SIZE.matcher(rest.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return "err resize needs a width and a height, like 1024x768 or 46em 30em";
        }
        String[] parts = {m.group(1), m.group(2)};
        float em = gui.rootEmPx() * gui.zoom().value() * gui.dpi().value();
        int w = pixels(parts[0], em);
        int h = pixels(parts[1], em);
        controls.resize(w, h);
        long deadline = System.nanoTime() + SETTLE_TIMEOUT_MS * 1_000_000L;
        while ((controls.width() != w || controls.height() != h) && System.nanoTime() < deadline) {
            sleepQuietly(SHOT_POLL_MS);
        }
        String failed = relayout();
        if (failed != null) {
            return failed;
        }
        int gotW = controls.width();
        int gotH = controls.height();
        String note = gotW == w && gotH == h ? ""
                : " (asked for " + w + "x" + h + "; the window would not go there)";
        return "ok " + gotW + "x" + gotH + note;
    }

    /** Wake the loop so the change just made is drawn, then wait for it: {@code null} once settled. */
    private String relayout() {
        gui.wakeForInput();
        String settled = settle();
        return settled.startsWith("err") ? settled : null;
    }

    private static float factor(String text, String what) {
        float v;
        try {
            v = Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " wants a number, and got '" + text.trim() + "'");
        }
        if (!Float.isFinite(v) || v <= 0f) {
            throw new IllegalArgumentException(what + " wants a positive number, and got '" + text.trim() + "'");
        }
        return v;
    }

    /** {@code 800}, {@code 800px} or {@code 46em} as whole pixels, an em being {@code emPx} of them. */
    private static int pixels(String text, float emPx) {
        boolean inEm = text.endsWith("em");
        String digits = inEm || text.endsWith("px") ? text.substring(0, text.length() - 2) : text;
        float v;
        try {
            v = Float.parseFloat(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a size: '" + text + "' (write 800, 800px or 46em)");
        }
        int px = Math.round(inEm ? v * emPx : v);
        if (px <= 0) {
            throw new IllegalArgumentException("a window cannot be " + px + "px across");
        }
        return px;
    }

    private static String clampNote(float asked, float got, String what) {
        return Math.abs(asked - got) < 1e-4f ? ""
                : " (asked for " + number(asked) + "; the application's " + what + " range allows " + number(got) + ")";
    }

    /** Short and stable: {@code 1.5}, {@code 2}, never {@code 1.5000001}. */
    private static String number(float v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- the rest ----------------------------------------------------------

    /**
     * Photograph the window this driver is attached to, and <b>wait for the picture</b>.
     *
     * <p>The waiting is the whole command. {@link WindowControls#capture} is asynchronous and cannot report:
     * it posts to the frame loop and returns, so a {@code shot} that replied on return said {@code ok} for a
     * window that was minimized (no pixels, and {@code GuiWindow.capture} correctly declines), for a host whose
     * capture sink does nothing, and for a write that failed and complained to {@code System.err} where no
     * agent on a socket can see it. That is the failure mode §6 of automation.md exists to forbid, and it is
     * worse here than elsewhere: a photograph is the one command whose entire value is that its answer can be
     * looked at, so an {@code ok} naming a file that is not there is the instrument lying about the only thing
     * it was asked.
     *
     * <p>Waiting for the file is sound rather than a sleep in disguise, because the capture is written beside
     * the target and moved into place — so a file that exists is a complete PNG, which is a contract
     * {@code GuiWindow.capture} states and keeps for exactly this consumer.
     *
     * <p><b>The target is removed first</b>, and that is what makes the wait mean anything: a path photographed
     * earlier in the same session already holds a complete PNG, so "wait for it to appear" would be satisfied
     * instantly by the previous run's picture. An agent comparing two shots would be handed the same one twice,
     * which is a wrong answer that looks like a working instrument.
     */
    private String shot(String path) {
        String file = path.isBlank() ? "shot.png" : path.trim();
        java.io.File target = new java.io.File(file);
        if (target.exists() && !target.delete()) {
            // Said rather than photographed over. A stale picture returned as a fresh one is the failure this
            // whole method is about, so being unable to rule it out is a refusal.
            return "err could not clear the previous " + target.getAbsolutePath();
        }
        controls.get().capture(file);
        long deadline = System.nanoTime() + SHOT_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (target.isFile() && target.length() > 0) {
                return "ok " + target.getAbsolutePath() + " " + target.length() + " bytes";
            }
            try {
                Thread.sleep(SHOT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "err interrupted waiting for " + target.getAbsolutePath();
            }
        }
        return "err no picture appeared at " + target.getAbsolutePath()
                + " within " + SHOT_TIMEOUT_MS + "ms";
    }

    /**
     * Record this window to a video file, or stop: the camera's <i>Record video to file…</i> told where rather than
     * asking, so a script can film what it drives. The recording is {@link dev.vexelray.gui.core.WindowRecording}'s,
     * the same one the camera starts and stops, so a person can stop what a script started and the other way round.
     *
     * <p>{@code record <path>} answers once frames are flowing, with the canvas every frame is drawn on.
     * {@code record stop} answers once the file is whole, with its size, the way {@code shot} answers only once its
     * picture exists: an {@code ok} naming a file still being written would be a promise, not a fact.
     */
    private String record(String rest) {
        String arg = rest.trim();
        if (arg.isEmpty()) {
            return dev.vexelray.gui.core.WindowRecording.file().map(f -> "ok recording " + f)
                    .orElse(dev.vexelray.gui.core.WindowRecording.available() ? "ok not recording"
                            : "ok not recording (nothing here records video)");
        }
        if (arg.equalsIgnoreCase("stop")) {
            var stopping = dev.vexelray.gui.core.WindowRecording.stop();
            if (stopping.isEmpty()) {
                return "err not recording";
            }
            try {
                java.nio.file.Path file = stopping.get().get(RECORD_STOP_TIMEOUT_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                return "ok " + file + " " + java.nio.file.Files.size(file) + " bytes";
            } catch (java.util.concurrent.ExecutionException e) {
                return "err " + e.getCause().getMessage();
            } catch (java.util.concurrent.TimeoutException e) {
                return "err the file was not finished within " + RECORD_STOP_TIMEOUT_MS + "ms";
            } catch (java.io.IOException e) {
                return "err " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "err interrupted waiting for the file";
            }
        }
        java.nio.file.Path file = java.nio.file.Path.of(arg).toAbsolutePath();
        try {
            int[] canvas = dev.vexelray.gui.core.WindowRecording.start(controls.get(), file);
            return "ok recording " + file + " on " + canvas[0] + "x" + canvas[1];
        } catch (java.io.IOException e) {
            return "err " + e.getMessage();
        }
    }

    /**
     * Write a row into the correlation log saying what the agent was doing and why.
     *
     * <p>The one command that changes nothing. A run is read back by someone who does not have the transcript
     * that produced it, and "clicked 41" is a fact where "checking whether the menu closes on a second click"
     * is an explanation.
     */
    private String mark(String note) {
        if (Probe.ON) {
            Probe.mark(Lane.APP, "agent.mark", note);
        }
        return "ok";
    }

    /**
     * Read or change how much the application logs, while it runs.
     *
     * <p>{@code log} says what is in force and where the file is — so an agent that wants to read what a run did
     * knows where to look. {@code log debug} holds both sinks to a level; {@code log gui.frame trace} holds one
     * logger and everything beneath it, which reaches every sink that is on (see {@code LogConfig}). The change
     * is to the running process only: nothing is written anywhere to be remembered by the next launch.
     *
     * <p>Here because automation is how a run is watched, and asking for more detail in the middle of one — after
     * a click did something unexpected, say — is the moment a launch flag is too late.
     */
    private String log(String rest) {
        String[] args = rest.trim().isEmpty() ? new String[0] : rest.trim().split("\\s+");
        if (args.length > 2) {
            return "err log takes a level, or a logger and a level";
        }
        if (args.length > 0) {
            Level level = Level.parse(args[args.length - 1]);
            if (level == null) {
                return "err '" + args[args.length - 1] + "' is not a level (trace, debug, info, warn, error, off)";
            }
            Logging.setLevel(args.length == 2 ? args[0] : null, level);
        }
        LogConfig c = Logging.config();
        return "ok mode=" + c.mode() + " console=" + c.console() + " file=" + c.file()
                + (c.fileEnabled() ? " -> " + c.logFile() : " (no file)")
                + (Probe.ON ? " probe=on" : " probe=off")
                + (c.overrides().isEmpty() ? "" : " loggers=" + c.overrides());
    }

    private static int round(float v) {
        return Math.round(v);
    }

    /**
     * What an ancestor's clip has done to this box, as a suffix for a listing — {@code ""} when it has done
     * nothing.
     *
     * <p>Printed next to the rect and not instead of it, because the two say different things: the rect is
     * where the node was put, and this is how much of that a person could point at. A listing that showed only
     * the rect is what let an agent read a position for a row behind a sticky header and aim there.
     */
    private static String clipping(NodeLayout box) {
        if (box.clippedAway()) {
            return " offscreen";
        }
        if (box.clipped()) {
            Rect v = box.visibleRect();
            return " clipped to [" + round(v.x()) + ',' + round(v.y())
                    + ' ' + round(v.w()) + 'x' + round(v.h()) + ']';
        }
        return "";
    }

    /**
     * Which axes this box scrolls along, as a suffix — {@code ""} for none. A scroller is where the wheel goes, and
     * one scrolling inside another along the same axis is two scrollbars side by side.
     */
    private static String scrolling(NodeLayout box) {
        if (box.overflowX() && box.overflowY()) {
            return " scrolls x y";
        }
        return box.overflowX() ? " scrolls x" : box.overflowY() ? " scrolls y" : "";
    }

    private static final String HELP = String.join("\n", List.of(
            "ok",
            "tree                     the addressable tree: ref, role, name, box",
            "find <text>              refs whose role, name or landmark matches",
            "go <landmark>           navigate: reveal, scroll into view, focus",
            "where                    where the pointer is",
            "move <ref|x,y>           travel there (hover happens on the way)",
            "click <ref|x,y>          travel there, then press and release",
            "rightclick <ref|x,y>     the same, with the right button",
            "drag <ref|x,y> <ref|x,y> press, travel, release",
            "down <ref|x,y>           travel there and press, and hold: move and shot keep it held",
            "up [ref|x,y]             let go, where the pointer is or after travelling there",
            "scroll <ref|x,y> dx dy   wheel notches there",
            "touch <ref|x,y> <n> dx dy [scale] [turn] [ms]",
            "                         n fingers on the touchpad, the pointer there: their centre moves dx dy mm,",
            "                         their spread grows by scale and they turn degrees, over ms (200), and lift",
            "tap <ref|x,y> <n> [times]  n fingers touch and lift, times over (2: a double tap)",
            "type <text>              one code point at a time, as typing",
            "key <NAME>               press and release a named key (key ENTER)",
            "key <MOD+...+NAME>       a chord: modifiers held around the key (key CTRL+SHIFT+W);",
            "                         modifiers CONTROL|CTRL, SHIFT, ALT, SUPER|META|WIN|CMD",
            "keydown <[MOD+...+]NAME> press and hold, until keyup or the hand is let go (quit, disconnect)",
            "keyup [MOD+...+NAME]     release held keys, in reverse; with nothing named, all of them",
            "settle                   wait for the loop to catch up, and any animation to finish",
            "await <landmark> <text>  wait until that landmark is named something containing text",
            "await <landmark>         wait until a node with that landmark exists",
            "shot [path]              photograph this window, and wait for the file (err if none arrives)",
            "record <path.mkv>        record this window to a video, as the camera's Record does, until record stop",
            "record stop              stop, and wait for the file to be whole; record alone says what is recording",
            "size                     this window's pixels, zoom, dpi and em, in one line",
            "zoom [factor]            read or set the zoom; clamped to the application's own range, and says so",
            "dpi [factor]             read or set the display density factor",
            "resize <w>x<h>           size the window: pixels, or em with a unit (resize 46em 30em)",
            "hide                     take this window off the screen, as the application's hide() does",
            "show                     bring a hidden window back",
            "log [logger] [level]     read, or set, how much is logged; the reply says where the file is",
            "mark <note>              write why into the correlation log"));
}
