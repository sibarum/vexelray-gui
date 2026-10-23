package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Notices a machine that cannot draw this application at a usable rate, offers the user simplified graphics, and keeps
 * it only if it helped: the frame loop's side of the offer, with no window, clock or widget of its own, so every turn
 * of it can be driven by a test.
 *
 * <h2>What counts as slow</h2>
 *
 * <p>A frame's <em>cost</em>: from the top of a loop iteration that drew to the end of its present, which is what the
 * machine spent producing that frame. Not the gap between frames, which is mostly the loop parked on nothing to do (a
 * render-on-demand application idles at a frame a minute and is not slow), and not a posted task, which the loop times
 * and reports as a stall of its own. A frame costing {@link #SLOW_NANOS} or more cannot be drawn five times a second.
 *
 * <p>The machine is struggling when, after {@link #WARM_UP_NANOS} of drawing (the first frames pay for shaders, atlases
 * and uploads, once), at least {@link #SLOW_NEEDED} of the last {@link #WINDOW} frames drawn were slow, and they span
 * at least {@link #SPAN_NANOS}: repeatedly, not one hitch.
 *
 * <h2>What happens then</h2>
 *
 * <ol>
 *   <li>The user is asked ({@link Offer}), once a session. "Not now" ends it for the session; "Never" is saved for
 *       every application ({@link SuiteGraphics#refuseOffers}).</li>
 *   <li>"Switch" turns simplified graphics on at once, for every tree, as a trial: live, so nothing open is lost.</li>
 *   <li>After {@link #TRIAL_FRAMES} frames drawn with it, it is judged by the median cost: helped if back under
 *       {@link #SLOW_NANOS}, or at least halved. Helped, it is saved for every application
 *       ({@link SuiteGraphics#saveSimplified}). Not helped, the machine is slow for some other reason, and the trial is
 *       taken back: the user would be giving up depth and motion for nothing.</li>
 * </ol>
 *
 * <p>Loop thread only. {@link #drawn} runs every frame, so it does not allocate; deciding does, once.
 */
public final class SlowFrames {

    /** A frame that costs this much cannot be drawn five times a second. */
    public static final long SLOW_NANOS = 200_000_000L;

    /** How long after the first frame drawn the watch starts counting: startup pays for things once. */
    public static final long WARM_UP_NANOS = 10_000_000_000L;

    /** How many of the most recent frames drawn the watch looks at. */
    public static final int WINDOW = 12;

    /** How many of those must be slow. */
    public static final int SLOW_NEEDED = 10;

    /** How far apart the first and last of them must start, so a burst of hitches is not a slow machine. */
    public static final long SPAN_NANOS = 3_000_000_000L;

    /** Frames drawn after switching that are not judged: the switch itself redraws everything once. */
    public static final int TRIAL_SKIP = 2;

    /** Frames drawn with simplified graphics that the trial is judged on. */
    public static final int TRIAL_FRAMES = 12;

    /** The user's answer to the offer. */
    public enum Answer {
        /** Try simplified graphics now, and keep it if it helps. */
        SWITCH,
        /** Not this session. */
        NOT_NOW,
        /** Never, in any application. */
        NEVER
    }

    /**
     * Asks the user whether to switch to simplified graphics: the prompt, which needs widgets the frame loop does not
     * have, so the layer that has them installs it ({@code GuiApp.slowFramesOffer}). Without one, a struggling machine
     * is reported in the diagnostics and nothing is asked.
     */
    @FunctionalInterface
    public interface Offer {
        /**
         * Ask, on {@code gui}'s window. {@code typicalFrameNanos} is the median cost of the slow frames, for a prompt
         * that wants to say how slow. Call {@code answer} exactly once, from any thread; closing the prompt without
         * choosing is {@link Answer#NOT_NOW}.
         */
        void ask(Gui gui, long typicalFrameNanos, Consumer<Answer> answer);
    }

    /** What the offer does to the world: the frame loop's side, behind an interface so a test can stand in for it. */
    interface Host {
        /** Whether there is anything to offer: simplified graphics not already on, and the offer not refused. */
        boolean worthOffering();

        /** Put the offer to the user, with the slow frames' median cost; the answer comes back to {@link #answered}. */
        void ask(long typicalFrameNanos);

        /** Turn the trial on for every tree, or off again, back to whatever the trees had before it. */
        void trial(boolean on);

        /** Save simplified graphics for every application. */
        void saveSimplified();

        /** Save that the user does not want the offer again. */
        void refuseOffers();

        /** One line for the diagnostics, deduplicated by {@code key}. */
        void report(String key, String what, String why);
    }

    private enum State { WATCHING, ASKED, TRIAL, DONE }

    private final Host host;
    private State state = State.WATCHING;

    private long firstDrawn = -1L;
    private final long[] starts = new long[WINDOW];
    private final long[] costs = new long[WINDOW];
    private int count;                // frames in the ring, up to WINDOW
    private int next;                 // where the next goes
    private long before;              // the slow frames' median cost, once asked

    private int skip;
    private final long[] trial = new long[TRIAL_FRAMES];
    private int trialCount;

    SlowFrames(Host host) {
        this.host = host;
    }

    /** One frame that drew: it began at {@code startNanos} and cost {@code costNanos}. Loop thread; no allocation. */
    void drawn(long startNanos, long costNanos) {
        if (state == State.WATCHING) {
            watch(startNanos, costNanos);
        } else if (state == State.TRIAL) {
            judge(costNanos);
        }
    }

    /** The user's answer. Loop thread: the host hands it here through the loop's own queue. */
    void answered(Answer answer) {
        if (state != State.ASKED) {
            return;
        }
        if (answer == Answer.SWITCH) {
            state = State.TRIAL;
            skip = TRIAL_SKIP;
            trialCount = 0;
            host.trial(true);
        } else {
            state = State.DONE;
            if (answer == Answer.NEVER) {
                host.refuseOffers();
            }
        }
    }

    private void watch(long start, long cost) {
        if (firstDrawn < 0L) {
            firstDrawn = start;
        }
        if (start - firstDrawn < WARM_UP_NANOS) {
            return;
        }
        starts[next] = start;
        costs[next] = cost;
        next = (next + 1) % WINDOW;
        count = Math.min(count + 1, WINDOW);
        if (count < WINDOW || cost < SLOW_NANOS) {
            return;   // judged only when the newest frame is slow too: a recovered machine is not offered anything
        }
        int slow = 0;
        for (long c : costs) {
            if (c >= SLOW_NANOS) {
                slow++;
            }
        }
        long oldest = starts[next];   // the ring is full, so the next slot holds the oldest frame
        if (slow < SLOW_NEEDED || start - oldest < SPAN_NANOS) {
            return;
        }
        state = State.ASKED;
        before = median(costs);
        if (!host.worthOffering()) {
            state = State.DONE;
            return;
        }
        host.report("SlowFrames/struggling", "frames are costing " + millis(before) + " ms",
                "fewer than five a second; offering simplified graphics");
        host.ask(before);
    }

    private void judge(long cost) {
        if (skip > 0) {
            skip--;
            return;
        }
        trial[trialCount++] = cost;
        if (trialCount < TRIAL_FRAMES) {
            return;
        }
        state = State.DONE;
        long after = median(trial);
        if (after < SLOW_NANOS || after * 2L <= before) {
            host.saveSimplified();
            host.report("SlowFrames/kept", "simplified graphics brought frames from " + millis(before) + " ms to "
                    + millis(after) + " ms", "saved for every VexelRay application");
        } else {
            host.trial(false);
            host.report("SlowFrames/undone", "simplified graphics left frames at " + millis(after) + " ms (from "
                    + millis(before) + " ms)", "the time goes somewhere else, so it was turned off again");
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }
}
