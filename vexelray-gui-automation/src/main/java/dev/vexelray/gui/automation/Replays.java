package dev.vexelray.gui.automation;

import java.util.function.Supplier;

/**
 * {@code replay}: run what this program's logic has taken in so far again, on a fresh set of its components, and say
 * which of their sends differ (vexelray-framework's ROADMAP, <i>Done means</i> 5; vexelray-inspector's R6).
 *
 * <p><b>Handed in, the way the bus graph is.</b> The replay is the framework's, which knows the components and keeps
 * the recording; this layer knows neither, so the framework's driver declares what answers ({@link #declare}) before
 * any socket opens, and nothing declared means a program that cannot replay, which {@code replay} says.
 *
 * <p>The answer is the framework's text: {@code ok replay ticks=<n> sends=<n> differ=<n>}, {@code full} when the
 * recording stopped at its bound, then each difference listed: its own line, {@code   now ...} under it for what the
 * replay sent there, and {@code   was ...} for what the run sent when it kept its sends; or {@code err replay: ...}.
 * The asking connection waits while the replay runs on a thread of its own, as long as the run's logic took, or less.
 */
public final class Replays {

    private static volatile Supplier<String> replay;

    private Replays() {
    }

    /** Declare what answers {@code replay}. Called once at startup; a later call replaces it. */
    public static void declare(Supplier<String> answer) {
        replay = answer;
    }

    /** {@code replay}'s reply. */
    static String answer() {
        Supplier<String> r = replay;
        if (r == null) {
            return "err replay: this program declares no replay (an application built on vexelray-framework declares"
                    + " one at startup through Replays.declare)";
        }
        return r.get();
    }

    /** Forget it. For tests, which share one process. */
    static void clear() {
        replay = null;
    }
}
