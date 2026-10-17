package dev.vexelray.gui.recorder;

/**
 * One frame, in one line: the shape {@code frames} lists.
 *
 * @param frame     the frame number ({@code LoopCounters.frame()})
 * @param atNs      when its {@code frame} span began, in {@code t_mono_ns}
 * @param tookUs    the {@code frame} span: the loop's work, not counting the park after it
 * @param stage     the named stage it spent most time in (see {@link FrameExplanation#inside()}), {@code other}
 *                  when time outside every named stage was the largest, or {@code -}
 * @param stageUs   time in that stage, or -1
 * @param woke      what woke the loop for it: {@code wake}, {@code input}, {@code timeout}, {@code unparked}, or
 *                  {@code -} when nothing recorded says
 * @param drawn     {@code yes}, {@code no}, or {@code -} when the lanes that would say are off
 * @param presented {@code yes}, {@code no}, or {@code -}
 * @param park      the budget the loop parked on after it ({@code loop.park}: {@code 10ms}, {@code forever}), or
 *                  {@code -}
 */
public record FrameSummary(long frame, long atNs, long tookUs, String stage, long stageUs, String woke, String drawn,
                           String presented, String park) {
}
