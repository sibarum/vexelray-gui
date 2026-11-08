package dev.vexelray.gui.core;

/**
 * The moment a part is asked to draw for: the frame contract's {@code Frame} (docs/plans/frame-contract.md B).
 *
 * <p>A part's asked half ({@link dev.vexelray.gui.core.spi.Asked}, and the framework's own {@code PaintMotion} and
 * {@code LayoutMotion}) chooses what its nodes show for {@link #presentNanos} and answers when it next needs asking:
 * {@link #NEVER} when it is at rest, a time at or before {@link #presentNanos} when it wants the next frame, or a time
 * after it when it has nothing to change until then. The loop parks until the earliest of those, so a still part costs
 * no frame.
 *
 * <p><b>The present time is the tree's clock</b> ({@link dev.vexelray.gui.core.spi.Clock#nanos}), sampled once as the
 * frame's motion stage begins. That is the moment the frame was started, not yet the presenter's prediction of when it
 * will be seen (tree-thread.md B); when that prediction exists it replaces this value and nothing that reads it changes.
 * A told change and the frame that first shows it read the same clock, so a motion started by a change begins at the
 * frame it is first drawn in.
 *
 * <p><b>The same moment on the machine's clock</b> ({@link #monoNanos}), sampled with it. The tree's clock is the one
 * to move by: it steps with a test or a capture, and a due time is on it. But it is logical time, counting from its
 * own zero and falling behind after a stall, so it cannot be set beside a time stamped elsewhere. A part that lines up
 * with another process's events reads this instead: the Inspector's live timeline places a target's rows, stamped with
 * the target's {@code System.nanoTime()}, against it. On one machine the two are one clock.
 *
 * <p>What it carries is what a witness has needed, and no more: the window's scale is added when one does.
 *
 * @param presentNanos  the moment drawn for, in the tree clock's nanoseconds; due times are on this clock
 * @param monoNanos     the same moment on the machine's monotonic clock, {@code System.nanoTime()}'s
 * @param reducedMotion whether motion is reduced ({@link Gui#reducedMotion()}): a part that moves lands at once
 */
public record Frame(long presentNanos, long monoNanos, boolean reducedMotion) {

    /** The answer of a part with nothing to change until it is told something: never ask again on its own account. */
    public static final long NEVER = Long.MAX_VALUE;

    /** Whether a part that answered {@code due} wants this frame's successor: it is due at or before this moment. */
    public boolean owes(long due) {
        return due <= presentNanos;
    }
}
