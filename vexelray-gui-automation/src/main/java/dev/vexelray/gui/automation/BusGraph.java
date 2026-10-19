package dev.vexelray.gui.automation;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The declared message graph of this process: which components and publishers there are, which mailbox subscribes
 * to which topic, and who declares publishing it — the text the framework's processor writes as {@code BUS_GRAPH}
 * (vexelray-inspector docs/bus-circuit.md §1, version 1). The Inspector reads it once on attaching, with the read
 * verb {@code bus graph}, and draws the bus as a circuit from it.
 *
 * <p><b>Handed in, the way the build identity is.</b> The framework's generated wiring passes its constant to
 * {@link #declare(String)} at startup, before any socket opens, as its driver passes {@code BuildInfo.facts()} to
 * {@link Discovery#build(java.util.Map)}. Process-wide, because the declarations are the program's. Nothing
 * declared means none: an application not built by the processor has no graph, and {@code bus graph} says so.
 *
 * <p><b>Checked at the door.</b> The first line must be {@value #HEADER}; anything else — another version, no
 * header, no text — is refused when it is declared, so a mismatch between the processor and this build fails at
 * startup rather than as a circuit the Inspector cannot draw. The lines are kept as given; their meaning is the
 * Inspector's.
 */
public final class BusGraph {

    /** The first line of every graph this build reads: the format and its version. */
    public static final String HEADER = "# vexelray bus graph 1";

    /** The reply terminator on the automation socket, which no graph line may be. */
    private static final String TERMINATOR = ".";

    private static volatile List<String> lines;

    private BusGraph() {
    }

    /**
     * Declare this process's graph: {@code text} as the processor wrote it, {@value #HEADER} first, {@code \n} line
     * ends. Called once at startup; a later call replaces it, as a later {@link Discovery#build} does.
     *
     * @throws IllegalArgumentException when {@code text} is null, empty, does not begin with {@value #HEADER}, or
     *                                  holds a line that is only {@code .}
     */
    public static void declare(String text) {
        lines = parse(text);
    }

    /** The graph as declared, without its trailing line ends; empty when none was. */
    public static Optional<String> text() {
        List<String> l = lines;
        return l == null ? Optional.empty() : Optional.of(String.join("\n", l));
    }

    /** The graph's lines, header first; empty when none was declared. */
    static List<String> lines() {
        List<String> l = lines;
        return l == null ? List.of() : l;
    }

    /**
     * {@code bus graph}'s reply: {@code ok bus graph lines=<n>} and then the lines, or {@code err no bus graph: ...}
     * when nothing was declared.
     */
    static String answer() {
        List<String> l = lines;
        if (l == null) {
            return "err no bus graph: none was declared (an application built by vexelray-framework's processor "
                    + "declares one at startup through BusGraph.declare)";
        }
        return "ok bus graph lines=" + l.size() + "\n" + String.join("\n", l);
    }

    /** Forget the graph. For tests, which share one process. */
    static void clear() {
        lines = null;
    }

    static List<String> parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("a bus graph needs text, beginning '" + HEADER + "'");
        }
        String trimmed = text.replaceAll("\n+$", "");
        List<String> l = List.copyOf(Arrays.asList(trimmed.split("\n", -1)));
        if (!l.getFirst().equals(HEADER)) {
            throw new IllegalArgumentException("a bus graph begins '" + HEADER + "', and this one begins '"
                    + l.getFirst() + "'; this build reads version 1 only");
        }
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).equals(TERMINATOR)) {
                throw new IllegalArgumentException("line " + (i + 1) + " of the bus graph is '.', which ends a reply"
                        + " on the automation socket");
            }
        }
        return l;
    }
}
