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
import sibarum.probe.Probe;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

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
 * <p>Thread-safe to call from one driver thread. It reads snapshots lock-free and publishes to the bus; it
 * never touches GUI-thread state.
 */
public final class Automation {

    /** How long {@link #settle} waits before giving up and saying so. */
    private static final long SETTLE_TIMEOUT_MS = 2_000L;

    private final Gui gui;
    private final WindowControls controls;
    private final Cursor cursor;

    public Automation(Gui gui) {
        this(gui, WindowControls.NONE);
    }

    public Automation(Gui gui, WindowControls controls) {
        this.gui = java.util.Objects.requireNonNull(gui, "gui");
        this.controls = controls == null ? WindowControls.NONE : controls;
        this.cursor = new Cursor(gui.bus());
    }

    /** The pointer this driver moves. One hand. */
    public Cursor cursor() {
        return cursor;
    }

    /**
     * Run one command line and return what to print. Never throws: a driver that dies on a bad command is a
     * driver that loses the session it was in the middle of investigating.
     */
    public String command(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1] : "";
        try {
            return run(verb, rest);
        } catch (RuntimeException e) {
            return "err " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
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
            case "scroll" -> scroll(rest);
            case "type" -> type(rest);
            case "key" -> key(rest);
            case "go" -> go(rest);
            case "settle" -> settle();
            case "shot" -> shot(rest);
            case "mark" -> mark(rest);
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
        boolean interesting = !n.role().isEmpty() || !n.name().isEmpty() || n.focusable();
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
            }
            if (!n.visible()) {
                // Said explicitly, because a rect is published for a hidden node too and finding one proves
                // nothing about whether it can be seen or clicked (docs/semantic-read-model.md §6).
                sb.append(" hidden");
            }
            if (n.focused()) {
                sb.append(" focused");
            }
            sb.append('\n');
        }
        for (long child : n.children()) {
            describe(sem, layout, child, interesting ? depth + 1 : depth, sb);
        }
    }

    /** Refs whose role or name contains {@code query}, case-insensitively. */
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
                    && !n.name().toLowerCase(Locale.ROOT).contains(needle)) {
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
            }
            if (!n.visible()) {
                sb.append(" hidden");
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
        long id = s.chars().allMatch(Character::isDigit) ? Long.parseLong(s) : landmarkId(s);
        SemanticNode what = gui.semanticSnapshot().node(id);
        if (!what.present()) {
            throw new IllegalArgumentException("no node " + s);
        }
        if (!what.visible()) {
            // Not a refusal if the framework can do something about it. A landmark is *reachable*: navigating
            // to one asks every container concealing it to un-conceal it (Reveal — the selected tab, the
            // collapsed branch, the closed drawer) and scrolls it into view. That is the hardest problem in UI
            // automation, already solved here, and re-solving it from the outside would mean this class
            // enumerating the containers that can hide something — an enumeration that is never finished.
            if (what.landmark().isEmpty()) {
                throw new IllegalArgumentException("node " + id + " is not visible, and has no landmark to "
                        + "navigate to; only a named node can ask to be revealed");
            }
            go(what.landmark());
            if (!gui.semanticSnapshot().node(id).visible()) {
                throw new IllegalArgumentException("navigated to '" + what.landmark()
                        + "' and it is still not visible");
            }
        }
        NodeLayout box = gui.layoutSnapshot().node(id);
        if (!box.present()) {
            throw new IllegalArgumentException("node " + id + " has no laid-out box");
        }
        Rect r = box.rect();
        // The centre: the one point in a box that is inside it for every shape a box can take.
        return new int[]{Math.round(r.x() + r.w() * 0.5f), Math.round(r.y() + r.h() * 0.5f)};
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
            if (Probe.ON) {
                Probe.mark(Lane.INPUT, "type", new String(Character.toChars(cp)));
            }
        });
        return "ok " + text.codePointCount(0, text.length());
    }

    /** Press and release a named key — {@code key ENTER}, {@code key TAB}. */
    private String key(String name) {
        Key k;
        try {
            k = Key.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "err no key named '" + name.trim() + "'";
        }
        gui.bus().publish(InputTopics.INPUT, new InputEvent.KeyPressed(k, 0));
        gui.bus().publish(InputTopics.INPUT, new InputEvent.KeyReleased(k, 0));
        if (Probe.ON) {
            Probe.mark(Lane.INPUT, "key", k.name());
        }
        return "ok " + k.name();
    }

    // --- synchronising -----------------------------------------------------

    /**
     * Wait until the loop has caught up with everything published so far.
     *
     * <p>Off the version counter the framework already publishes, not off a sleep — automation flakiness is
     * overwhelmingly "did I wait long enough?", and that failure mode is absent when there is a number to wait
     * for. It waits for a new layout to be published and then keeps waiting while a frame is still owed, since
     * one publish is not enough: a handler running on a worker posts its mutation after the frame that
     * dispatched the click has already drained.
     *
     * <p><b>What it cannot see</b> is a handler that has not published yet — it is owed nothing, so nothing
     * reports it ({@link Gui#frameOwed()} says so from the other side). This is therefore exact about the frame
     * loop and blind to application work still in flight. An agent that needs the second has to wait on
     * something the application names.
     */
    public String settle() {
        if (!gui.frameOwed()) {
            return "ok v" + gui.semanticSnapshot().version();   // nothing outstanding; do not wait for news
        }
        long deadline = System.nanoTime() + SETTLE_TIMEOUT_MS * 1_000_000L;
        // Waited on a signal rather than on the version counter directly. State.await(version) blocks until the
        // *next* publish, with no timeout — and layout does not republish when nothing moved, so a tree that has
        // settled produces no further versions and that wait never returns. An unbounded wait inside a bounded
        // loop is unbounded; the bound has to be on the wait itself.
        java.util.concurrent.Semaphore published = new java.util.concurrent.Semaphore(0);
        try (var sub = gui.layout().onCommit(v -> published.release())) {
            while (gui.frameOwed()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                if (!published.tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
            if (!gui.frameOwed()) {
                return "ok v" + gui.semanticSnapshot().version();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "err interrupted";
        }
        // Said rather than swallowed. A settle that times out silently and returns is how an agent comes to
        // report a confident wrong answer about a UI that never actually updated.
        return "err did not settle within " + SETTLE_TIMEOUT_MS + "ms (frame still owed)";
    }

    // --- the rest ----------------------------------------------------------

    /** Photograph the window this driver is attached to. */
    private String shot(String path) {
        String file = path.isBlank() ? "shot.png" : path.trim();
        controls.capture(file);
        return "ok " + file;
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

    private static int round(float v) {
        return Math.round(v);
    }

    private static final String HELP = String.join("\n", List.of(
            "ok",
            "tree                     the addressable tree: ref, role, name, box",
            "find <text>              refs whose role or name matches",
            "go <landmark>           navigate: reveal, scroll into view, focus",
            "where                    where the pointer is",
            "move <ref|x,y>           travel there (hover happens on the way)",
            "click <ref|x,y>          travel there, then press and release",
            "rightclick <ref|x,y>     the same, with the right button",
            "drag <ref|x,y> <ref|x,y> press, travel, release",
            "scroll <ref|x,y> dx dy   wheel notches there",
            "type <text>              one code point at a time, as typing",
            "key <NAME>               press and release a named key",
            "settle                   wait for the loop to catch up",
            "shot [path]              photograph this window",
            "mark <note>              write why into the correlation log"));
}
