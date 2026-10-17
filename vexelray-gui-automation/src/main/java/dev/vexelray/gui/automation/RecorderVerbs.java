package dev.vexelray.gui.automation;

import dev.vexelray.gui.recorder.Follower;
import dev.vexelray.gui.recorder.FrameList;
import dev.vexelray.gui.recorder.GapList;
import dev.vexelray.gui.recorder.Queries;
import dev.vexelray.gui.recorder.Recorder;
import dev.vexelray.gui.recorder.Replies;
import dev.vexelray.gui.recorder.RowFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The verbs over the flight recorder's ring: {@code recorder}, {@code rows}, {@code gaps}, {@code frames} and
 * {@code why} (docs/plans/flight-recorder.md §5, docs/reference/automation.md §5).
 *
 * <p>Only the words live here. Each verb parses its arguments, asks {@link Queries} for a record, and prints it
 * with {@link Replies}; nothing about the answer is decided in this class, so an answer read over another
 * transport is the same answer. All of them read, so any connection may use them and none needs the hand, and
 * none of them needs a window: the ring is the process's, like the probe that feeds it.
 */
final class RecorderVerbs {

    static final String OFF = "err the recorder is off: start with -Dprobe=...";

    static final String HELP = String.join("\n",
            "recorder                 the flight recorder: rows held, span, loss, row rate, lanes on",
            "rows [--lane L] [--kind K] [--thread T] [--grep S] [--frame N] [n]",
            "                         the last n rows (50) that match, as correlation-log lines plus frame",
            "gaps [ms]                silences between frames of ms (100) or more, each judged against its park",
            "frames [last N | worst N | since <mark>]",
            "                         one line per frame: time, slowest stage, what woke it (last 20)",
            "why <frame>              one frame explained: wake, stages, layout passes, bus; and where to look",
            "stream [lanes|all] [from <seq>] [for <ms>]",
            "                         the rows as they happen, as correlation-log lines plus frame; '# lost' and",
            "                         '# idle' lines between, '# end' last. Without for, until any line arrives");

    /** The longest a bounded stream may run: as long as {@code meter}'s longest window. */
    static final long MAX_STREAM_MS = 600_000;

    private final Recorder recorder;

    /** Over {@code recorder}, or answering {@link #OFF} to every verb when it is {@code null}. */
    RecorderVerbs(Recorder recorder) {
        this.recorder = recorder;
    }

    Recorder recorder() {
        return recorder;
    }

    /** Whether {@code verb} is one of these. */
    static boolean owns(String verb) {
        return switch (verb) {
            case "recorder", "rows", "gaps", "frames", "why" -> true;
            default -> false;
        };
    }

    /** The reply to one of {@link #owns these} verbs. */
    String answer(String verb, String rest) {
        if (recorder == null) {
            return OFF;
        }
        try {
            return switch (verb) {
                case "recorder" -> Replies.text(Queries.status(recorder));
                case "rows" -> Replies.text(Queries.rows(recorder, filter(rest)));
                case "gaps" -> Replies.text(Queries.gaps(recorder,
                        rest.isBlank() ? GapList.DEFAULT_THRESHOLD_MS : number(rest.trim(), "a threshold in ms")));
                case "frames" -> frames(rest.trim());
                case "why" -> rest.isBlank()
                        ? "err why takes a frame number; frames lists them"
                        : Replies.text(Queries.why(recorder, number(rest.trim(), "a frame number")));
                default -> "err no recorder verb '" + verb + "'";
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "err " + e.getMessage();
        }
    }

    /**
     * {@code stream [lanes|all] [from <seq>] [for <ms>]}: the rows as the recorder takes them, written as they
     * happen (flight-recorder.md §6, the text form). A mistake in the arguments is an {@code err} reply like any
     * other verb's, written by the stream that would have run.
     */
    Commands.Streaming stream(String rest) {
        if (recorder == null) {
            return (in, out) -> out.println(OFF);
        }
        List<String> lanes = new ArrayList<>();
        long from = -1;
        long forMs = -1;
        try {
            String[] words = rest.isBlank() ? new String[0] : rest.trim().split("\\s+");
            for (int i = 0; i < words.length; i++) {
                String w = words[i].toLowerCase(Locale.ROOT);
                if (w.equals("from") || w.equals("for")) {
                    if (i + 1 >= words.length) {
                        throw new IllegalArgumentException(w + " needs a number");
                    }
                    long v = number(words[++i], w.equals("from") ? "a seq" : "a duration in ms");
                    if (w.equals("from")) {
                        from = v;
                    } else {
                        if (v < 1 || v > MAX_STREAM_MS) {
                            throw new IllegalArgumentException("for takes 1 to " + MAX_STREAM_MS + " ms, not " + v);
                        }
                        forMs = v;
                    }
                } else if (w.equals("all")) {
                    lanes.clear();
                } else if (lanes.isEmpty() && i == 0) {
                    for (String key : w.split(",")) {
                        Follower.lane(key);     // an unknown lane fails here, naming the ones there are
                        lanes.add(key.trim().toLowerCase(Locale.ROOT));
                    }
                } else {
                    throw new IllegalArgumentException("stream takes [lanes|all] [from <seq>] [for <ms>], not '"
                            + words[i] + "'");
                }
            }
        } catch (IllegalArgumentException e) {
            String why = "err " + e.getMessage();
            return (in, out) -> out.println(why);
        }
        return new TextStream(recorder, lanes, from, forMs);
    }

    private String frames(String rest) {
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+", 2);
        if (parts.length == 0) {
            return Replies.text(Queries.lastFrames(recorder, FrameList.DEFAULT_COUNT));
        }
        String how = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        if (how.chars().allMatch(Character::isDigit)) {
            return Replies.text(Queries.lastFrames(recorder, (int) number(how, "a count")));
        }
        int n = arg.isEmpty() ? FrameList.DEFAULT_COUNT : -1;
        return switch (how) {
            case "last" -> Replies.text(Queries.lastFrames(recorder, n > 0 ? n : (int) number(arg, "a count")));
            case "worst" -> Replies.text(Queries.worstFrames(recorder, n > 0 ? n : (int) number(arg, "a count")));
            case "since" -> arg.isEmpty()
                    ? "err frames since takes a mark's note (mark <note> writes one)"
                    : Replies.text(Queries.framesSince(recorder, arg, FrameList.DEFAULT_COUNT));
            default -> "err frames takes last N, worst N or since <mark>, not '" + parts[0] + "'";
        };
    }

    /** {@code rows}' options, as {@code csvview}'s: each one narrows, and a bare number is the count. */
    static RowFilter filter(String rest) {
        List<String> lanes = new ArrayList<>();
        String kind = null;
        String thread = null;
        String grep = null;
        long frame = -1;
        int limit = RowFilter.DEFAULT_LIMIT;
        String[] words = rest.isBlank() ? new String[0] : rest.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.startsWith("--")) {
                if (i + 1 >= words.length) {
                    throw new IllegalArgumentException(w + " needs a value");
                }
                String v = words[++i];
                if (w.equals("--lane")) {
                    lanes.addAll(List.of(v.split(",")));
                } else if (w.equals("--kind")) {
                    kind = v;
                } else if (w.equals("--thread")) {
                    thread = v;
                } else if (w.equals("--grep")) {
                    grep = v;
                } else if (w.equals("--frame")) {
                    frame = number(v, "a frame number");
                } else {
                    throw new IllegalArgumentException("rows has no option " + w
                            + "; it takes --lane, --kind, --thread, --grep, --frame and a count");
                }
            } else {
                limit = (int) number(w, "a count");
            }
        }
        return new RowFilter(lanes, kind, thread, grep, frame, limit);
    }

    private static long number(String s, String what) {
        try {
            long v = Long.parseLong(s);
            if (v < 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not " + what);
        }
    }
}
