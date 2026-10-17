package dev.vexelray.gui.recorder;

import java.util.List;

/**
 * The answer to {@code why <frame>}: one frame, explained from the rows the probe already emits.
 *
 * <p>Where a field cannot be derived from those rows it says so rather than guessing: a stage whose lane is off
 * has {@code us == -1}, and {@link #unrecorded} names what no row records that a fuller answer would need.
 *
 * @param frame      the frame number
 * @param atNs       when its {@code frame} span began, in {@code t_mono_ns}
 * @param tookUs     the {@code frame} span
 * @param drawn      {@code yes}, {@code no} or {@code -}
 * @param presented  {@code yes}, {@code no} or {@code -}
 * @param woke       what woke the loop for it
 * @param stages     the frame's top-level spans on the loop thread ({@code before}, {@code update},
 *                   {@code draw}) and {@code other}, the rest of {@code tookUs}: together they make it up
 * @param inside     the named pieces inside those ({@code input}, {@code clock}, {@code dispatch},
 *                   {@code drain}, {@code layout}, {@code resolve}, {@code emit}, {@code gpu_wait},
 *                   {@code submit}, {@code present}); these need not add up to anything
 * @param passes     the layout passes the frame ran, in order
 * @param drained    messages the loop thread's pumps delivered in the frame, or -1 when the bus lane is off
 * @param published  publishes since the previous frame's work ended, by topic; empty when the bus lane is off
 * @param park       the budget it parked on afterwards, or {@code -}
 * @param waitedUs   how long that park lasted, or -1 when it did not park or the frame lane is off
 * @param slowSpans  spans in the frame the probe flagged over its {@code probe.slow} threshold
 * @param unrecorded what a fuller answer would need that no row records
 * @param see        the verb that shows the evidence
 * @param span       the rows this was computed from: the frame before (for what woke it) and the frame itself
 */
public record FrameExplanation(long frame, long atNs, long tookUs, String drawn, String presented, Wake woke,
                               List<Stage> stages, List<Stage> inside, List<LayoutPass> passes, long drained,
                               List<Published> published, String park, long waitedUs, long slowSpans,
                               List<String> unrecorded, String see, Span span) {

    public FrameExplanation {
        stages = List.copyOf(stages);
        inside = List.copyOf(inside);
        passes = List.copyOf(passes);
        published = List.copyOf(published);
        unrecorded = List.copyOf(unrecorded);
    }

    /**
     * What woke the loop for a frame.
     *
     * @param kind   {@code wake} (a {@code wake} mark: a tree was owed a frame), {@code input} (OS key edges in the
     *               frame), {@code timeout} (the previous park ran its whole budget), {@code unparked} (the previous
     *               frame parked on nothing), or {@code -}
     * @param detail the first wake's own words, or what the kind was decided from
     * @param count  how many wake marks there were
     * @param thread the thread that posted the first wake, or {@code -}
     */
    public record Wake(String kind, String detail, int count, String thread) {
    }

    /**
     * Time in one stage.
     *
     * @param name the stage
     * @param lane the lane its rows are on
     * @param us   microseconds, or -1 when that lane is off and the time cannot be known
     */
    public record Stage(String name, String lane, long us) {
    }

    /**
     * One layout pass.
     *
     * @param kind {@code flex layout}, or {@code flex layout (reveal)} for the second pass a reveal pays for
     * @param us   its time
     */
    public record LayoutPass(String kind, long us) {
    }

    /**
     * Publishes to one topic.
     *
     * @param topic   the topic
     * @param count   publishes
     * @param threads the threads that published, comma-separated
     */
    public record Published(String topic, int count, String threads) {
    }
}
