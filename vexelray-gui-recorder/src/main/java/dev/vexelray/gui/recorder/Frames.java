package dev.vexelray.gui.recorder;

import dev.vexelray.gui.recorder.FrameExplanation.LayoutPass;
import dev.vexelray.gui.recorder.FrameExplanation.Published;
import dev.vexelray.gui.recorder.FrameExplanation.Stage;
import dev.vexelray.gui.recorder.FrameExplanation.Wake;
import sibarum.probe.CsvView;
import sibarum.probe.Lane;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Frames, derived from the rows the probe already emits, grouped by their frame stamp.
 *
 * <h2>What a frame is made of</h2>
 *
 * Every row written while {@code LoopCounters.frame()} read {@code F} belongs to frame {@code F}: the loop's
 * {@code frame} span (the frame lane's root span, which closes at the end of the frame's work), everything
 * nested in it, then {@code frame.present}, {@code loop.park} and the {@code wait for events} span the loop
 * parks in. A row another thread writes in that time belongs to {@code F} as well, honest to within one frame.
 * Stamps never decrease in {@code seq} order (they are read under the probe's lock), so a frame's rows are
 * contiguous in the ring and one pass groups them.
 *
 * <h2>The stages</h2>
 *
 * Only the loop thread's rows count — the thread that wrote the {@code frame} span. The stages are the span names
 * the loop, the windows and the presenters use today: {@link #TOP} partitions the frame span, {@link #INSIDE}
 * names the pieces within. A stage whose lane recorded nothing anywhere in the ring is unknown ({@code -}), never
 * zero.
 *
 * <h2>What woke it</h2>
 *
 * Read from the frame before. A wake posted during frame {@code F-1} — during its work or its park — is owed to
 * {@code F}, so {@code F-1}'s {@code wake} marks are what woke {@code F}. Failing those: key edges in {@code F}'s own
 * input pump mean OS input ended the park; a park that lasted its whole budget timed out; a park on {@code 0ms}
 * did not park at all. Anything else ended the park without a row saying so — an OS message such as pointer
 * motion or a window event — and is reported as {@code -} with that reason, because the probe has no mark there.
 */
final class Frames {

    /** One named stage: a lane and the span names on it; no names means every span on the lane. */
    record Def(String name, Lane lane, Set<String> kinds) {
        Def(String name, Lane lane, String... kinds) {
            this(name, lane, Set.of(kinds));
        }

        boolean matches(Lane l, String kind) {
            return l == lane && (kinds.isEmpty() || kinds.contains(kind));
        }
    }

    /** The frame span's direct children on the loop thread; with {@code other} they make up the frame. */
    static final List<Def> TOP = List.of(
            new Def("before", Lane.FRAME, "before frame"),
            new Def("update", Lane.LAYOUT, "tree update"),
            new Def("draw", Lane.GPU, "present frame", "offscreen frame"));

    /** The pieces inside those, by name. */
    static final List<Def> INSIDE = List.of(
            new Def("input", Lane.INPUT),
            new Def("clock", Lane.TIME, "batch"),
            new Def("dispatch", Lane.LAYOUT, "dispatch input"),
            new Def("drain", Lane.BUS, "pump drain"),
            new Def("layout", Lane.LAYOUT, "flex layout", "flex layout (reveal)"),
            new Def("resolve", Lane.LAYOUT, "resolve geometry"),
            new Def("emit", Lane.DRAW, "emit canvas"),
            new Def("gpu_wait", Lane.GPU, "wait fence", "dxgi wait", "wait image", "acquire image"),
            new Def("submit", Lane.GPU, "queue submit"),
            new Def("present", Lane.GPU, "dxgi copy+present", "queue present"));

    /** What no row records, which a fuller {@code why} would need. */
    static final List<String> UNRECORDED = List.of(
            "budget_us (the loop marks no frame budget, so over_us cannot be given)",
            "gc (the probe has no gc rows)",
            "layout cause (flex layout rows do not say what made the tree dirty)");

    /** One frame's rows, folded. */
    static final class Acc {
        final long frame;
        long frameRowSeq = -1;
        long frameNs = -1;
        long frameEndNs = -1;
        final long[] topNs = new long[TOP.size()];
        final long[] insideNs = new long[INSIDE.size()];
        boolean drawSkipped;
        boolean drew;
        boolean presented;
        String park;
        long waitNs = -1;
        int wakes;
        String wakeDetail;
        String wakeThread;
        long inputEdges;
        long drained;
        final List<LayoutPass> passes = new ArrayList<>(2);
        int slow;
        Map<String, Topic> published = Map.of();

        Acc(long frame) {
            this.frame = frame;
        }

        boolean complete() {
            return frameNs >= 0;
        }

        long atNs() {
            return frameEndNs - frameNs;
        }
    }

    /** Publishes to one topic, collected for {@code why}. */
    static final class Topic {
        int count;
        final Set<String> threads = new TreeSet<>();
    }

    /** The frames folded from one pass over the ring. */
    record Result(Span span, List<Acc> frames, Set<Lane> lanes, Span targetSpan) {
    }

    private Frames() {
    }

    /**
     * Fold every complete frame the ring holds. The first frame stamp met is dropped, because the ring may hold only
     * its tail; frame 0 (rows before the first frame, or with no frame counter) is never a frame.
     *
     * @param target a frame whose publishes and span to collect for {@code why}, or -1
     * @return every frame group but the first, in order, complete or not (a frame the platform pulled during a
     *         move has rows but no frame span); a caller lists the {@linkplain Acc#complete complete} ones and uses
     *         the rest as the frame before
     */
    static Result fold(Recorder recorder, long target) {
        // First pass: the loop thread, and which lanes recorded anything. Cheap: no row is kept.
        String[] loop = {null};
        Set<Lane> lanes = EnumSet.noneOf(Lane.class);
        Scan.all(recorder, (rows, i) -> {
            Lane l = rows.laneOf(i);
            lanes.add(l);
            if (loop[0] == null && l == Lane.FRAME && rows.kind(i).equals("frame")) {
                loop[0] = rows.thread(i);
            }
        });
        List<Acc> frames = new ArrayList<>();
        Scan.Tally targetTally = new Scan.Tally();
        Map<String, Topic> pending = new LinkedHashMap<>();
        boolean[] firstGroup = {true};
        Acc[] current = {null};
        Span span = Scan.all(recorder, (rows, i) -> {
            long f = rows.frame(i);
            Acc acc = current[0];
            if (acc == null || acc.frame != f) {
                if (acc != null && !firstGroup[0] && acc.frame > 0) {
                    frames.add(acc);
                }
                if (acc != null) {
                    firstGroup[0] = false;
                }
                acc = new Acc(f);
                current[0] = acc;
            }
            if (target >= 0 && (f == target || f == target - 1)) {
                targetTally.add(rows.seq(i), rows.monoNanos(i));
            }
            add(acc, rows, i, loop[0], target, pending);
        });
        // The frame in progress, or the last one finished and parked: its work is complete once its frame span is.
        if (current[0] != null && !firstGroup[0] && current[0].frame > 0) {
            frames.add(current[0]);
        }
        return new Result(span, frames, lanes, targetTally.span());
    }

    private static void add(Acc acc, Rows rows, int i, String loop, long target, Map<String, Topic> pending) {
        Lane lane = rows.laneOf(i);
        String kind = rows.kind(i);
        String detail = rows.detail(i);
        String thread = rows.thread(i);
        if (lane == Lane.BUS && isPublish(kind)) {
            if (target >= 0) {
                Topic t = pending.computeIfAbsent(kind, k -> new Topic());
                t.count++;
                t.threads.add(thread);
            }
            return;
        }
        if (kind.startsWith("SLOW ")) {
            acc.slow++;
        }
        if (lane == Lane.FRAME && kind.equals("wake")) {
            if (acc.wakes++ == 0) {
                acc.wakeDetail = detail;
                acc.wakeThread = thread;
            }
            return;
        }
        if (!thread.equals(loop)) {
            return;
        }
        if (lane == Lane.FRAME) {
            switch (kind) {
                case "frame" -> {
                    acc.frameNs = Scan.nanos(detail);
                    acc.frameEndNs = rows.monoNanos(i);
                    acc.frameRowSeq = rows.seq(i);
                    // What was published since the last frame's work ended belongs to this frame's account.
                    if (target >= 0 && acc.frame == target) {
                        acc.published = new LinkedHashMap<>(pending);
                    }
                    pending.clear();
                    return;
                }
                case "loop.park" -> {
                    acc.park = detail;
                    return;
                }
                case "wait for events" -> {
                    acc.waitNs = Scan.nanos(detail);
                    return;
                }
                case "draw skipped" -> {
                    acc.drawSkipped = true;
                    return;
                }
                default -> {
                    // another frame-lane row; the stages below may still name it
                }
            }
        }
        if (lane == Lane.INPUT && kind.equals("edges")) {
            acc.inputEdges += Scan.count(detail);
            return;
        }
        if (lane == Lane.BUS && kind.equals("pump delivered")) {
            acc.drained += Scan.count(detail);
            return;
        }
        long ns = Scan.nanos(detail);
        if (ns < 0) {
            return;
        }
        for (int s = 0; s < TOP.size(); s++) {
            if (TOP.get(s).matches(lane, kind)) {
                acc.topNs[s] += ns;
                if (TOP.get(s).name().equals("draw")) {
                    acc.drew = true;
                }
            }
        }
        for (int s = 0; s < INSIDE.size(); s++) {
            if (INSIDE.get(s).matches(lane, kind)) {
                acc.insideNs[s] += ns;
            }
        }
        if (lane == Lane.DRAW && kind.equals("emit canvas")) {
            acc.drew = true;
        }
        if (lane == Lane.GPU && (kind.equals("dxgi copy+present") || kind.equals("queue present"))) {
            acc.presented = true;
        }
        if (lane == Lane.LAYOUT && kind.startsWith("flex layout")) {
            acc.passes.add(new LayoutPass(kind, us(ns)));
        }
    }

    /** A bus row that is a publish: the topic's own name, not its depth, batch, drop or a pump's rows. */
    private static boolean isPublish(String kind) {
        return !kind.startsWith("pump ") && !kind.endsWith(" depth") && !kind.endsWith(" batch")
                && !kind.endsWith(" dropped");
    }

    // --- from a folded frame to the records ---------------------------------

    static FrameSummary summary(Acc f, Acc before, Set<Lane> lanes) {
        List<Stage> inside = inside(f, lanes);
        Stage other = other(f, lanes);
        Stage most = most(inside, other);
        return new FrameSummary(f.frame, f.atNs(), us(f.frameNs), most == null ? "-" : most.name(),
                most == null ? -1 : most.us(), woke(f, before).kind(), drawn(f, lanes), presented(f, lanes),
                f.park == null ? "-" : f.park);
    }

    static FrameExplanation explain(Acc f, Acc before, Set<Lane> lanes, Span span) {
        List<Stage> stages = new ArrayList<>();
        for (int s = 0; s < TOP.size(); s++) {
            Def d = TOP.get(s);
            stages.add(new Stage(d.name(), d.lane().key(), lanes.contains(d.lane()) ? us(f.topNs[s]) : -1));
        }
        stages.add(other(f, lanes));
        List<Stage> inside = inside(f, lanes);
        Stage most = most(inside, null);
        String see = "rows --frame " + f.frame + (most == null ? "" : " --lane " + most.lane());
        List<Published> published = new ArrayList<>();
        f.published.forEach((topic, t) -> published.add(new Published(topic, t.count, String.join(",", t.threads))));
        return new FrameExplanation(f.frame, f.atNs(), us(f.frameNs), drawn(f, lanes), presented(f, lanes),
                woke(f, before), stages, inside, f.passes, lanes.contains(Lane.BUS) ? f.drained : -1, published,
                f.park == null ? "-" : f.park, f.waitNs < 0 ? -1 : us(f.waitNs), f.slow, UNRECORDED, see, span);
    }

    private static List<Stage> inside(Acc f, Set<Lane> lanes) {
        List<Stage> inside = new ArrayList<>(INSIDE.size());
        for (int s = 0; s < INSIDE.size(); s++) {
            Def d = INSIDE.get(s);
            inside.add(new Stage(d.name(), d.lane().key(), lanes.contains(d.lane()) ? us(f.insideNs[s]) : -1));
        }
        return inside;
    }

    /** The frame less its top-level spans: the loop's own time. Unknown when any of those lanes is off. */
    private static Stage other(Acc f, Set<Lane> lanes) {
        long rest = f.frameNs;
        for (int s = 0; s < TOP.size(); s++) {
            if (!lanes.contains(TOP.get(s).lane())) {
                return new Stage("other", Lane.FRAME.key(), -1);
            }
            rest -= f.topNs[s];
        }
        return new Stage("other", Lane.FRAME.key(), us(Math.max(0, rest)));
    }

    private static Stage most(List<Stage> stages, Stage other) {
        Stage most = null;
        for (Stage s : stages) {
            if (s.us() > 0 && (most == null || s.us() > most.us())) {
                most = s;
            }
        }
        if (other != null && other.us() > 0 && (most == null || other.us() > most.us())) {
            most = other;
        }
        return most;
    }

    static Wake woke(Acc f, Acc before) {
        if (before == null || before.frame != f.frame - 1) {
            return new Wake("-", "frame " + (f.frame - 1) + " is not in the ring", 0, "-");
        }
        if (before.wakes > 0) {
            return new Wake("wake", before.wakeDetail, before.wakes, before.wakeThread);
        }
        if (f.inputEdges > 0) {
            return new Wake("input", f.inputEdges + " key edges in this frame's input pump", 0, "-");
        }
        if (before.park != null) {
            long budget = CsvView.parkBudgetNanos(before.park);
            if (budget == 0 || before.waitNs < 0) {
                return new Wake("unparked", "frame " + before.frame + " parked on " + before.park, 0, "-");
            }
            if (budget != Long.MAX_VALUE && before.waitNs >= budget - 1_000_000L) {
                return new Wake("timeout", "frame " + before.frame + "'s park on " + before.park + " ran out", 0,
                        "-");
            }
            return new Wake("-", "the park on " + before.park + " ended after " + us(before.waitNs)
                    + "us on something no row names (an OS message: pointer, window)", 0, "-");
        }
        return new Wake("-", "frame " + before.frame + " recorded no park", 0, "-");
    }

    private static String drawn(Acc f, Set<Lane> lanes) {
        if (f.drew) {
            return "yes";
        }
        if (f.drawSkipped) {
            return "no";
        }
        return lanes.contains(Lane.FRAME) && lanes.contains(Lane.GPU) && lanes.contains(Lane.DRAW) ? "no" : "-";
    }

    private static String presented(Acc f, Set<Lane> lanes) {
        if (f.presented) {
            return "yes";
        }
        return lanes.contains(Lane.GPU) ? "no" : "-";
    }

    static long us(long ns) {
        return Math.round(ns / 1_000.0);
    }
}
